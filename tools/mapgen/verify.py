#!/usr/bin/env python3
"""Checks the generated map resources against DESIGN 3.2 and the budget of DESIGN 1.6. Exit code 1 on failure.

    python tools/mapgen/verify.py

Also checks trees.json's places against the index's layers when trees.json exists, and the
surface tiles against Fairy Ring Atlas's when that checkout exists (see mapgen.compare_with_fra).
"""
import json
import os
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mapgen  # noqa: E402

errors = []


def check(cond, msg):
    if not cond:
        errors.append(msg)


def inside(b, x, y):
    return b[0] <= x < b[2] and b[1] <= y < b[3]


with open(os.path.join(mapgen.OUT, "index.json"), encoding="utf-8") as f:
    idx = json.load(f)

check(idx["tileSize"] == 256, "tileSize")
check(idx["levels"] == [2, -1], "levels")
check(idx["cache"] == mapgen.CACHE_PROVENANCE, "cache %r" % idx["cache"])
layers = {l["id"]: l for l in idx["layers"]}
check([l["id"] for l in idx["layers"]] == ["surface", "prifddinas"], "layers %s" % [l["id"] for l in idx["layers"]])

# every listed tile exists, every shipped file is listed, every PNG is 256x256 and not one colour
for z in map(str, idx["levels"]):
    listed = set(idx["tiles"][z])
    files = {fn[:-4] for fn in os.listdir(os.path.join(mapgen.OUT, z)) if fn.endswith(".png")}
    check(listed == files, "level %s: listed %d vs files %d" % (z, len(listed), len(files)))
    check(len(listed) == len(idx["tiles"][z]), "level %s: duplicate tile keys" % z)
    for key in idx["solid"].get(z, {}):
        check(key not in files, "solid tile %s/%s is also shipped" % (z, key))
    for fn in files:
        with Image.open(os.path.join(mapgen.OUT, z, fn + ".png")) as im:
            check(im.size == (256, 256), "%s/%s size %s" % (z, fn, im.size))
            check(im.mode in ("P", "RGB"), "%s/%s mode %s" % (z, fn, im.mode))
            a = np.asarray(im.convert("RGB"))
            check(not np.all(a == a[0, 0]), "%s/%s is a single colour" % (z, fn))
check(not any(os.path.isdir(os.path.join(mapgen.OUT, z)) for z in ("0", "1")), "stray levels 0/1 on disk")

# the layers: the surface exactly the world map's surface, Prifddinas inside its world map squares
sb = layers["surface"]["bounds"]
check(sb == mapgen.SURFACE_BOUNDS, "surface bounds %s, expected %s" % (sb, mapgen.SURFACE_BOUNDS))
pb = layers["prifddinas"]["bounds"]
check(mapgen.PRIF_RENDER[0] <= pb[0] < pb[2] <= mapgen.PRIF_RENDER[2]
      and mapgen.PRIF_RENDER[1] <= pb[1] < pb[3] <= mapgen.PRIF_RENDER[3], "prifddinas bounds %s" % pb)
check(layers["prifddinas"]["background"] == "#000000", "prifddinas background")
layer_regions = set().union(*(mapgen.regions_in(L["bounds"]) for L in idx["layers"]))
for t in idx["tiles"]["2"]:
    check(tuple(map(int, t.split("_"))) in layer_regions, "z=2 tile %s outside every layer" % t)

