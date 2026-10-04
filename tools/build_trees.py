#!/usr/bin/env python3
"""Build src/main/resources/com/spirittreeatlas/trees.json (DESIGN.md section 3.1).

Inputs (all under tools/data/):
  spirit_trees.verified.json  research dataset: menu labels, previous values, layers, tree
                              centres, arrival tiles, house portals (the source of truth for
                              everything that is not display text)
  tree_display.json           curated player-facing text: names, map labels, areas, locked
                              hints, requirement lines, POIs, notes, dangers, and the surface
                              portal of the Prifddinas layer

It also reads src/main/resources/com/spirittreeatlas/map/index.json (written by tools/mapgen)
for the layer bounds. The output is deterministic: same inputs, same bytes. The script fails
loudly (exit 1) on any contract violation. Standard library only; Python 3.8+.

Usage:
  python tools/build_trees.py            validate and write trees.json
  python tools/build_trees.py --check    validate only; exit 1 if trees.json is stale
"""

import argparse
import json
import os
import re
import sys

TOOLS = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(TOOLS)
DATA = os.path.join(TOOLS, 'data')
DATASET = os.path.join(DATA, 'spirit_trees.verified.json')
DISPLAY = os.path.join(DATA, 'tree_display.json')
OUT = os.path.join(ROOT, 'src', 'main', 'resources', 'com', 'spirittreeatlas', 'trees.json')
INDEX = os.path.join(ROOT, 'src', 'main', 'resources', 'com', 'spirittreeatlas', 'map', 'index.json')

TITLE = 'Spirit Tree Locations'
LAYERS = ('surface', 'prifddinas', 'poh')
KINDS = ('fixed', 'patch', 'quest', 'house')
# The dataset calls Laguna Aurorae an 'island'; to the plugin it is a quest tree (DESIGN 2.5).
KIND_MAP = {'island': 'quest'}
HOUSE_ID = 'YOUR_HOUSE'
HOUSE_PREVIOUS = 12
HOUSE_VALUES = {1, 2, 3, 4, 5, 6, 8, 9, 13}   # cache enum 252 (VarbitID.POH_HOUSE_LOCATION)
EXPECTED_TREES = 14
REQ_PREFIXES = ('Quest: ', 'Skill: ', 'Unlock: ', 'Item: ', 'Diary: ')
LABEL_MAX = 24
LOCKED_HINT_MAX = 32
NAME_MAX = 24
AREA_MAX = 100
LINE_MAX = 140
POI_RANGE = (3, 6)
NOTES_MAX = 3
PORTAL_LABEL_SLACK = 2          # tiles: a portal sits on its layer's place label on the surface
ID_RE = re.compile(r'^[A-Z][A-Z0-9_]*$')

TREE_KEYS = ('id', 'menuLabel', 'match', 'previousValue', 'name', 'label', 'area', 'kind', 'layer',
             'x', 'y', 'plane', 'arrival', 'lockedHint', 'requirements', 'poi', 'notes', 'danger')


class BuildError(Exception):
    pass


def load(path):
    with open(path, encoding='utf-8') as f:
        return json.load(f)


def check_text(errors, where, value, limit):
    if not isinstance(value, str) or not value.strip():
        errors.append('%s: empty text' % where)
        return
    if value != value.strip() or '\n' in value or '  ' in value:
        errors.append('%s: stray whitespace or line break: %r' % (where, value))
    if len(value) > limit:
        errors.append('%s: %d chars > %d: %r' % (where, len(value), limit, value))
    # The plugin draws with RuneScape bitmap fonts; keep to printable ASCII so every glyph exists.
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


def norm(s):
    return ' '.join(s.split()).lower()


def inside(b, x, y):
    return b[0] <= x < b[2] and b[1] <= y < b[3]


def requirement_key(line):
    """What a dataset requirement line must keep in the display: the quest name, or the skill level."""
    m = re.match(r'^Quest: ([^(]+?)\s*(\(|$)', line)
    if m:
        return m.group(1)
    m = re.match(r'^Skill: (\d+ [A-Z][a-z]+)', line)
    if m:
        return m.group(1)
    return None


