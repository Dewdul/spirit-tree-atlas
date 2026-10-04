#!/usr/bin/env python3
"""Build src/main/resources/com/fairyringatlas/rings.json (DESIGN.md section 3.1).

Inputs (all under tools/data/):
  fairy_rings.verified.json  research dataset: codes, landing tiles, game flags, log varbits
  ring_display.json          curated player-facing text: names, areas, descriptions,
                             requirement lines, dangers, POIs, search tags, notes
  portals.json               surface entrances of underground layers
  unlock.json                what must hold before a first visit can unlock a ring
                             (quest states and vars the plugin reads; optional per ring)

The output is deterministic: same inputs, same bytes. The script fails loudly (exit 1)
on any contract violation. Standard library only; Python 3.8+.

Usage:
  python tools/build_rings.py                 validate and write rings.json
  python tools/build_rings.py --check         validate only; exit 1 if rings.json is stale
  python tools/build_rings.py --dbrow dump.dbrow --varbits VarbitID.java
                                              also cross-check against a cache dump
                                              (Joshua-F/osrs-dumps config/dump.dbrow) and
                                              RuneLite's gameval VarbitID source
"""

import argparse
import json
import os
import re
import sys

TOOLS = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(TOOLS)
DATA = os.path.join(TOOLS, 'data')
DATASET = os.path.join(DATA, 'fairy_rings.verified.json')
DISPLAY = os.path.join(DATA, 'ring_display.json')
PORTALS = os.path.join(DATA, 'portals.json')
UNLOCK = os.path.join(DATA, 'unlock.json')
OUT = os.path.join(ROOT, 'src', 'main', 'resources', 'com', 'fairyringatlas', 'rings.json')
INDEX = os.path.join(ROOT, 'src', 'main', 'resources', 'com', 'fairyringatlas', 'map', 'index.json')

# DESIGN 3.3. Bounds are world tiles, inclusive-exclusive [x0, y0, x1, y1].
# Approximate by design; tools/mapgen may tighten them in map/index.json.
LAYERS = {
    'surface':          ('Gielinor',                      (1152, 2368, 3776, 4032)),
    'zanaris':          ('Zanaris',                       (2304, 4288, 2560, 4544)),
    'abyss':            ('The Abyss',                     (2880, 4672, 3200, 4992)),
    'dorgesh_south':    ('Dorgesh-Kaan South Dungeon',    (2624, 5120, 2816, 5312)),
    'fisher_realm':     ('Fisher Realm',                  (2560, 4608, 2752, 4800)),
    'enchanted_valley': ('Enchanted Valley',              (2944, 4416, 3136, 4608)),
    'mor_ul_rek':       ('Mor Ul Rek',                    (2304, 4928, 2624, 5248)),
    'cosmic_plane':     ("Cosmic entity's plane",         (1984, 4736, 2176, 4928)),
    'gorak_plane':      ('Gorak Plane',                   (2944, 5248, 3136, 5440)),
    # 3x3 map regions around the ring: BLQ (3572,4372) is in region (55,68).
    'yubiusk':          ("Yu'biusk",                      (3456, 4288, 3648, 4480)),
    # 3x3 map regions around the ring: DLP (2926,10455) is in region (45,163).
    'grimstone':        ('Grimstone Dungeon',             (2816, 10368, 3008, 10560)),
    'hollows':          ('Myreque Hideout (The Hollows)', (3328, 9728, 3584, 9984)),
    'poh':              ('Your house',                    None),
}

# Off-surface layer per code (DESIGN 3.3). Every other dialable code is on 'surface'.
OFF_SURFACE = {
    'AJQ': 'dorgesh_south',
    'ALR': 'abyss',
    'DIP': 'abyss',
    'BJR': 'fisher_realm',
    'BKQ': 'enchanted_valley',
    'BKS': 'zanaris',
    'BLP': 'mor_ul_rek',
    'CKP': 'cosmic_plane',
    'DIR': 'gorak_plane',
    'BLQ': 'yubiusk',
    'DLP': 'grimstone',
    'DLS': 'hollows',
    'DIQ': 'poh',
}

