#!/usr/bin/env python3
"""Spirit Tree Atlas map pipeline, step 0 and step 2.

    python tools/mapgen/mapgen.py plan     # writes tools/mapgen/regions.json
    ./gradlew -p tools/mapgen render       # step 1 (Java): renders build/raw/2/*.png + labels/icons
    python tools/mapgen/mapgen.py build    # filters, builds z=-1, quantises, writes resources + index.json

`build` writes into src/main/resources/com/spirittreeatlas/map/ (wiping the old
tiles first), compares the surface tiles with Fairy Ring Atlas's when that
checkout is present, and prints the budget. Requires Pillow and numpy.
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
TREES_SRC = os.path.join(REPO, "tools", "data", "spirit_trees.verified.json")
RESOURCES = os.path.join(REPO, "src", "main", "resources")
TREES_JSON = os.path.join(RESOURCES, "com", "spirittreeatlas", "trees.json")
OUT = os.path.join(RESOURCES, "com", "spirittreeatlas", "map")
RAW = os.path.join(HERE, "build", "raw")
REGIONS_JSON = os.path.join(HERE, "regions.json")
# Fairy Ring Atlas's shipped map (same cache, same renderer): the surface tiles must match it
# byte for byte. A sibling checkout by default; MAPGEN_FRA overrides, an empty value skips the check.
FRA_MAP = os.environ.get("MAPGEN_FRA", os.path.normpath(os.path.join(
    REPO, "..", "FairyRingAtlas", "src", "main", "resources", "com", "fairyringatlas", "map")))

CACHE_PROVENANCE = "openrs2 2727 (2026-09-30, build 241)"
TILE = 256
REGION = 64
BUDGET = int(7.6 * 1024 * 1024)  # all of src/main/resources; keeps the jar under the hub's 8 MiB warning
DECODED_MAX = 950000  # w*h*4 of every bundled PNG stays under this; the hub bot rejects about 1 MiB decoded
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

# Prifddinas. The world map's "Prifddinas" (worldmap file 29, safeName prifddinas) shows exactly
# the 16 map squares rx 49-52, ry 93-96 (x 3136-3391, y 5952-6207); cmd_build checks this.
PRIF_RENDER = [3136, 5952, 3392, 6208]
PRIF_MAP = "prifddinas"

# The index bounds of an off-surface layer are the bounding box of its non-empty pixels in its
# regions, plus MARGIN tiles, clamped to those regions. Pixels outside the index bounds are
# painted with the layer background so no unrelated content leaks in at the edges.
MARGIN = 8
TRANSPORT_SPRITE = 1504  # SpriteID.Mapfunction.TRANSPORTATION: beside every fixed spirit tree
FARMING_SPRITE = 1501    # SpriteID.Mapfunction.FARMING_PATCH: the only icon on a patch tree
# tiles, per axis, from a tree centre within which those icons are dropped: 6 for the
# transportation icon (Tree Gnome Village's sits 5.5 tiles from its tree), 3 for the patch icon
TRANSPORT_RADIUS = 6
FARMING_RADIUS = 3

#   "bounds": region-aligned area rendered.
#   "keep":   optional subset of regions [rx, ry] that belong to the layer. None is needed:
#             all 16 Prifddinas map squares are the city and its forest (checked on the preview).
LAYERS = [
    # surface bounds are SURFACE_BOUNDS, set in plan_layers()
    {"id": "surface", "name": "Gielinor", "bounds": None},
    {"id": "prifddinas", "name": "Prifddinas", "bounds": list(PRIF_RENDER)},
]


def load_trees():
    """Every map place in the dataset: the trees, and the house portals as kind 'portal'."""
    with open(TREES_SRC, encoding="utf-8") as f:
        data = json.load(f)
    out = []
    for d in data["destinations"]:
        t = d.get("tree")
        if t is None:
            continue  # the house: placed at a portal below
        out.append({"id": d["id"], "x": t["center"][0], "y": t["center"][1], "layer": d["layer"], "kind": d["kind"]})
    for p in data["housePortals"]:
        out.append({"id": "HOUSE_%d" % p["value"], "x": p["center"][0], "y": p["center"][1], "layer": p["layer"],
                    "kind": "portal"})
    return out


def plan_layers(trees):
    layers = json.loads(json.dumps(LAYERS))
    layers[0]["bounds"] = list(SURFACE_BOUNDS)
    layers[0]["renderBounds"] = list(SURFACE_RENDER)
    return layers


def check_world_map_extents():
    """The world map's surface and Prifddinas squares (exported by MapGen) must be exactly the render areas."""
    p = os.path.join(RAW, "worldmaps.json")
    if not os.path.exists(p):
        print("WARNING: %s missing; cannot check the world map extents" % p)
        return
    with open(p, encoding="utf-8") as f:
        maps = json.load(f)
    for what, match, render in (("surface", lambda m: m.get("surface"), SURFACE_RENDER),
                                (PRIF_MAP, lambda m: m.get("safeName") == PRIF_MAP, PRIF_RENDER)):
        sq = {(s[2], s[3]) for m in maps if match(m) for s in m.get("squares", [])}
        want = set(regions_in(render))
        if sq != want:
            print("WARNING: world map %s squares differ from %s: %d extra, %d missing"
                  % (what, render, len(sq - want), len(want - sq)))
        else:
            print("World map extent: the %d %s squares are exactly %s" % (len(sq), what, render))


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


