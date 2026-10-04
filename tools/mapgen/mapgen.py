#!/usr/bin/env python3
"""Fairy Ring Atlas map pipeline, step 0 and step 2.

    python tools/mapgen/mapgen.py plan     # writes tools/mapgen/regions.json
    ./gradlew -p tools/mapgen render       # step 1 (Java): renders build/raw/2/*.png + labels/icons
    python tools/mapgen/mapgen.py build    # filters, builds z=-1, quantises, writes resources + index.json

`build` writes into src/main/resources/com/fairyringatlas/map/ (wiping the old
tiles first) and prints the budget. Requires Pillow and numpy.
See README.md next to this file.
"""
import argparse
import io
import itertools
import json
import math
import os
import shutil
import sys
from collections import Counter
from concurrent.futures import ProcessPoolExecutor

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.normpath(os.path.join(HERE, "..", ".."))
RINGS_SRC = os.path.join(REPO, "tools", "data", "fairy_rings.verified.json")
RINGS_JSON = os.path.join(REPO, "src", "main", "resources", "com", "fairyringatlas", "rings.json")
OUT = os.path.join(REPO, "src", "main", "resources", "com", "fairyringatlas", "map")
RAW = os.path.join(HERE, "build", "raw")
REGIONS_JSON = os.path.join(HERE, "regions.json")

CACHE_PROVENANCE = "openrs2 2727 (2026-09-30, build 241)"
TILE = 256
REGION = 64
BUDGET = int(7.6 * 1024 * 1024)  # all of src/main/resources; keeps the jar under the hub's 8 MiB warning
PSNR_MIN = float(os.environ.get("MAPGEN_PSNR", "40.0"))  # below this a PNG8 candidate counts as "visibly degraded"
# The whole surface. The world map's own "Gielinor Surface" (worldmap details file 0) shows
# exactly the 64x64 map squares rx 15-62, ry 32-65 (1632 squares, all at their own position;
# MapGen exports them to build/raw/worldmaps.json): SURFACE_RENDER. Every one of them has map
# data, but the outermost 56 tiles on every side are void (MapImageDumper paints them
# SURFACE_VOID), so the layer bounds are the content inside that frame: SURFACE_BOUNDS.
# cmd_build checks both against the cache and the renders.
SURFACE_RENDER = [960, 2048, 4032, 4224]
SURFACE_BOUNDS = [1016, 2104, 3976, 4168]
SURFACE_VOID = (0x20, 0x2f, 0x3d)

# Off-surface layers (DESIGN 3.3). Bounds are world tiles, [x0, y0, x1, y1),
# region-aligned so one z=2 tile is exactly one region.
def around(x, y, r=1):
    rx, ry = x // REGION, y // REGION
    return [(rx - r) * REGION, (ry - r) * REGION, (rx + r + 1) * REGION, (ry + r + 1) * REGION]

#   "bounds": region-aligned area rendered (DESIGN 3.3 rough bounds).
#   "keep":   optional subset of regions [rx, ry] that belong to the destination.
#             The DESIGN bounds come from wiki basemaps and also pull in unrelated
#             neighbouring areas packed into the same part of the cache (checked by
#             eye on a contact sheet of every layer); those regions are not shipped.
# The index bounds of an off-surface layer are the bounding box of its non-empty
# pixels in the kept regions, plus MARGIN tiles, clamped to the kept regions.
# Pixels outside the index bounds are painted with the layer background so no
# unrelated content leaks in at the edges.
MARGIN = 8
TRANSPORT_SPRITE = 1504  # SpriteID.Mapfunction.TRANSPORTATION, the fairy ring map icon