EXPECTED_DIALABLE = 55          # 64 combinations minus 9 unused (wiki; game dbtable 89)
EXPECTED_SURFACE = 42
UNUSED_CODES = {'AIP', 'BIR', 'BJQ', 'CJP', 'CJS', 'CLQ', 'DJQ', 'DJS', 'DKQ'}
HIDEOUT_ID = 'HIDEOUT'
HIDEOUT_LOG_VARBIT = 4026       # VarbitID.FAIRYRINGS_LOG_HIDEOUT
CODE_RE = re.compile(r'^[ABCD][IJKL][PQRS]$')
REQ_PREFIXES = ('Quest: ', 'Skill: ', 'Unlock: ', 'Item: ', 'Diary: ')
NAME_MAX = 28
DESCRIPTION_MAX = 120
LINE_MAX = 140
EDGE_WARN = 64                  # report rings closer than one map region to a layer edge

KEY_GROUPS = [
    ('code', 'id', 'kind', 'name'),
    ('description',),
    ('x', 'y', 'plane', 'layer', 'area'),
    ('requirements',),
    ('danger',),
    ('poi',),
    ('tags',),
    ('notes',),
    ('logVarbit', 'noStaffReturn'),
    ('unlock',),
    ('sequence',),
]

# unlock.json: condition types, comparisons and quest states the plugin understands (UnlockCheck)
UNLOCK_TYPES = ('quest', 'varbit', 'varp', 'unknown')
UNLOCK_OPS = ('>=', '>', '==')
UNLOCK_STATES = ('FINISHED', 'IN_PROGRESS')
UNLOCK_CONFIDENCE = ('high', 'medium', 'low')
UNLOCK_LABEL_MAX = 32           # 'Locked - needs <label>' stays one line on the compact card
QUEST_RE = re.compile(r'^[A-Z][A-Z0-9_]*$')


class BuildError(Exception):
    pass


def load(path):
    with open(path, encoding='utf-8') as f:
        return json.load(f)


def entry_id(entry):
    """Stable id: the code for dialable rings, HIDEOUT for the sequence, else the dataset id."""
    if entry.get('kind') == 'sequence':
        return HIDEOUT_ID
    return entry.get('code') or entry['id']


def layer_for(entry):
    if entry['kind'] in ('sequence', 'exit'):
        return 'zanaris'
    return OFF_SURFACE.get(entry['code'], 'surface')


def check_text(errors, where, value, limit):
    if not isinstance(value, str) or not value.strip():
        errors.append('%s: empty text' % where)
        return
    if value != value.strip() or '\n' in value or '  ' in value:
        errors.append('%s: stray whitespace or line break: %r' % (where, value))
    if len(value) > limit:
        errors.append('%s: %d chars > %d: %r' % (where, len(value), limit, value))
    # The info card uses RuneScape bitmap fonts; keep to printable ASCII so every glyph exists.
    bad = sorted({c for c in value if not (' ' <= c <= '~')})
    if bad:
        errors.append('%s: non-ASCII characters %r in %r' % (where, bad, value))


def check_list(errors, where, values, limit=LINE_MAX):
    if not isinstance(values, list):
        errors.append('%s: not a list' % where)
        return
    for i, v in enumerate(values):
        check_text(errors, '%s[%d]' % (where, i), v, limit)
    if len(set(values)) != len(values):
        errors.append('%s: duplicate entries' % where)