def check_trees_in_layers(trees, layers):
    by_id = {l["id"]: l for l in layers}
    bad = []
    for t in trees:
        l = by_id.get(t["layer"])
        if l is None or not (l["bounds"][0] <= t["x"] < l["bounds"][2] and l["bounds"][1] <= t["y"] < l["bounds"][3]):
            bad.append(t)
    for t in bad:
        print("TREE OUTSIDE LAYER BOUNDS:", t)
    print("Tree/layer bounds check: %d trees and house portals, %d outside" % (len(trees), len(bad)))
    return not bad


def cmd_plan(_):
    trees = load_trees()
    layers = plan_layers(trees)
    ok = check_trees_in_layers(trees, layers)
    want = []
    seen = set()
    for b in [l.get("renderBounds", l["bounds"]) for l in layers]:
        for rc in regions_in(b):
            if rc not in seen:
                seen.add(rc)
                want.append(list(rc))
    with open(REGIONS_JSON, "w") as f:
        json.dump({"layers": layers, "regions": want}, f, separators=(",", ":"))
    for l in layers:
        b = l.get("renderBounds", l["bounds"])
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
    trees = load_trees()
    layers = plan_layers(trees)  # from code, not regions.json, so edits apply without re-rendering
    check_world_map_extents()

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
            elif l["id"] != "surface":
                print("WARNING: %s region %s has no render" % (l["id"], rc))

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
        print("  %s: content %s, bounds %s (content + %d tiles, inside %s)"
              % (l["id"], [int(x0), int(y0), int(x1), int(y1)], b, MARGIN, ext))
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
    if not check_trees_in_layers(trees, layers):
        sys.exit(1)

    # ---- optional surface tightening: drop regions far from land
    surface_drop = set()
    if args.tighten:
        surface_drop = tighten_surface(layers[0], z2, bg["surface"], trees)

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
        # fill colour: background of the first layer whose bounds touch this block. Regions of
        # no layer keep it, so the surface's northern row of tiles (ty 8), where Fairy Ring
        # Atlas also drew Zanaris and the realms, shows plain sea there.
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
    labels, icons = export_labels_icons(layers, trees)

    index = {
        "cache": CACHE_PROVENANCE,
        "tileSize": TILE,
        "levels": [2, -1],
        "tiles": {
            "2": ["%d_%d" % rc for rc in sorted(ship2, key=lambda t: (t[1], t[0]))],
            "-1": ["%d_%d" % rc for rc in sorted(zm1, key=lambda t: (t[1], t[0]))],
        },
        "solid": {"2": solid2, "-1": solidm1},
        # "poh" (the house) has no map and therefore no entry here (DESIGN 3.2: card only)
        "layers": [{"id": l["id"], "name": l["name"], "bounds": l["bounds"], "background": l["background"]} for l in layers],
        "labels": labels,
        "icons": icons,
    }
    with open(os.path.join(OUT, "index.json"), "w", encoding="utf-8") as f:
        json.dump(index, f, ensure_ascii=False, separators=(",", ":"))

    report(stats, sizes, psnrs, ship2, zm1, solid2, solidm1, surface_drop, labels, icons)
    res = compare_with_fra(index)
    if res is not None:
        print_fra(res)
    previews(layers, bg, trees)