def build(dataset, display, index):
    errors, warnings = [], []
    shown = display['trees']
    dests = dataset['destinations']
    bounds = {l['id']: l['bounds'] for l in index.get('layers', [])}
    for layer in ('surface', 'prifddinas'):
        if layer not in bounds:
            errors.append('index.json: no %s layer' % layer)

    # ---- the dataset itself ---------------------------------------------------------
    ids = [d['id'] for d in dests]
    if len(ids) != EXPECTED_TREES:
        errors.append('expected %d destinations, found %d' % (EXPECTED_TREES, len(ids)))
    if len(set(ids)) != len(ids):
        errors.append('duplicate ids: %s' % sorted({i for i in ids if ids.count(i) > 1}))
    if [d['menuRow'] for d in dests] != list(range(len(dests))):
        errors.append('destinations are not in menu order: %s' % [d['menuRow'] for d in dests])
    if dataset['menu']['title'] != TITLE:
        errors.append('menu title %r, expected %r' % (dataset['menu']['title'], TITLE))
    missing = sorted(set(ids) - set(shown))
    extra = sorted(set(shown) - set(ids))
    if missing:
        errors.append('tree_display.json lacks: %s' % missing)
    if extra:
        errors.append('tree_display.json has unknown ids: %s' % extra)
    if errors:
        raise BuildError('\n'.join(errors))

    check_list(errors, 'global.requirements', display['global']['requirements'])
    if not any(r.startswith('Quest: Tree Gnome Village') for r in display['global']['requirements']):
        errors.append('global.requirements must name Tree Gnome Village')

    # ---- assemble trees, in menu order -------------------------------------------------
    trees = []
    for d in dests:
        tid = d['id']
        s = shown[tid]
        where = tid
        kind = KIND_MAP.get(d['kind'], d['kind'])
        t = {
            'id': tid,
            'menuLabel': d['menuLabel'],
            'match': d['menuLabelMatch'],
            'previousValue': d['previousValue'],
            'name': s['name'],
            'label': s['label'],
            'area': s['area'],
            'kind': kind,
            'layer': d['layer'],
        }
        if d['layer'] != 'poh':
            # the tree centre in tile-index units (DESIGN 2.5): the painter draws it at +0.5
            t['x'], t['y'], t['plane'] = d['tree']['center']
            t['arrival'] = list(d['arrival']['tile'])
        t['lockedHint'] = s['lockedHint']
        for key in ('requirements', 'poi', 'notes', 'danger'):
            t[key] = list(s[key])
        trees.append(t)

        if not ID_RE.match(tid):
            errors.append('%s: id is not UPPER_SNAKE' % where)
        if kind not in KINDS:
            errors.append('%s: kind %r not in %s' % (where, kind, KINDS))
        if t['layer'] not in LAYERS:
            errors.append('%s: layer %r not in %s' % (where, t['layer'], LAYERS))
        if (kind == 'house') != (tid == HOUSE_ID) or (t['layer'] == 'poh') != (tid == HOUSE_ID):
            errors.append('%s: only %s is a house on the poh layer' % (where, HOUSE_ID))
        if t['match'] not in ('exact', 'prefix') or (t['match'] == 'prefix') != (kind == 'house'):
            errors.append('%s: match %r (prefix only for the house)' % (where, t['match']))
        if not isinstance(t['previousValue'], int) or not 1 <= t['previousValue'] <= 14:
            errors.append('%s: previousValue %r not in 1-14' % (where, t['previousValue']))
        if tid == HOUSE_ID and t['previousValue'] != HOUSE_PREVIOUS:
            errors.append('%s: previousValue %r, expected %d' % (where, t['previousValue'], HOUSE_PREVIOUS))
        if s.get('sources') in (None, []):
            errors.append('%s: no sources in tree_display.json' % where)

        # text
        check_text(errors, where + '.menuLabel', t['menuLabel'], LABEL_MAX)
        check_text(errors, where + '.name', t['name'], NAME_MAX)
        check_text(errors, where + '.label', t['label'], LABEL_MAX)
        check_text(errors, where + '.area', t['area'], AREA_MAX)
        check_text(errors, where + '.lockedHint', t['lockedHint'], LOCKED_HINT_MAX)
        for key in ('requirements', 'poi', 'notes', 'danger'):
            check_list(errors, '%s.%s' % (where, key), t[key])
        for r in t['requirements']:
            if not r.startswith(REQ_PREFIXES) or len(r) <= len('Item: x'):
                errors.append('%s.requirements: %r must start with one of %s' % (where, r, REQ_PREFIXES))
        if not POI_RANGE[0] <= len(t['poi']) <= POI_RANGE[1]:
            errors.append('%s.poi: %d items, expected %d-%d' % (where, len(t['poi']), POI_RANGE[0], POI_RANGE[1]))
        if len(t['notes']) > NOTES_MAX:
            errors.append('%s.notes: %d items > %d' % (where, len(t['notes']), NOTES_MAX))

        # the display must keep every quest and skill level the dataset requires
        joined = ' | '.join(t['requirements'])
        for r in d.get('requirements', []):
            key = requirement_key(r)
            if key and key not in joined:
                errors.append('%s: dataset requirement %r is not in the display requirements %r' % (where, key, t['requirements']))
        if d['kind'] in ('patch', 'quest', 'island', 'house') and not t['requirements']:
            errors.append('%s: a %s tree needs requirement lines' % (where, d['kind']))

        # place
        if t['layer'] != 'poh':
            b = bounds.get(t['layer'])
            if t['plane'] != 0:
                errors.append('%s: plane %r (every tree is on plane 0)' % (where, t['plane']))
            if b is not None and not inside(b, t['x'], t['y']):
                errors.append('%s: (%s,%s) outside the %s layer bounds %s' % (where, t['x'], t['y'], t['layer'], b))
            if max(abs(t['arrival'][0] - t['x']), abs(t['arrival'][1] - t['y'])) > 4:
                warnings.append('%s: arrival %s is more than 4 tiles from the centre' % (where, t['arrival']))

    for key in ('id', 'menuLabel', 'label', 'name'):
        values = [norm(t[key]) for t in trees]
        if len(set(values)) != len(values):
            errors.append('duplicate %s: %s' % (key, sorted({v for v in values if values.count(v) > 1})))
    prev = [t['previousValue'] for t in trees]
    if sorted(prev) != list(range(1, EXPECTED_TREES + 1)):
        errors.append('previousValue must be 1-%d, each once: %s' % (EXPECTED_TREES, prev))

    # ---- house portals ------------------------------------------------------------------
    house_portals = []
    for p in sorted(dataset['housePortals'], key=lambda p: p['value']):
        where = 'housePortals[%s]' % p['value']
        hp = {'value': p['value'], 'town': p['town'], 'x': p['center'][0], 'y': p['center'][1],
              'plane': p['center'][2], 'layer': p['layer']}
        house_portals.append(hp)
        check_text(errors, where + '.town', hp['town'], LABEL_MAX)
        if hp['layer'] not in ('surface', 'prifddinas'):
            errors.append('%s: layer %r' % (where, hp['layer']))
        b = bounds.get(hp['layer'])
        if b is not None and not inside(b, hp['x'], hp['y']):
            errors.append('%s: (%s,%s) outside the %s layer bounds %s' % (where, hp['x'], hp['y'], hp['layer'], b))
        if p.get('valueConfidence') != 'high':
            errors.append('%s: value confidence %r' % (where, p.get('valueConfidence')))
        if len('Your house (%s)' % hp['town']) > LABEL_MAX:
            warnings.append('%s: "Your house (%s)" is %d characters, over the %d of a label'
                            % (where, hp['town'], len('Your house (%s)' % hp['town']), LABEL_MAX))
    values = [hp['value'] for hp in house_portals]
    if set(values) != HOUSE_VALUES or len(values) != len(HOUSE_VALUES):
        errors.append('house portal values %s, expected %s' % (values, sorted(HOUSE_VALUES)))

    # ---- portals: where an off-surface layer is entered on the surface ----------------------
    portals = []
    sb = bounds.get('surface')
    used = {t['layer'] for t in trees} | {hp['layer'] for hp in house_portals}
    for p in display['portals']:
        where = 'portal %s' % p.get('layer')
        if p['layer'] in ('surface', 'poh') or p['layer'] not in LAYERS:
            errors.append('%s: not an off-surface map layer' % where)
        if p['layer'] not in used:
            errors.append('%s: nothing on that layer' % where)
        if not isinstance(p.get('x'), int) or not isinstance(p.get('y'), int):
            errors.append('%s: x and y must be integer tiles' % where)
        elif sb is not None and not inside(sb, p['x'], p['y']):
            errors.append('%s: (%d,%d) outside the surface bounds %s' % (where, p['x'], p['y'], sb))
        check_text(errors, where + '.label', p['label'], LABEL_MAX)
        check_text(errors, where + '.description', p['description'], LINE_MAX)
        if not p.get('source'):
            errors.append('%s: no source' % where)
        # it must sit on the world map's own surface label for the place
        near = [l for l in index.get('labels', []) if l.get('layer') == 'surface' and l['t'] == p['label']
                and abs(l['x'] - p['x']) <= PORTAL_LABEL_SLACK and abs(l['y'] - p['y']) <= PORTAL_LABEL_SLACK]
        if not near:
            errors.append('%s: no surface label %r within %d tiles of (%s,%s)'
                          % (where, p['label'], PORTAL_LABEL_SLACK, p.get('x'), p.get('y')))
        portals.append({'layer': p['layer'], 'x': p['x'], 'y': p['y'], 'label': p['label'],
                        'description': p['description']})
    off_surface = sorted(used - {'surface', 'poh'})
    if sorted(p['layer'] for p in portals) != off_surface:
        errors.append('portals %s, expected exactly one for each of %s' % ([p['layer'] for p in portals], off_surface))
    portals.sort(key=lambda p: p['layer'])

    if errors:
        raise BuildError('\n'.join(errors))

    doc = {
        '_about': 'Generated by tools/build_trees.py from tools/data/spirit_trees.verified.json and '
                  'tools/data/tree_display.json. Do not edit by hand.',
        'title': TITLE,
        'unavailableColour': dataset['menu']['unavailableColour'],
        'global': {'requirements': list(display['global']['requirements'])},
        'trees': trees,
        'housePortals': house_portals,
        'portals': portals,
    }
    return doc, warnings