def unlock_conditions(errors, code, entry, ring):
    """Validates one unlock.json entry; returns its conditions as rings.json carries them."""
    where = '%s.unlock' % code
    conds = entry.get('all') if isinstance(entry, dict) else None
    if not isinstance(conds, list) or not conds:
        errors.append('%s: needs a non-empty "all" list' % where)
        return []
    if set(entry) - {'all'}:
        errors.append('%s: unknown keys %s' % (where, sorted(set(entry) - {'all'})))
    if not ring['requirements']:
        errors.append('%s: the ring has no requirement lines to explain it' % where)
    out = []
    for i, c in enumerate(conds):
        w = '%s[%d]' % (where, i)
        t = c.get('type')
        if t not in UNLOCK_TYPES:
            errors.append('%s: type %r not in %s' % (w, t, UNLOCK_TYPES))
            continue
        check_text(errors, w + '.label', c.get('label'), UNLOCK_LABEL_MAX)
        conf = c.get('confidence')
        if conf not in UNLOCK_CONFIDENCE:
            errors.append('%s: confidence %r not in %s' % (w, conf, UNLOCK_CONFIDENCE))
        # a guess must never lock a ring away: a low-confidence condition is only ever a hint
        if conf == 'low' and t != 'unknown':
            errors.append('%s: a low-confidence condition must be type "unknown"' % w)
        if not c.get('source'):
            errors.append('%s: no source' % w)
        o = {'type': t}
        if t == 'quest':
            if not QUEST_RE.match(str(c.get('quest', ''))):
                errors.append('%s: quest %r is not a Quest constant name' % (w, c.get('quest')))
            if c.get('state') not in UNLOCK_STATES:
                errors.append('%s: state %r not in %s' % (w, c.get('state'), UNLOCK_STATES))
            o['quest'] = c.get('quest')
            o['state'] = c.get('state')
        elif t in ('varbit', 'varp'):
            if not isinstance(c.get('id'), int) or c['id'] < 0:
                errors.append('%s: bad id %r' % (w, c.get('id')))
            if c.get('op') not in UNLOCK_OPS:
                errors.append('%s: op %r not in %s' % (w, c.get('op'), UNLOCK_OPS))
            if not isinstance(c.get('value'), int):
                errors.append('%s: bad value %r' % (w, c.get('value')))
            prefix = 'VarbitID.' if t == 'varbit' else 'VarPlayerID.'
            if not str(c.get('name', '')).startswith(prefix):
                errors.append('%s: name %r must be the %s constant' % (w, c.get('name'), prefix))
            o['id'] = c.get('id')
            o['op'] = c.get('op')
            o['value'] = c.get('value')
        o['label'] = c.get('label')
        out.append(o)
    if out and all(o['type'] == 'unknown' for o in out):
        errors.append('%s: only unknown conditions; say it in notes instead' % where)
    return out