# every tree and house portal of the dataset is inside its layer, with a z=2 tile under it
trees = mapgen.load_trees()
for t in trees:
    b = layers[t["layer"]]["bounds"]
    check(inside(b, t["x"], t["y"]), "%s outside %s" % (t, t["layer"]))
    check("%d_%d" % (int(t["x"]) // 64, int(t["y"]) // 64) in idx["tiles"]["2"], "no z=2 tile under %s" % t)

# labels / icons inside some layer
for l in idx["labels"]:
    check(inside(layers[l["layer"]]["bounds"], l["x"], l["y"]), "label outside its layer: %s" % l)
    check(l["s"] in (0, 1, 2), "label size %s" % l)
for x, y, sprite in idx["icons"]:  # compact [x, y, sprite] triples
    check(any(inside(L["bounds"], x, y) for L in idx["layers"]), "icon outside every layer: %s" % [x, y, sprite])
check(any(l["t"] == "Prifddinas" and l["layer"] == "surface" for l in idx["labels"]), "no surface Prifddinas label")

# the icon rule (DESIGN 3.2): no transportation icon within 3 tiles of a tree, no farming patch icon
# within 3 tiles of a patch tree, and every other icon of the raw export (fairy rings included) kept
plants = [t for t in trees if t["kind"] != "portal"]
patches = [t for t in plants if t["kind"] == "patch"]


def under(x, y, sprite):
    ts = plants if sprite == mapgen.TRANSPORT_SPRITE else patches if sprite == mapgen.FARMING_SPRITE else []
    return any(abs(x - t["x"]) <= mapgen.ICON_RADIUS and abs(y - t["y"]) <= mapgen.ICON_RADIUS for t in ts)


shipped = {tuple(i) for i in idx["icons"]}
for x, y, sprite in shipped:
    check(not under(x, y, sprite), "icon %s sits under a tree marker" % [x, y, sprite])
raw_icons = os.path.join(mapgen.RAW, "icons.json")
if os.path.exists(raw_icons):
    with open(raw_icons, encoding="utf-8") as f:
        want = {(i["x"], i["y"], i["sprite"]) for i in json.load(f)
                if i["z"] == 0 and any(inside(L["bounds"], i["x"], i["y"]) for L in idx["layers"])
                and not under(i["x"], i["y"], i["sprite"])}
    check(want == shipped, "icons differ from the raw export: %d missing, %d extra" % (len(want - shipped), len(shipped - want)))
    print("icons: %d shipped = the raw export minus the icons under tree markers" % len(shipped))
else:
    print("icons: build/raw/icons.json missing; completeness not checked")

# the surface covers the world map's whole surface (minus its void frame): every region in it is
# shipped, a recorded solid tile, or (checked against the raw render when present) plain background
bg = tuple(int(layers["surface"]["background"][k:k + 2], 16) for k in (1, 3, 5))
shipped2 = set(idx["tiles"]["2"])
plain = [rc for rc in mapgen.regions_in(sb) if "%d_%d" % rc not in shipped2 and "%d_%d" % rc not in idx["solid"]["2"]]
for rc in plain:
    a = mapgen.load_raw(*rc)
    if a is not None:
        a = a.copy()
        mapgen.paint_outside({rc: a}, [rc], sb, bg)
        check(np.all(a == np.array(bg, dtype=np.uint8)), "surface region %s is not shipped but is not plain sea" % (rc,))
print("surface %s: %d regions, %d shipped or solid, %d plain sea" % (sb, len(mapgen.regions_in(sb)), len(mapgen.regions_in(sb)) - len(plain), len(plain)))

# trees.json (when built): every placed tree, house portal and portal inside the index's layers
if os.path.exists(mapgen.TREES_JSON):
    with open(mapgen.TREES_JSON, encoding="utf-8") as f:
        tj = json.load(f)
    n = 0
    for t in tj["trees"] + tj["housePortals"]:
        if "x" in t:
            n += 1
            check(t["layer"] in layers and inside(layers[t["layer"]]["bounds"], t["x"], t["y"]), "trees.json: %s outside its layer" % t.get("id", t))
    for p in tj["portals"]:
        n += 1
        check(p["layer"] in layers and inside(sb, p["x"], p["y"]), "trees.json: portal %s not on the surface" % p)
    print("trees.json: %d places inside the index's layers" % n)
else:
    print("trees.json missing; run tools/build_trees.py")

# Fairy Ring Atlas: the surface tiles must be the same bytes (same cache, same renderer)
res = mapgen.compare_with_fra(idx)
if res is not None:
    mapgen.print_fra(res)
    check(res["ok"], "surface tiles differ from Fairy Ring Atlas's")

# the budget: all of src/main/resources, and every bundled PNG
total, count = mapgen.dir_bytes(mapgen.RESOURCES)
print("src/main/resources: %d bytes in %d files (%.3f MiB; budget %d)" % (total, count, total / 1048576, mapgen.BUDGET))
check(total <= mapgen.BUDGET, "over budget")
npng, bad = mapgen.png_limits(mapgen.RESOURCES)
check(not bad, "PNGs over 256x256 or %d bytes decoded: %s" % (mapgen.DECODED_MAX, bad[:10]))

if errors:
    print("FAILED:")
    for e in errors[:50]:
        print("  " + e)
    sys.exit(1)
print("OK: %d z=2 tiles, %d z=-1 tiles, %d layers, %d labels, %d icons, %d PNGs within limits"
      % (len(idx["tiles"]["2"]), len(idx["tiles"]["-1"]), len(idx["layers"]), len(idx["labels"]), len(idx["icons"]), npng))