LAYERS = [
    # surface bounds are SURFACE_BOUNDS, set in plan_layers()
    {"id": "surface", "name": "Gielinor", "bounds": None},
    {"id": "zanaris", "name": "Zanaris", "bounds": [2304, 4288, 2560, 4544]},
    # Abyssal Nexus (DIP) + the Abyss/Abyssal Area (ALR); the outer ring of the
    # 5x5 holds unrelated buildings and a surface-like village.
    {"id": "abyss", "name": "The Abyss", "bounds": [2880, 4672, 3200, 4992],
     "keep": [[rx, ry] for ry in range(74, 77) for rx in range(46, 49)]},
    # (43,80) holds two unrelated huts
    {"id": "dorgesh_south", "name": "Dorgesh-Kaan South Dungeon", "bounds": [2624, 5120, 2816, 5312],
     "keep": [[42, 81], [42, 82]]},
    {"id": "fisher_realm", "name": "Fisher Realm", "bounds": [2560, 4608, 2752, 4800]},
    {"id": "enchanted_valley", "name": "Enchanted Valley", "bounds": [2944, 4416, 3136, 4608],
     "keep": [[47, 70]]},
    # ring (2437,5126) +- ~1.5 regions inside map 23's [2304,4928]-[2624,5248];
    # row 81 is empty and row 78 holds unrelated areas, so the city is 3x2.
    {"id": "mor_ul_rek", "name": "Mor Ul Rek", "bounds": [2368, 5056, 2560, 5248],
     "keep": [[rx, ry] for ry in range(79, 81) for rx in range(37, 40)]},
    # (31,75) boat area, (33,75) pillars, (32,76)/(33,76) buildings are unrelated
    # its west edge picks up a ground-blend band from (31,75), so clip it by hand
    {"id": "cosmic_plane", "name": "Cosmic entity's plane", "bounds": [1984, 4736, 2176, 4928],
     "keep": [[32, 75]], "clip": [2056, 4800, 2112, 4864]},
    # (48,82) holds unrelated caves
    {"id": "gorak_plane", "name": "Gorak Plane", "bounds": [2944, 5248, 3136, 5440],
     "keep": [[47, 83]]},
    {"id": "yubiusk", "name": "Yu'biusk", "bounds": around(3572, 4372)},
    {"id": "grimstone", "name": "Grimstone Dungeon", "bounds": around(2926, 10455),
     "keep": [[44, 163], [45, 163]]},
    {"id": "hollows", "name": "Myreque Hideout (The Hollows)", "bounds": [3328, 9728, 3584, 9984]},
]

# Extra regions rendered only to explore/verify (not shipped unless inside a layer).
EXTRA_RENDER = [
    [2304, 4928, 2624, 5248],  # all of map 23 (Mor Ul Rek) to check the crop
]

# Which layer each non-surface ring belongs to (by code or by dataset name).
RING_LAYER = {
    "AJQ": "dorgesh_south", "ALR": "abyss", "DIP": "abyss", "BJR": "fisher_realm",
    "BKQ": "enchanted_valley", "BKS": "zanaris", "BLP": "mor_ul_rek", "BLQ": "yubiusk",
    "CKP": "cosmic_plane", "DIR": "gorak_plane", "DLP": "grimstone", "DLS": "hollows",
    "DIQ": "poh",
}


def load_rings():
    with open(RINGS_SRC, encoding="utf-8") as f:
        rings = json.load(f)
    out = []
    for r in rings:
        if r.get("x") is None:
            continue
        code = r.get("code")
        if r.get("surface"):
            layer = "surface"
        elif code in RING_LAYER:
            layer = RING_LAYER[code]
        else:
            layer = "zanaris"  # hideout sequence landing and the Zanaris exit rings
        out.append({"code": code, "name": r["name"], "x": r["x"], "y": r["y"], "layer": layer, "kind": r["kind"]})
    return out


def plan_layers(rings):
    layers = json.loads(json.dumps(LAYERS))
    layers[0]["bounds"] = list(SURFACE_BOUNDS)
    layers[0]["renderBounds"] = list(SURFACE_RENDER)
    return layers


def check_surface_extent():
    """The world map's surface squares (exported by MapGen) must be exactly SURFACE_BOUNDS."""
    p = os.path.join(RAW, "worldmaps.json")
    if not os.path.exists(p):
        print("WARNING: %s missing; cannot check the surface extent" % p)
        return
    with open(p, encoding="utf-8") as f:
        maps = json.load(f)
    surf = [m for m in maps if m.get("surface")]
    sq = {(s[2], s[3]) for m in surf for s in m.get("squares", [])}
    want = set(regions_in(SURFACE_RENDER))
    if sq != want:
        print("WARNING: world map surface squares differ from SURFACE_BOUNDS: %d extra, %d missing"
              % (len(sq - want), len(want - sq)))
    else:
        print("Surface extent: the world map's %d surface squares are exactly SURFACE_RENDER %s" % (len(sq), SURFACE_RENDER))