def build(dataset, display, portals_doc, unlock_doc=None):
    errors, warnings = [], []
    shown = display['rings']
    unlocks = (unlock_doc or {}).get('rings', {})

    # ---- the dataset itself ---------------------------------------------------------
    ids = [entry_id(e) for e in dataset]
    if len(set(ids)) != len(ids):
        errors.append('duplicate ids in dataset: %s' % sorted({i for i in ids if ids.count(i) > 1}))
    codes = [e['code'] for e in dataset if e['kind'] == 'destination']
    if len(set(codes)) != len(codes):
        errors.append('duplicate codes: %s' % sorted({c for c in codes if codes.count(c) > 1}))
    for c in codes:
        if not CODE_RE.match(c or ''):
            errors.append('invalid code %r' % c)
    if len(codes) != EXPECTED_DIALABLE:
        errors.append('expected %d dialable codes, found %d' % (EXPECTED_DIALABLE, len(codes)))
    if 'DIQ' not in codes:
        errors.append('DIQ missing')
    if set(codes) & UNUSED_CODES:
        errors.append('unused codes present: %s' % sorted(set(codes) & UNUSED_CODES))
    all_codes = {a + b + c for a in 'ABCD' for b in 'IJKL' for c in 'PQRS'}
    if set(codes) | UNUSED_CODES != all_codes:
        errors.append('codes plus unused codes do not cover all 64: missing %s'
                      % sorted(all_codes - set(codes) - UNUSED_CODES))
    kinds = sorted(e['kind'] for e in dataset)
    if kinds.count('sequence') != 1 or kinds.count('exit') != 2:
        errors.append('expected 1 sequence and 2 exit entries, found %s' % kinds)
    if set(OFF_SURFACE) - set(codes):
        errors.append('OFF_SURFACE names unknown codes: %s' % sorted(set(OFF_SURFACE) - set(codes)))

    # ---- display data covers exactly the dataset -------------------------------------
    missing = sorted(set(ids) - set(shown))
    extra = sorted(set(shown) - set(ids))
    if missing:
        errors.append('ring_display.json lacks: %s' % missing)
    if extra:
        errors.append('ring_display.json has unknown ids: %s' % extra)
    stray = sorted(set(unlocks) - set(codes))
    if stray:
        errors.append('unlock.json names codes that are not dialable rings: %s' % stray)
    if errors:
        raise BuildError('\n'.join(errors))

    check_list(errors, 'global.requirements', display['global']['requirements'])
    if not any('Fairytale II' in r for r in display['global']['requirements']):
        errors.append('global.requirements must name Fairytale II')
    if not any('Dramen' in r and 'Lunar' in r and 'Lumbridge & Draynor' in r
               for r in display['global']['requirements']):
        errors.append('global.requirements must state the Dramen/Lunar staff rule and the diary exemption')

    # ---- assemble rings ---------------------------------------------------------------
    def sort_key(e):
        # dialable codes alphabetically, then the sequence, then exits by id
        order = {'destination': 0, 'sequence': 1, 'exit': 2}[e['kind']]
        return (order, entry_id(e))

    rings = []
    log_varbits = {}
    for e in sorted(dataset, key=sort_key):
        rid = entry_id(e)
        d = shown[rid]
        game = e.get('game') or {}
        where = rid
        layer = layer_for(e)
        kind = e['kind']

        if kind == 'destination':
            log_varbit = game.get('logVarbit')
        elif kind == 'sequence':
            log_varbit = HIDEOUT_LOG_VARBIT
        else:
            log_varbit = -1

        if rid == 'DIQ':
            x, y, plane = 0, 0, 0
        else:
            x, y, plane = e['x'], e['y'], e['plane']

        ring = {
            'code': e['code'] if kind == 'destination' else None,
            'id': rid,
            'kind': kind,
            'name': d['name'],
            'description': d['description'],
            'x': x, 'y': y, 'plane': plane,
            'layer': layer,
            'area': d['area'],
            'requirements': list(d['requirements']),
            'danger': list(d['danger']),
            'poi': list(d['poi']),
            'tags': list(d['tags']),
            'notes': list(d['notes']),
            'logVarbit': log_varbit,
            'noStaffReturn': bool(game.get('noStaffReturn', False)),
        }
        if kind == 'destination' and rid in unlocks:
            ring['unlock'] = unlock_conditions(errors, rid, unlocks[rid], ring)
        if kind == 'sequence':
            seq = e['code'].split('-')
            ring['sequence'] = seq
            if any(not CODE_RE.match(s) for s in seq) or len(seq) != 4:
                errors.append('%s: bad sequence %r' % (where, e['code']))
        rings.append(ring)

        # text
        check_text(errors, where + '.name', ring['name'], NAME_MAX)
        check_text(errors, where + '.description', ring['description'], DESCRIPTION_MAX)
        check_text(errors, where + '.area', ring['area'], 40)
        for key in ('requirements', 'danger', 'poi', 'tags', 'notes'):
            check_list(errors, '%s.%s' % (where, key), ring[key])
        for t in ring['tags']:
            if t != t.lower():
                errors.append('%s.tags: %r is not lowercase' % (where, t))
        for r in ring['requirements']:
            if not r.startswith(REQ_PREFIXES) or len(r) <= len('Item: x'):
                errors.append('%s.requirements: %r must start with one of %s' % (where, r, REQ_PREFIXES))
        if not ring['poi']:
            errors.append('%s.poi: empty' % where)
        elif kind == 'destination' and len(ring['poi']) < 5:
            warnings.append('%s: only %d POIs' % (where, len(ring['poi'])))
        if len(ring['poi']) > 12:
            errors.append('%s.poi: %d > 12 items' % (where, len(ring['poi'])))

        # requirements must not drop anything the dataset requires
        # (optional / recommended items are advice, not unlock requirements; they live in notes)
        # a display entry may list dataset requirements the wiki does not state for this ring
        # ('unconfirmed'); those are told in notes, not shown as requirement lines
        unconfirmed = d.get('unconfirmed', [])
        needed = [r for r in e.get('requirements', [])
                  if 'optional' not in str(r.get('detail', '')).lower()
                  and not str(r.get('detail', '')).lower().startswith('recommended')
                  and r['name'] not in unconfirmed]
        if needed and not ring['requirements']:
            errors.append('%s: dataset has requirements %s but the display has none'
                          % (where, [r['name'] for r in needed]))
        joined = ' | '.join(ring['requirements'])
        for r in needed:
            if r['type'] in ('quest', 'skill') and r['name'] not in joined:
                errors.append('%s: requirement %s %r is not mentioned in %r'
                              % (where, r['type'], r['name'], ring['requirements']))

        # layer and position
        if layer not in LAYERS:
            errors.append('%s: unknown layer %r' % (where, layer))
            continue
        if (layer == 'surface') != bool(e.get('surface')) and kind == 'destination':
            errors.append('%s: dataset surface=%s but layer=%s' % (where, e.get('surface'), layer))
        bounds = LAYERS[layer][1]
        if bounds is None:
            if (x, y, plane) != (0, 0, 0):
                errors.append('%s: layer %s must have 0,0,0' % (where, layer))
        else:
            x0, y0, x1, y1 = bounds
            if plane != 0:
                errors.append('%s: plane %d (all ring tiles are plane 0)' % (where, plane))
            if not (x0 <= x < x1 and y0 <= y < y1):
                errors.append('%s: (%d,%d) outside %s bounds %s' % (where, x, y, layer, bounds))
            else:
                margin = min(x - x0, x1 - 1 - x, y - y0, y1 - 1 - y)
                if margin < EDGE_WARN:
                    warnings.append('%s: %d tiles from the %s layer edge (%d,%d in %s)'
                                    % (where, margin, layer, x, y, bounds))

        # log varbit
        if kind != 'exit':
            if not isinstance(log_varbit, int) or log_varbit <= 0:
                errors.append('%s: missing logVarbit' % where)
            elif log_varbit in log_varbits:
                errors.append('%s: logVarbit %d also used by %s' % (where, log_varbit, log_varbits[log_varbit]))
            else:
                log_varbits[log_varbit] = rid

    surface = [r for r in rings if r['kind'] == 'destination' and r['layer'] == 'surface']
    if len(surface) != EXPECTED_SURFACE:
        errors.append('expected %d surface rings, found %d' % (EXPECTED_SURFACE, len(surface)))
    names = [r['name'] for r in rings]
    if len(set(names)) != len(names):
        errors.append('duplicate names: %s' % sorted({n for n in names if names.count(n) > 1}))

    # ---- portals ----------------------------------------------------------------------
    used_layers = {r['layer'] for r in rings}
    portals = []
    seen = set()
    sx0, sy0, sx1, sy1 = LAYERS['surface'][1]
    for p in portals_doc['portals']:
        where = 'portal %s' % p.get('layer')
        if p['layer'] not in LAYERS or p['layer'] in ('surface', 'poh'):
            errors.append('%s: not an underground layer' % where)
        if p['layer'] not in used_layers:
            errors.append('%s: no ring on that layer' % where)
        if p['layer'] in seen:
            errors.append('%s: more than one portal for the layer' % where)
        seen.add(p['layer'])
        if not (sx0 <= p['x'] < sx1 and sy0 <= p['y'] < sy1):
            errors.append('%s: (%d,%d) outside the surface crop' % (where, p['x'], p['y']))
        check_text(errors, where + '.label', p['label'], NAME_MAX)
        check_text(errors, where + '.description', p['description'], LINE_MAX)
        if not p.get('source'):
            errors.append('%s: no source' % where)
        portals.append({'layer': p['layer'], 'x': p['x'], 'y': p['y'],
                        'label': p['label'], 'description': p['description']})
    portals.sort(key=lambda p: p['layer'])

    if errors:
        raise BuildError('\n'.join(errors))

    doc = {
        '_about': 'Generated by tools/build_rings.py from tools/data/*.json. Do not edit by hand.',
        'rings': rings,
        'global': {'requirements': list(display['global']['requirements'])},
        'portals': portals,
    }
    return doc, warnings