def tighten_surface(surface, z2, sea, trees):
    """Regions to drop: no land within +-2 regions and further than 2 regions from any tree."""
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
    for t in trees:
        if t["layer"] != "surface":
            continue
        rx, ry = int(t["x"]) // REGION, int(t["y"]) // REGION
        for dx in range(-2, 3):
            for dy in range(-2, 3):
                keep.add((rx + dx, ry + dy))
    drop = {rc for rc in layer_regions(surface) if rc not in keep}
    print("Surface tighten: dropping %d regions" % len(drop))
    return drop


def export_labels_icons(layers, trees):
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
        if l["c"] != "#ffffff":
            o["c"] = l["c"]  # optional text colour (orange/cyan in-game labels); absent = white
        labels.append(o)
    labels.sort(key=lambda o: (o["layer"], -o["s"], o["t"]))
    # The plugin draws its own tree markers, so drop the game's icon under each one: the
    # transportation icon beside every spirit tree and the farming patch icon on every patch
    # tree. Every other map-function icon stays, fairy rings included.
    plants = [t for t in trees if t["kind"] != "portal"]
    patches = [t for t in plants if t["kind"] == "patch"]

    def near(x, y, ts, radius):
        return [t for t in ts if abs(x - t["x"]) <= radius and abs(y - t["y"]) <= radius]

    icons = []
    seen = set()
    covered = set()
    dropped = Counter()
    for i in raw_icons:
        if i["z"] != 0:
            continue
        lid = layer_of(i["x"], i["y"])
        if lid is None:
            continue
        under = (near(i["x"], i["y"], plants, TRANSPORT_RADIUS) if i["sprite"] == TRANSPORT_SPRITE
                 else near(i["x"], i["y"], patches, FARMING_RADIUS) if i["sprite"] == FARMING_SPRITE else [])
        if under:
            dropped[i["sprite"]] += 1
            covered.update(t["id"] for t in under)
            continue
        key = (i["x"], i["y"], i["sprite"])
        if key in seen:
            continue
        seen.add(key)
        icons.append([i["x"], i["y"], i["sprite"]])
    # compact [x, y, sprite] triples: they are most of index.json, which ships as source text
    icons.sort(key=lambda o: (o[1], o[0]))
    print("Icons: dropped %d transportation icons beside trees and %d farming patch icons on patch trees"
          % (dropped[TRANSPORT_SPRITE], dropped[FARMING_SPRITE]))
    for t in plants:
        if t["id"] not in covered:
            print("  note: no icon dropped for %s (none near %s,%s)" % (t["id"], t["x"], t["y"]))
    return labels, icons


def dir_bytes(path):
    total = 0
    count = 0
    for root, _, files in os.walk(path):
        for fn in files:
            total += os.path.getsize(os.path.join(root, fn))
            count += 1
    return total, count


def png_limits(path):
    """(PNG count, offenders): every PNG must be at most 256x256 and under DECODED_MAX bytes decoded."""
    n = 0
    bad = []
    for root, _, files in os.walk(path):
        for fn in files:
            if fn.endswith(".png"):
                n += 1
                with Image.open(os.path.join(root, fn)) as im:
                    w, h = im.size
                if w > TILE or h > TILE or w * h * 4 >= DECODED_MAX:
                    bad.append(os.path.relpath(os.path.join(root, fn), path))
    return n, bad