def surface_content(z2):
    """Bounding box of the non-void pixels of the rendered surface, in world tiles."""
    x0 = y0 = 1 << 30
    x1 = y1 = -1
    for (rx, ry) in regions_in(SURFACE_RENDER):
        a = z2.get((rx, ry))
        if a is None:
            continue
        ys, xs = np.nonzero(np.any(a != np.array(SURFACE_VOID, dtype=np.uint8), axis=2))
        if len(xs) == 0:
            continue
        x0 = min(x0, rx * REGION + xs.min() // 4)
        x1 = max(x1, rx * REGION + xs.max() // 4 + 1)
        y0 = min(y0, ry * REGION + 63 - ys.max() // 4)
        y1 = max(y1, ry * REGION + 63 - ys.min() // 4 + 1)
    return [int(v) for v in (x0, y0, x1, y1)]


def paint_outside(z2, regs, b, colour):
    """Paints the pixels of the given z=2 renders that lie outside bounds b with colour."""
    for (rx, ry) in regs:
        a = z2[(rx, ry)]
        wx = rx * REGION + np.arange(256) // 4                 # per column
        wy = ry * REGION + 63 - np.arange(256) // 4            # per row
        outside = (~((wx >= b[0]) & (wx < b[2])))[None, :] | (~((wy >= b[1]) & (wy < b[3])))[:, None]
        a[outside] = colour


def regions_in(b):
    """The regions overlapping bounds b (which need not be region-aligned)."""
    return [(rx, ry) for ry in range(b[1] // REGION, -(-b[3] // REGION)) for rx in range(b[0] // REGION, -(-b[2] // REGION))]


def layer_regions(l):
    if l.get("keep"):
        return [tuple(rc) for rc in l["keep"]]
    return regions_in(l["bounds"] if "renderBounds" not in l else l["renderBounds"])


def check_rings_in_layers(rings, layers):
    by_id = {l["id"]: l for l in layers}
    bad = []
    for r in rings:
        b = by_id[r["layer"]]["bounds"]
        if not (b[0] <= r["x"] < b[2] and b[1] <= r["y"] < b[3]):
            bad.append(r)
    for r in bad:
        print("RING OUTSIDE LAYER BOUNDS:", r)
    print("Ring/layer bounds check: %d rings, %d outside" % (len(rings), len(bad)))
    return not bad


def cmd_plan(_):
    rings = load_rings()
    layers = plan_layers(rings)
    ok = check_rings_in_layers(rings, layers)
    want = []
    seen = set()
    for b in [l.get("renderBounds", l["bounds"]) for l in layers] + EXTRA_RENDER:
        for rc in regions_in(b):
            if rc not in seen:
                seen.add(rc)
                want.append(list(rc))
    with open(REGIONS_JSON, "w") as f:
        json.dump({"layers": layers, "regions": want}, f, separators=(",", ":"))
    for l in layers:
        b = l["bounds"]
        print("  %-17s %s  %dx%d regions" % (l["id"], b, (b[2] - b[0]) // REGION, (b[3] - b[1]) // REGION))
    print("Wrote %s: %d regions" % (REGIONS_JSON, len(want)))
    if not ok:
        sys.exit(1)


# ---------------------------------------------------------------- compression

def psnr(a, b):
    mse = np.mean((a.astype(np.float64) - b.astype(np.float64)) ** 2)
    return 99.0 if mse == 0 else 10 * math.log10(255.0 ** 2 / mse)


def png_bytes(img, **kw):
    buf = io.BytesIO()
    img.save(buf, "PNG", optimize=True, **kw)
    return buf.getvalue()


def encode_tile(rgb_bytes):
    """Return (png bytes, kind, psnr) for the smallest acceptable encoding."""
    img = Image.open(io.BytesIO(rgb_bytes)).convert("RGB")
    ref = np.asarray(img)
    cands = []
    data24 = png_bytes(img)
    cands.append((len(data24), data24, "png24", 99.0))
    ncol = len(img.getcolors(1 << 16) or [None] * 100000)
    if ncol <= 256:
        # lossless palette
        p = img.quantize(colors=ncol, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE)
        q = np.asarray(p.convert("RGB"))
        if np.array_equal(q, ref):
            d = png_bytes(p)
            cands.append((len(d), d, "png8", 99.0))
        else:
            # median cut can merge colours; build an exact palette instead
            pal = {}
            flat = ref.reshape(-1, 3)
            keys = (flat[:, 0].astype(np.int32) << 16) | (flat[:, 1].astype(np.int32) << 8) | flat[:, 2]
            uniq, inv = np.unique(keys, return_inverse=True)
            pimg = Image.fromarray(inv.reshape(ref.shape[:2]).astype(np.uint8), "P")
            palette = []
            for k in uniq:
                palette += [(k >> 16) & 255, (k >> 8) & 255, k & 255]
            pimg.putpalette(palette)
            d = png_bytes(pimg)
            cands.append((len(d), d, "png8", 99.0))
    else:
        # kmeans refines the palette: about +3 dB PSNR at the same size
        for method, dither, colors, kmeans in itertools.product(
                (Image.Quantize.MEDIANCUT, Image.Quantize.FASTOCTREE),
                (Image.Dither.NONE, Image.Dither.FLOYDSTEINBERG),
                (256, 192, 128),
                (0, 2)):
            try:
                p = img.quantize(colors=colors, method=method, dither=dither, kmeans=kmeans)
            except Exception:
                continue
            s = psnr(np.asarray(p.convert("RGB")), ref)
            if s < PSNR_MIN:
                continue
            d = png_bytes(p)
            cands.append((len(d), d, "png8", s))
    cands.sort(key=lambda c: c[0])
    n, d, kind, s = cands[0]
    return d, kind, s


def _encode_job(args):
    key, rgb_bytes = args
    d, kind, s = encode_tile(rgb_bytes)
    return key, d, kind, s


def to_png_bytes(arr):
    buf = io.BytesIO()
    Image.fromarray(arr, "RGB").save(buf, "PNG", compress_level=1)
    return buf.getvalue()


# ---------------------------------------------------------------- build

def hexcol(c):
    return "#%02x%02x%02x" % tuple(int(v) for v in c)


def load_raw(rx, ry):
    p = os.path.join(RAW, "2", "%d_%d.png" % (rx, ry))
    if not os.path.exists(p):
        return None
    return np.asarray(Image.open(p).convert("RGB"))


def solid_colour(arr):
    first = arr[0, 0]
    if np.all(arr == first):
        return tuple(int(v) for v in first)
    return None


def cmd_build(args):
    with open(REGIONS_JSON) as f:
        plan = json.load(f)
    layers = plan_layers(load_rings())  # from code, not regions.json, so edits apply without re-rendering
    rings = load_rings()
    check_surface_extent()

    # ---- load z=2 raw tiles per layer
    z2 = {}           # (rx,ry) -> array
    owner = {}        # (rx,ry) -> layer id
    for l in layers:
        for rc in layer_regions(l):
            if rc in owner:
                print("WARNING: region %s in two layers (%s, %s)" % (rc, owner[rc], l["id"]))
                continue
            owner[rc] = l["id"]
            a = load_raw(*rc)
            if a is not None:
                z2[rc] = a.copy()

    # ---- backgrounds
    bg = {}
    for l in layers:
        solids = [solid_colour(z2[rc]) for rc in layer_regions(l) if rc in z2]
        solids = [s for s in solids if s is not None]
        if l["id"] == "surface":
            # the open-sea colour MapImageDumper paints (most common solid tile colour)
            bg[l["id"]] = Counter(solids).most_common(1)[0][0] if solids else (0, 0, 0)
        else:
            bg[l["id"]] = (0, 0, 0)  # TYPE_INT_RGB default: empty map squares render black
        l["background"] = hexcol(bg[l["id"]])

    # ---- index bounds of off-surface layers: content bbox + margin; mask the rest
    for l in layers:
        if l["id"] == "surface":
            continue
        regs = [rc for rc in layer_regions(l) if rc in z2]
        ext = [min(r[0] for r in layer_regions(l)) * REGION, min(r[1] for r in layer_regions(l)) * REGION,
               (max(r[0] for r in layer_regions(l)) + 1) * REGION, (max(r[1] for r in layer_regions(l)) + 1) * REGION]
        x0 = y0 = 1 << 30
        x1 = y1 = -1
        for (rx, ry) in regs:
            m = np.any(z2[(rx, ry)] != np.array(bg[l["id"]], dtype=np.uint8), axis=2)
            ys, xs = np.nonzero(m)
            if len(xs) == 0:
                continue
            x0 = min(x0, rx * REGION + xs.min() // 4)
            x1 = max(x1, rx * REGION + xs.max() // 4 + 1)
            y0 = min(y0, ry * REGION + 63 - ys.max() // 4)
            y1 = max(y1, ry * REGION + 63 - ys.min() // 4 + 1)
        b = [int(v) for v in (max(ext[0], x0 - MARGIN), max(ext[1], y0 - MARGIN), min(ext[2], x1 + MARGIN), min(ext[3], y1 + MARGIN))]
        if l.get("clip"):
            b = list(l["clip"])
        l["renderBounds"] = l["bounds"]
        l["bounds"] = b
        paint_outside(z2, regs, b, bg[l["id"]])
    # the surface: the void frame around the world map, outside its bounds, becomes open sea
    surface = layers[0]
    content = surface_content(z2)
    if content != surface["bounds"]:
        print("WARNING: surface content %s differs from SURFACE_BOUNDS %s" % (content, surface["bounds"]))
    paint_outside(z2, [rc for rc in layer_regions(surface) if rc in z2], surface["bounds"], bg["surface"])
    for l in layers:
        print("  %-17s bounds %-28s background %s" % (l["id"], l["bounds"], l["background"]))
    if not check_rings_in_layers(rings, layers):
        sys.exit(1)

    # ---- optional surface tightening: drop regions far from land
    surface_drop = set()
    if args.tighten:
        surface_drop = tighten_surface(layers[0], z2, bg["surface"], rings)

    # ---- decide z=2 tiles
    ship2 = {}
    solid2 = {}
    for rc, a in sorted(z2.items()):
        lid = owner[rc]
        if rc in surface_drop:
            continue
        s = solid_colour(a)
        if s is not None:
            if s != bg[lid]:
                solid2["%d_%d" % rc] = hexcol(s)
            continue
        ship2[rc] = a

    # ---- z=-1 (0.5 ppt): 8x8 regions per tile, area average of z=2
    zm1 = {}
    solidm1 = {}
    blocks = set()
    for l in layers:
        b = l["bounds"]
        for ty in range(b[1] // 512, (b[3] - 1) // 512 + 1):
            for tx in range(b[0] // 512, (b[2] - 1) // 512 + 1):
                blocks.add((tx, ty))
    for (tx, ty) in sorted(blocks):
        canvas = np.zeros((2048, 2048, 3), dtype=np.uint8)
        # fill colour: background of the first layer whose bounds touch this block
        fill = None
        for l in layers:
            b = l["bounds"]
            if b[0] < (tx + 1) * 512 and b[2] > tx * 512 and b[1] < (ty + 1) * 512 and b[3] > ty * 512:
                fill = bg[l["id"]]
                break
        canvas[:, :] = fill
        any_tile = False
        for j in range(8):
            for i in range(8):
                rc = (tx * 8 + i, ty * 8 + j)
                a = None
                if rc in z2 and rc not in surface_drop:
                    a = z2[rc]
                elif rc in owner:
                    a = None
                if a is None:
                    if rc in owner:
                        canvas[(7 - j) * 256:(8 - j) * 256, i * 256:(i + 1) * 256] = bg[owner[rc]]
                    continue
                any_tile = True
                canvas[(7 - j) * 256:(8 - j) * 256, i * 256:(i + 1) * 256] = a
        if not any_tile:
            continue
        small = np.asarray(Image.fromarray(canvas, "RGB").reduce(8))
        s = solid_colour(small)
        if s is not None:
            if s != fill:
                solidm1["%d_%d" % (tx, ty)] = hexcol(s)
            continue
        zm1[(tx, ty)] = small

    # ---- encode in parallel
    if os.path.isdir(OUT):
        for z in ("2", "-1", "1", "0"):
            shutil.rmtree(os.path.join(OUT, z), ignore_errors=True)
    os.makedirs(OUT, exist_ok=True)
    jobs = [(("2",) + rc, to_png_bytes(a)) for rc, a in ship2.items()]
    jobs += [(("-1",) + rc, to_png_bytes(a)) for rc, a in zm1.items()]
    stats = {"2": Counter(), "-1": Counter()}
    sizes = {"2": 0, "-1": 0}
    psnrs = []
    with ProcessPoolExecutor() as ex:
        for key, d, kind, s in ex.map(_encode_job, jobs, chunksize=8):
            z, tx, ty = key
            os.makedirs(os.path.join(OUT, z), exist_ok=True)
            with open(os.path.join(OUT, z, "%d_%d.png" % (tx, ty)), "wb") as f:
                f.write(d)
            stats[z][kind] += 1
            sizes[z] += len(d)
            if kind == "png8" and s < 99:
                psnrs.append(s)

    # ---- labels and icons
    labels, icons = export_labels_icons(layers)

    index = {
        "cache": CACHE_PROVENANCE,
        "tileSize": TILE,
        "levels": [2, -1],
        "tiles": {
            "2": ["%d_%d" % rc for rc in sorted(ship2, key=lambda t: (t[1], t[0]))],
            "-1": ["%d_%d" % rc for rc in sorted(zm1, key=lambda t: (t[1], t[0]))],
        },
        "solid": {"2": solid2, "-1": solidm1},
        # "poh" (DIQ) has no map and therefore no entry here (DESIGN 3.3: card only)
        "layers": [{"id": l["id"], "name": l["name"], "bounds": l["bounds"], "background": l["background"]} for l in layers],
        "labels": labels,
        "icons": icons,
    }
    with open(os.path.join(OUT, "index.json"), "w", encoding="utf-8") as f:
        json.dump(index, f, ensure_ascii=False, separators=(",", ":"))

    report(stats, sizes, psnrs, ship2, zm1, solid2, solidm1, surface_drop, labels, icons)
    previews(layers, bg, z2, zm1, surface_drop)


def tighten_surface(surface, z2, sea, rings):
    """Regions to drop: no land within +-2 regions and further than 2 regions from any ring."""
    land = set()
    for rc in layer_regions(surface):
        a = z2.get(rc)
        if a is not None and solid_colour(a) is None:
            land.add(rc)
    keep = set()
    for (rx, ry) in land:
        for dx in range(-2, 3):
            for dy in range(-2, 3):
                keep.add((rx + dx, ry + dy))
    for r in rings:
        if r["layer"] != "surface":
            continue
        rx, ry = r["x"] // REGION, r["y"] // REGION
        for dx in range(-2, 3):
            for dy in range(-2, 3):
                keep.add((rx + dx, ry + dy))
    drop = {rc for rc in layer_regions(surface) if rc not in keep}
    print("Surface tighten: dropping %d regions" % len(drop))
    return drop


# Labels the cache puts where a player cannot go from the ring. The Fight Cave name sits in the
# arena instance (region 9551); the entrance players walk to is north of BLP.
LABEL_MOVES = {("Fight Cave", "mor_ul_rek"): (2439, 5172)}


def export_labels_icons(layers):
    def layer_of(x, y):
        for l in layers:
            b = l["bounds"]
            if b[0] <= x < b[2] and b[1] <= y < b[3]:
                return l["id"]
        return None

    with open(os.path.join(RAW, "labels.json"), encoding="utf-8") as f:
        raw_labels = json.load(f)
    with open(os.path.join(RAW, "icons.json"), encoding="utf-8") as f:
        raw_icons = json.load(f)
    labels = []
    seen = set()
    for l in raw_labels:
        if l["z"] != 0:
            continue
        lid = layer_of(l["x"], l["y"])
        if lid is None:
            continue
        t = " ".join(part.strip() for part in l["t"].replace("<br>", "\n").split("\n") if part.strip())
        key = (t, l["x"], l["y"])
        if key in seen:
            continue
        seen.add(key)
        o = {"t": t, "x": l["x"], "y": l["y"], "s": max(0, min(2, l["s"])), "layer": lid}
        moved = LABEL_MOVES.get((t, lid))
        if moved:
            o["x"], o["y"] = moved
        if l["c"] != "#ffffff":
            o["c"] = l["c"]  # optional text colour (orange/cyan in-game labels); absent = white
        labels.append(o)
    labels.sort(key=lambda o: (o["layer"], -o["s"], o["t"]))
    # The plugin draws its own ring markers, so drop the game's transportation
    # icon (sprite 1504) sitting on each fairy ring we know about.
    rings = load_rings()
    icons = []
    seen = set()
    dropped = 0
    for i in raw_icons:
        if i["z"] != 0:
            continue
        lid = layer_of(i["x"], i["y"])
        if lid is None:
            continue
        if i["sprite"] == TRANSPORT_SPRITE and any(abs(i["x"] - r["x"]) <= 3 and abs(i["y"] - r["y"]) <= 3 for r in rings):
            dropped += 1
            continue
        key = (i["x"], i["y"], i["sprite"])
        if key in seen:
            continue
        seen.add(key)
        icons.append([i["x"], i["y"], i["sprite"]])
    # compact [x, y, sprite] triples: they are most of index.json, which ships as source text
    icons.sort(key=lambda o: (o[1], o[0]))
    print("Icons: dropped %d fairy-ring transport icons under ring markers" % dropped)
    return labels, icons


def dir_bytes(path):
    total = 0
    count = 0
    for root, _, files in os.walk(path):
        for fn in files:
            total += os.path.getsize(os.path.join(root, fn))
            count += 1
    return total, count


def report(stats, sizes, psnrs, ship2, zm1, solid2, solidm1, surface_drop, labels, icons):
    mapb, mapn = dir_bytes(OUT)
    ringsb = os.path.getsize(RINGS_JSON) if os.path.exists(RINGS_JSON) else 0
    idxb = os.path.getsize(os.path.join(OUT, "index.json"))
    print()
    print("=== budget ===")
    print("z=2 : %4d tiles  %9d bytes  %s  (solid not shipped: %d recorded)" % (len(ship2), sizes["2"], dict(stats["2"]), len(solid2)))
    print("z=-1: %4d tiles  %9d bytes  %s  (solid recorded: %d)" % (len(zm1), sizes["-1"], dict(stats["-1"]), len(solidm1)))
    print("index.json: %d bytes (%d labels, %d icons)" % (idxb, len(labels), len(icons)))
    if psnrs:
        print("lossy PNG8 tiles: %d, PSNR min %.1f dB, median %.1f dB" % (len(psnrs), min(psnrs), sorted(psnrs)[len(psnrs) // 2]))
    if surface_drop:
        print("surface regions dropped by tightening: %d" % len(surface_drop))
    print("map/ total: %d bytes in %d files" % (mapb, mapn))
    print("rings.json: %d bytes%s" % (ringsb, "" if ringsb else " (not present yet; budget assumes 150000)"))
    total = mapb + (ringsb or 150000)
    print("TOTAL: %d bytes = %.3f MiB (budget %.1f MiB) -> %s" % (total, total / 1048576, BUDGET / 1048576, "OK" if total <= BUDGET else "OVER"))
    # sanity: every shipped PNG <= 256x256
    big = 0
    for root, _, files in os.walk(OUT):
        for fn in files:
            if fn.endswith(".png"):
                with Image.open(os.path.join(root, fn)) as im:
                    if im.size[0] > TILE or im.size[1] > TILE:
                        big += 1
    print("PNGs larger than 256x256: %d" % big)


# ---------------------------------------------------------------- previews

PREVIEWS = os.path.join(os.environ.get("MAPGEN_PREVIEWS", os.path.join(HERE, "build", "previews")))


def stitch(level_dir, txs, tys, fill):
    w, h = len(txs) * TILE, len(tys) * TILE
    canvas = Image.new("RGB", (w, h), fill)
    for j, ty in enumerate(reversed(tys)):
        for i, tx in enumerate(txs):
            p = os.path.join(level_dir, "%d_%d.png" % (tx, ty))
            if os.path.exists(p):
                canvas.paste(Image.open(p).convert("RGB"), (i * TILE, j * TILE))
    return canvas


def fit(img, longest=2500):
    s = longest / max(img.size)
    if s < 1:
        img = img.resize((round(img.size[0] * s), round(img.size[1] * s)), Image.LANCZOS)
    return img


def previews(layers, bg, z2, zm1, surface_drop):
    os.makedirs(PREVIEWS, exist_ok=True)
    surf = layers[0]["bounds"]
    txs = list(range(surf[0] // 512, (surf[2] - 1) // 512 + 1))
    tys = list(range(surf[1] // 512, (surf[3] - 1) // 512 + 1))
    img = stitch(os.path.join(OUT, "-1"), txs, tys, bg["surface"])
    # crop to the surface bounds
    x0 = (surf[0] - txs[0] * 512) // 2
    y0 = ((tys[-1] + 1) * 512 - surf[3]) // 2
    img = img.crop((x0, y0, x0 + (surf[2] - surf[0]) // 2, y0 + (surf[3] - surf[1]) // 2))
    fit(img).save(os.path.join(PREVIEWS, "surface_z-1.png"))
    # Lumbridge / Draynor 4x3 regions at z=2 (shipped, quantised tiles)
    fit(stitch(os.path.join(OUT, "2"), list(range(47, 51)), list(range(49, 52)), bg["surface"])).save(
        os.path.join(PREVIEWS, "lumbridge_draynor_z2.png"))
    def span(lo, hi):
        return list(range(lo // 64, (hi - 1) // 64 + 1))

    zb = next(l for l in layers if l["id"] == "zanaris")["bounds"]
    fit(stitch(os.path.join(OUT, "2"), span(zb[0], zb[2]), span(zb[1], zb[3]), (0, 0, 0))).save(
        os.path.join(PREVIEWS, "zanaris_z2.png"))
    # contact sheet: every off-surface layer at z=2 (shipped tiles), 1 px per game tile,
    # cropped to the index bounds, with the rings marked
    from PIL import ImageDraw
    rings = load_rings()
    cell = 400
    sheet = Image.new("RGB", (4 * cell, 3 * cell), (40, 40, 40))
    d = ImageDraw.Draw(sheet)
    for k, l in enumerate(layers[1:]):
        b = l["bounds"]
        xs, ys = span(b[0], b[2]), span(b[1], b[3])
        img = stitch(os.path.join(OUT, "2"), xs, ys, (0, 0, 0))
        ox, oy = (b[0] - xs[0] * 64) * 4, ((ys[-1] + 1) * 64 - b[3]) * 4
        img = img.crop((ox, oy, ox + (b[2] - b[0]) * 4, oy + (b[3] - b[1]) * 4))
        scale = min((cell - 10) / img.size[0], (cell - 24) / img.size[1], 1.0)
        img = img.resize((max(1, round(img.size[0] * scale)), max(1, round(img.size[1] * scale))), Image.LANCZOS)
        x, y = (k % 4) * cell + 5, (k // 4) * cell + 20
        sheet.paste(img, (x, y))
        d.text((x, y - 16), "%s %s" % (l["id"], b), fill=(255, 255, 255))
        for r in rings:
            if r["layer"] == l["id"]:
                px = x + (r["x"] - b[0] + 0.5) * 4 * scale
                py = y + (b[3] - r["y"] - 0.5) * 4 * scale
                d.ellipse([px - 5, py - 5, px + 5, py + 5], outline=(255, 255, 0), width=2)
                d.text((px + 7, py - 6), r["code"] or "x", fill=(255, 255, 0))
    sheet.save(os.path.join(PREVIEWS, "offsurface_layers_z2.png"))
    print("Previews in", PREVIEWS)


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("plan")
    b = sub.add_parser("build")
    b.add_argument("--tighten", action="store_true", help="drop open-sea surface regions far from land and rings")
    args = ap.parse_args()
    {"plan": cmd_plan, "build": cmd_build}[args.cmd](args)


if __name__ == "__main__":
    main()