def dump(doc):
    """Compact but readable: one line per field group, lists inline."""
    def enc(v):
        return json.dumps(v, ensure_ascii=False)

    def group_line(obj, keys):
        return ', '.join('%s: %s' % (enc(k), enc(obj[k])) for k in keys if k in obj)

    out = ['{', '  "_about": %s,' % enc(doc['_about']), '  "rings": [']
    for i, r in enumerate(doc['rings']):
        known = {k for g in KEY_GROUPS for k in g}
        unknown = set(r) - known
        if unknown:
            raise BuildError('dump: unexpected keys %s' % sorted(unknown))
        lines = [group_line(r, g) for g in KEY_GROUPS if any(k in r for k in g)]
        body = ',\n     '.join(lines)
        out.append('    {' + body + '}' + (',' if i < len(doc['rings']) - 1 else ''))
    out.append('  ],')
    out.append('  "global": {"requirements": [')
    reqs = doc['global']['requirements']
    for i, r in enumerate(reqs):
        out.append('    ' + enc(r) + (',' if i < len(reqs) - 1 else ''))
    out.append('  ]},')
    out.append('  "portals": [')
    for i, p in enumerate(doc['portals']):
        out.append('    {' + group_line(p, ('layer', 'x', 'y', 'label')) + ',\n     '
                   + group_line(p, ('description',)) + '}'
                   + (',' if i < len(doc['portals']) - 1 else ''))
    out.append('  ]')
    out.append('}')
    text = '\n'.join(out) + '\n'
    if json.loads(text) != doc:
        raise BuildError('dump: round trip mismatch')
    return text


