#!/usr/bin/env python3
"""Checks the generated map resources against DESIGN 3.2/3.3. Exit code 1 on failure.

    python tools/mapgen/verify.py
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


with open(os.path.join(mapgen.OUT, "index.json"), encoding="utf-8") as f:
    idx = json.load(f)

check(idx["tileSize"] == 256, "tileSize")
check(idx["levels"] == [2, -1], "levels")
layers = {l["id"]: l for l in idx["layers"]}

# every listed tile exists, every shipped file is listed, every PNG is <= 256x256
for z in map(str, idx["levels"]):
    listed = set(idx["tiles"][z])
    files = {fn[:-4] for fn in os.listdir(os.path.join(mapgen.OUT, z)) if fn.endswith(".png")}
    check(listed == files, "level %s: listed %d vs files %d" % (z, len(listed), len(files)))
    for key in idx["solid"].get(z, {}):
        check(key not in files, "solid tile %s/%s is also shipped" % (z, key))
    for fn in files:
        with Image.open(os.path.join(mapgen.OUT, z, fn + ".png")) as im:
            check(im.size == (256, 256), "%s/%s size %s" % (z, fn, im.size))
            check(im.mode in ("P", "RGB"), "%s/%s mode %s" % (z, fn, im.mode))
            if im.mode == "P":
                a = np.asarray(im.convert("RGB"))
            else:
                a = np.asarray(im)
            check(not np.all(a == a[0, 0]), "%s/%s is a single colour" % (z, fn))

# every ring is inside its layer's bounds, and the bounds hold at least one shipped z=2 tile
for r in mapgen.load_rings():
    b = layers[r["layer"]]["bounds"]
    check(b[0] <= r["x"] < b[2] and b[1] <= r["y"] < b[3], "ring %s outside %s" % (r, r["layer"]))
    check("%d_%d" % (r["x"] // 64, r["y"] // 64) in idx["tiles"]["2"], "no z=2 tile under ring %s" % r)

# labels / icons inside some layer
for l in idx["labels"]:
    b = layers[l["layer"]]["bounds"]
    check(b[0] <= l["x"] < b[2] and b[1] <= l["y"] < b[3], "label outside its layer: %s" % l)
    check(l["s"] in (0, 1, 2), "label size %s" % l)
for x, y, sprite in idx["icons"]:  # compact [x, y, sprite] triples
    check(any(L["bounds"][0] <= x < L["bounds"][2] and L["bounds"][1] <= y < L["bounds"][3] for L in idx["layers"]),
          "icon outside every layer: %s" % [x, y, sprite])

# the surface covers the world map's whole surface (minus its void frame): every region in it is
# shipped, a recorded solid tile, or (checked against the raw render when present) plain background
sb = layers["surface"]["bounds"]
check(sb == mapgen.SURFACE_BOUNDS, "surface bounds %s, expected %s" % (sb, mapgen.SURFACE_BOUNDS))
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

ring_bytes = os.path.getsize(mapgen.RINGS_JSON) if os.path.exists(mapgen.RINGS_JSON) else 0
total = sum(os.path.getsize(os.path.join(r, f)) for r, _, fs in os.walk(mapgen.OUT) for f in fs) + ring_bytes
print("map + rings.json: %d bytes (%.3f MiB)%s" % (total, total / 1048576, "" if ring_bytes else " (rings.json missing)"))
check(total <= mapgen.BUDGET, "over budget")

if errors:
    print("FAILED:")
    for e in errors[:50]:
        print("  " + e)
    sys.exit(1)
print("OK: %d z=2 tiles, %d z=-1 tiles, %d layers, %d labels, %d icons"
      % (len(idx["tiles"]["2"]), len(idx["tiles"]["-1"]), len(idx["layers"]), len(idx["labels"]), len(idx["icons"])))