def report(stats, sizes, psnrs, ship2, zm1, solid2, solidm1, surface_drop, labels, icons):
    mapb, mapn = dir_bytes(OUT)
    treesb = os.path.getsize(TREES_JSON) if os.path.exists(TREES_JSON) else 0
    resb, resn = dir_bytes(RESOURCES)
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
    print("trees.json: %d bytes%s" % (treesb, "" if treesb else " (not present yet)"))
    print("src/main/resources: %d bytes in %d files = %.3f MiB (budget %d bytes) -> %s"
          % (resb, resn, resb / 1048576, BUDGET, "OK" if resb <= BUDGET else "OVER"))
    n, bad = png_limits(RESOURCES)
    print("PNGs: %d; over 256x256 or %d bytes decoded: %d %s" % (n, DECODED_MAX, len(bad), bad[:5]))


# ---------------------------------------------------------------- Fairy Ring Atlas comparison

def compare_with_fra(index):
    """Compares the surface tiles with Fairy Ring Atlas's shipped ones (same cache, same renderer).

    Every surface z=2 tile (and solid entry) must be byte-identical. A surface z=-1 tile may
    differ only when it touches one of Fairy Ring Atlas's off-surface layers (Zanaris and the
    realms north of the surface), whose pixels are now outside every layer and painted with the
    surface background. Returns None when the checkout is absent.
    """
    if not FRA_MAP or not os.path.isfile(os.path.join(FRA_MAP, "index.json")):
        print("Fairy Ring Atlas map not found at %r: surface identity not checked" % FRA_MAP)
        return None
    with open(os.path.join(FRA_MAP, "index.json"), encoding="utf-8") as f:
        fra = json.load(f)
    fra_off = [l["bounds"] for l in fra["layers"] if l["id"] != "surface"]
    res = {"z2_same": 0, "z2_diff": [], "z2_missing": [], "solid_diff": [],
           "zm1_same": 0, "zm1_explained": [], "zm1_diff": [], "zm1_missing": []}

    def read(base, z, key):
        with open(os.path.join(base, z, key + ".png"), "rb") as f:
            return f.read()

    def order(k):
        return tuple(reversed([int(v) for v in k.split("_")]))

    # z=2: one tile per region; the surface regions are those of SURFACE_RENDER
    surf2 = {"%d_%d" % rc for rc in regions_in(SURFACE_RENDER)}
    ours, theirs = set(index["tiles"]["2"]) & surf2, set(fra["tiles"]["2"]) & surf2
    for key in sorted(ours | theirs, key=order):
        if key not in ours or key not in theirs:
            res["z2_missing"].append(key)
        elif read(OUT, "2", key) == read(FRA_MAP, "2", key):
            res["z2_same"] += 1
        else:
            res["z2_diff"].append(key)
    for key in sorted(surf2, key=order):
        if index["solid"]["2"].get(key) != fra["solid"]["2"].get(key):
            res["solid_diff"].append(key)

    # z=-1: one tile per 8x8 regions; the surface tiles are those touching SURFACE_BOUNDS
    def touches(b, tx, ty):
        return b[0] < (tx + 1) * 512 and b[2] > tx * 512 and b[1] < (ty + 1) * 512 and b[3] > ty * 512

    sb = SURFACE_BOUNDS
    surf1 = {"%d_%d" % (tx, ty) for ty in range(sb[1] // 512, (sb[3] - 1) // 512 + 1)
             for tx in range(sb[0] // 512, (sb[2] - 1) // 512 + 1)}
    ours, theirs = set(index["tiles"]["-1"]) & surf1, set(fra["tiles"]["-1"]) & surf1
    for key in sorted(ours | theirs, key=order):
        tx, ty = map(int, key.split("_"))
        explained = any(touches(b, tx, ty) for b in fra_off)
        if key not in ours or key not in theirs:
            (res["zm1_explained"] if explained else res["zm1_missing"]).append(key)
        elif read(OUT, "-1", key) == read(FRA_MAP, "-1", key):
            res["zm1_same"] += 1
        else:
            (res["zm1_explained"] if explained else res["zm1_diff"]).append(key)
    res["ok"] = not (res["z2_diff"] or res["z2_missing"] or res["solid_diff"] or res["zm1_diff"] or res["zm1_missing"])
    return res


def print_fra(res):
    print()
    print("=== surface tiles vs Fairy Ring Atlas (%s) ===" % FRA_MAP)
    print("z=2 : %d byte-identical; %d differ %s; %d on one side only %s; solid entries differ %s"
          % (res["z2_same"], len(res["z2_diff"]), res["z2_diff"][:10], len(res["z2_missing"]), res["z2_missing"][:10],
             res["solid_diff"][:10]))
    print("z=-1: %d byte-identical; %d differ where FRA's tile held its off-surface layers %s; %d other differences %s %s"
          % (res["zm1_same"], len(res["zm1_explained"]), res["zm1_explained"], len(res["zm1_diff"]) + len(res["zm1_missing"]),
             res["zm1_diff"], res["zm1_missing"]))
    print("surface identity: %s" % ("OK" if res["ok"] else "FAILED"))


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


def span(lo, hi):
    return list(range(lo // 64, (hi - 1) // 64 + 1))


def previews(layers, bg, trees):
    from PIL import ImageDraw
    os.makedirs(PREVIEWS, exist_ok=True)
    # the whole surface at z=-1, with every surface tree (yellow) and house portal (cyan) ringed
    surf = layers[0]["bounds"]
    txs = list(range(surf[0] // 512, (surf[2] - 1) // 512 + 1))
    tys = list(range(surf[1] // 512, (surf[3] - 1) // 512 + 1))
    img = stitch(os.path.join(OUT, "-1"), txs, tys, bg["surface"])
    x0 = (surf[0] - txs[0] * 512) // 2
    y0 = ((tys[-1] + 1) * 512 - surf[3]) // 2
    img = img.crop((x0, y0, x0 + (surf[2] - surf[0]) // 2, y0 + (surf[3] - surf[1]) // 2))
    d = ImageDraw.Draw(img)
    for t in trees:
        if t["layer"] == "surface":
            px, py = (t["x"] + 0.5 - surf[0]) / 2, (surf[3] - t["y"] - 0.5) / 2
            d.ellipse([px - 7, py - 7, px + 7, py + 7], outline=(0, 255, 255) if t["kind"] == "portal" else (255, 255, 0), width=2)
    fit(img).save(os.path.join(PREVIEWS, "surface_z-1.png"))
    # Lumbridge / Draynor 4x3 regions at z=2 (shipped, quantised tiles)
    fit(stitch(os.path.join(OUT, "2"), list(range(47, 51)), list(range(49, 52)), bg["surface"])).save(
        os.path.join(PREVIEWS, "lumbridge_draynor_z2.png"))
    # Prifddinas at z=2 (shipped tiles) cropped to its index bounds, the tree and house portal ringed
    b = next(l for l in layers if l["id"] == "prifddinas")["bounds"]
    xs, ys = span(b[0], b[2]), span(b[1], b[3])
    img = stitch(os.path.join(OUT, "2"), xs, ys, (0, 0, 0))
    ox, oy = (b[0] - xs[0] * 64) * 4, ((ys[-1] + 1) * 64 - b[3]) * 4
    img = img.crop((ox, oy, ox + (b[2] - b[0]) * 4, oy + (b[3] - b[1]) * 4))
    d = ImageDraw.Draw(img)
    for t in trees:
        if t["layer"] == "prifddinas":
            px, py = (t["x"] + 0.5 - b[0]) * 4, (b[3] - t["y"] - 0.5) * 4
            d.ellipse([px - 16, py - 16, px + 16, py + 16], outline=(255, 255, 0), width=3)
            d.text((px + 20, py - 6), t["id"], fill=(255, 255, 0))
    img.save(os.path.join(PREVIEWS, "prifddinas_z2.png"))
    print("Previews in", PREVIEWS)


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("plan")
    b = sub.add_parser("build")
    b.add_argument("--tighten", action="store_true", help="drop open-sea surface regions far from land and trees")
    args = ap.parse_args()
    {"plan": cmd_plan, "build": cmd_build}[args.cmd](args)


if __name__ == "__main__":
    main()