def cross_check_dbrow(path, doc):
    """Compare landing tiles and flags with the game's dbtable 89 rows (osrs-dumps dump.dbrow)."""
    errors = []
    text = open(path, encoding='utf-8', errors='replace').read()
    rows = {}
    for m in re.finditer(r'^\[fairyrings_([a-z]{3})\]\s*\n(.*?)(?=^\[|\Z)', text, re.M | re.S):
        data = dict(re.findall(r'^data=([a-z_]+),(.*)$', m.group(2), re.M))
        rows[m.group(1).upper()] = data
    if len(rows) != 64:
        errors.append('dbrow: expected 64 fairyrings rows, found %d' % len(rows))
    rings = {r['code']: r for r in doc['rings'] if r['code']}
    live = sorted(c for c, d in rows.items() if d.get('desc', '').strip() or c == 'DIQ')
    if live != sorted(rings):
        errors.append('dbrow: live codes differ: only in cache %s, only in json %s'
                      % (sorted(set(live) - set(rings)), sorted(set(rings) - set(live))))
    for code, r in rings.items():
        d = rows.get(code, {})
        p, mx, my, lx, ly = (int(v) for v in d.get('dest_coord', '0_0_0_0_0').split('_'))
        if (mx * 64 + lx, my * 64 + ly, p) != (r['x'], r['y'], r['plane']):
            errors.append('dbrow: %s at (%d,%d,%d) in cache, (%d,%d,%d) in json'
                          % (code, mx * 64 + lx, my * 64 + ly, p, r['x'], r['y'], r['plane']))
        if (d.get('no_staff_return') == 'true') != r['noStaffReturn']:
            errors.append('dbrow: %s no_staff_return differs' % code)
    return errors, len(rows)


def cross_check_varbits(path, doc):
    errors = []
    found = dict((k, int(v)) for k, v in re.findall(
        r'FAIRYRINGS_LOG_([A-Z]+)\s*=\s*(\d+)', open(path, encoding='utf-8', errors='replace').read()))
    for r in doc['rings']:
        key = r['code'] or ('HIDEOUT' if r['kind'] == 'sequence' else None)
        if key is None:
            continue
        if found.get(key) != r['logVarbit']:
            errors.append('varbits: FAIRYRINGS_LOG_%s is %s, json has %s' % (key, found.get(key), r['logVarbit']))
    return errors, len(found)