def dump(doc):
    """Indented JSON in a fixed key order (trees in menu order); short number lists stay on one line."""
    for t in doc['trees']:
        unknown = set(t) - set(TREE_KEYS)
        if unknown:
            raise BuildError('dump: unexpected keys %s' % sorted(unknown))
    text = json.dumps(doc, indent=1, ensure_ascii=True)
    text = re.sub(r'\[\s+(-?[\d.]+),\s+(-?[\d.]+),\s+(-?[\d.]+)\s+\]', r'[\1, \2, \3]', text)
    text += '\n'
    if json.loads(text) != doc:
        raise BuildError('dump: round trip mismatch')
    return text


def summary(doc, warnings):
    trees = doc['trees']
    by_layer = {}
    for t in trees:
        by_layer.setdefault(t['layer'], []).append(t['id'])
    poi = [len(t['poi']) for t in trees]
    lines = [
        'trees: %d (%s)' % (len(trees), ', '.join('%s=%d' % (k, sum(t['kind'] == k for t in trees)) for k in KINDS)),
        'layers: ' + ', '.join('%s=%d' % (k, len(v)) for k, v in sorted(by_layer.items())),
        'with requirements: %d; with danger: %d; with notes: %d' % (
            sum(bool(t['requirements']) for t in trees), sum(bool(t['danger']) for t in trees),
            sum(bool(t['notes']) for t in trees)),
        'POIs per tree: min %d, max %d, total %d' % (min(poi), max(poi), sum(poi)),
        'house portals: ' + ', '.join('%d %s' % (p['value'], p['town']) for p in doc['housePortals']),
        'portals: ' + ', '.join('%s (%d,%d)' % (p['layer'], p['x'], p['y']) for p in doc['portals']),
    ]
    for w in warnings:
        lines.append('warning: ' + w)
    return '\n'.join(lines)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('--check', action='store_true', help='validate only; fail if trees.json is stale')
    ap.add_argument('--out', default=OUT, help='output path (default: the plugin resource)')
    args = ap.parse_args(argv)
    try:
        if not os.path.exists(INDEX):
            raise BuildError('%s is missing; run tools/mapgen first' % INDEX)
        doc, warnings = build(load(DATASET), load(DISPLAY), load(INDEX))
        text = dump(doc)
    except BuildError as e:
        print('build_trees: FAILED\n' + str(e), file=sys.stderr)
        return 1
    print(summary(doc, warnings))
    data = text.encode('utf-8')
    if args.check:
        current = open(args.out, 'rb').read() if os.path.exists(args.out) else b''
        # a Windows checkout with core.autocrlf may hold CRLF line ends; the content is what counts
        if current.replace(b'\r\n', b'\n') != data:
            print('build_trees: %s is stale; run tools/build_trees.py' % args.out, file=sys.stderr)
            return 1
        print('trees.json is up to date (%d bytes)' % len(data))
        return 0
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, 'wb') as f:
        f.write(data)
    print('wrote %s (%d bytes)' % (os.path.relpath(args.out, ROOT), len(data)))
    return 0


if __name__ == '__main__':
    sys.exit(main())