def cross_check_index(path, doc):
    """If tools/mapgen has written map/index.json, every ring and portal must lie inside its
    (possibly tightened) layer bounds there too; RingDataTest checks the same at build time."""
    errors = []
    layers = {l['id']: l['bounds'] for l in load(path).get('layers', [])}
    for r in doc['rings']:
        if r['layer'] == 'poh':
            continue
        b = layers.get(r['layer'])
        if b is None:
            errors.append('index.json: no layer %r (needed by %s)' % (r['layer'], r['id']))
        elif not (b[0] <= r['x'] < b[2] and b[1] <= r['y'] < b[3]):
            errors.append('index.json: %s (%d,%d) outside %s bounds %s' % (r['id'], r['x'], r['y'], r['layer'], b))
    sb = layers.get('surface')
    for p in doc['portals']:
        if p['layer'] not in layers:
            errors.append('index.json: no layer %r for its portal' % p['layer'])
        if sb and not (sb[0] <= p['x'] < sb[2] and sb[1] <= p['y'] < sb[3]):
            errors.append('index.json: portal %s (%d,%d) outside surface bounds %s' % (p['layer'], p['x'], p['y'], sb))
    return errors, len(layers)


def summary(doc, warnings):
    rings = doc['rings']
    by_layer = {}
    for r in rings:
        by_layer.setdefault(r['layer'], []).append(r['id'])
    poi = [len(r['poi']) for r in rings if r['kind'] == 'destination']
    lines = [
        'entries: %d (%d dialable, %d sequence, %d exit)' % (
            len(rings), sum(r['kind'] == 'destination' for r in rings),
            sum(r['kind'] == 'sequence' for r in rings), sum(r['kind'] == 'exit' for r in rings)),
        'layers: ' + ', '.join('%s=%d' % (k, len(v)) for k, v in sorted(by_layer.items())),
        'with per-ring requirements: %d; with danger: %d; noStaffReturn: %d' % (
            sum(bool(r['requirements']) for r in rings), sum(bool(r['danger']) for r in rings),
            sum(r['noStaffReturn'] for r in rings)),
        'with unlock conditions: %d (%d conditions, %d unknown)' % (
            sum('unlock' in r for r in rings), sum(len(r.get('unlock', [])) for r in rings),
            sum(c['type'] == 'unknown' for r in rings for c in r.get('unlock', []))),
        'POIs per dialable ring: min %d, max %d, total %d; tags total %d' % (
            min(poi), max(poi), sum(poi), sum(len(r['tags']) for r in rings)),
        'portals: ' + ', '.join('%s (%d,%d)' % (p['layer'], p['x'], p['y']) for p in doc['portals']),
    ]
    for w in warnings:
        lines.append('warning: ' + w)
    return '\n'.join(lines)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('--check', action='store_true', help='validate only; fail if rings.json is stale')
    ap.add_argument('--out', default=OUT, help='output path (default: the plugin resource)')
    ap.add_argument('--dbrow', help='osrs-dumps config/dump.dbrow to cross-check coordinates')
    ap.add_argument('--varbits', help='RuneLite gameval VarbitID source to cross-check log varbits')
    args = ap.parse_args(argv)
    try:
        doc, warnings = build(load(DATASET), load(DISPLAY), load(PORTALS), load(UNLOCK))
        text = dump(doc)
        extra = []
        if os.path.exists(INDEX):
            errs, n = cross_check_index(INDEX, doc)
            extra += errs
            print('map/index.json cross-check: %d layers, %d problems' % (n, len(errs)))
        if args.dbrow:
            errs, n = cross_check_dbrow(args.dbrow, doc)
            extra += errs
            print('dbrow cross-check: %d rows, %d problems' % (n, len(errs)))
        if args.varbits:
            errs, n = cross_check_varbits(args.varbits, doc)
            extra += errs
            print('varbit cross-check: %d FAIRYRINGS_LOG_* ids, %d problems' % (n, len(errs)))
        if extra:
            raise BuildError('\n'.join(extra))
    except BuildError as e:
        print('build_rings: FAILED\n' + str(e), file=sys.stderr)
        return 1
    print(summary(doc, warnings))
    data = text.encode('utf-8')
    if args.check:
        current = open(args.out, 'rb').read() if os.path.exists(args.out) else b''
        if current != data:
            print('build_rings: %s is stale; run tools/build_rings.py' % args.out, file=sys.stderr)
            return 1
        print('rings.json is up to date (%d bytes)' % len(data))
        return 0
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, 'wb') as f:
        f.write(data)
    print('wrote %s (%d bytes)' % (os.path.relpath(args.out, ROOT), len(data)))
    return 0


if __name__ == '__main__':
    sys.exit(main())
