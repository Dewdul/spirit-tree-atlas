# mapgen: map tiles for Spirit Tree Atlas

Generates everything under `src/main/resources/com/spirittreeatlas/map/`:
the z=2 tiles (4 px per game tile, one 256x256 PNG per 64x64 region), the
z=-1 overview tiles (0.5 px per game tile, 8x8 regions per tile) and
`map/index.json` (DESIGN.md section 3.2).

It is Fairy Ring Atlas's generator, ported: the same cache, renderer and
encoder, so the surface tiles come out byte-identical to Fairy Ring Atlas's
(checked on every build). What changed: the layers are `surface` and
`prifddinas` only, the data is the spirit tree dataset, and the icon filter
keeps the fairy ring icons and drops the icons under the spirit tree markers.

This is a standalone Gradle project. The plugin build does not include it
(the root `settings.gradle` names only the plugin) and nothing here is shipped.

All imagery is rendered from the game cache by RuneLite's own
`net.runelite.cache.MapImageDumper` (BSD-2, `net.runelite:cache`), with
icons and labels switched off. No wiki or third-party imagery is bundled.

## Current output

| | value |
|---|---|
| Cache | OpenRS2 archive id **2727**, OSRS live, 2026-09-30, build 241 (0 XTEA keys needed) |
| Renderer | `net.runelite:cache:1.13.1` `MapImageDumper.drawRegion(region, 0)` |
| Rendered | 1648 regions: the surface's 1632 (48x34) and Prifddinas's 16 (4x4); all have map data |
| `surface` "Gielinor" | bounds x 1016-3976, y 2104-4168 (exactly Fairy Ring Atlas's), background `#4a5d89` |
| `prifddinas` "Prifddinas" | bounds x 3136-3392, y 5952-6208 (the content fills all 16 map squares, so content + 8 tiles is clamped to them), background `#000000` |
| z=2 | 1592 tiles, 6,599,180 bytes (1589 PNG8, 3 PNG24): surface 1576 (6,454,046 bytes), Prifddinas 16 (145,134 bytes); 2 solid surface tiles recorded, 54 plain-sea regions not shipped |
| z=-1 | 37 tiles, 846,139 bytes (33 PNG8, 4 PNG24): surface 35 (834,184 bytes), Prifddinas 2 (`6_11`, `6_12`, 11,955 bytes) |
| index.json | 96,103 bytes: 2 layers, 561 labels (5 on Prifddinas), 2740 icons (95 on Prifddinas) |
| `map/` total | **7,541,422 bytes** in 1630 files |
| `src/main/resources` | **7,556,203 bytes (7.21 MiB)** in 1631 files, with `trees.json` (14,781 bytes) |
| Budget | all of `src/main/resources` <= 7.6 MiB (7,969,177 bytes), so the jar stays under the hub's 8 MiB warning; every PNG at most 256x256 and under 950,000 bytes decoded (all are 256x256: 262,144 bytes) |

Lossy PNG8 tiles: 544, PSNR >= 40.0 dB (median 42.4 dB). The rest are
lossless palette tiles (<= 256 colours) or PNG24.

**Against Fairy Ring Atlas** (its checkout at `../FairyRingAtlas`, 978815e):
all 1576 surface z=2 tiles and the 2 solid entries are byte-identical. Of the
35 surface z=-1 tiles, 31 are byte-identical; `4_8`, `5_8`, `6_8` and `7_8`
differ because Fairy Ring Atlas drew Zanaris and its realms (y >= 4288) into
them, which are now plain sea. Inside the surface bounds those four differ
only by the lossy PNG8 re-quantisation of the changed tile (at most 12 levels
per channel; `7_8` not at all).

## Regenerate

From the repo root, with JDK 21 for Gradle (Gradle 8.10 cannot run on JDK 25)
and Python 3 with Pillow and numpy:

```bash
# 1. the cache (git-ignored). Check https://archive.openrs2.org/caches.json for a newer
#    oldschool/live id, and update CACHE_PROVENANCE in mapgen.py if you change it.
#    (Or copy Fairy Ring Atlas's tools/mapgen/cache, which is the same archive.)
mkdir -p tools/mapgen/cache && cd tools/mapgen/cache
curl -LO https://archive.openrs2.org/caches/runescape/2727/disk.zip
curl -LO https://archive.openrs2.org/caches/runescape/2727/keys.json
unzip disk.zip && rm disk.zip       # -> tools/mapgen/cache/cache/main_file_cache.*
cd ../../..

# 2. which regions to render (tree/layer bounds check included)
python tools/mapgen/mapgen.py plan

# 3. render z=2 regions + export world-map labels/icons (about 1 minute)
JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.10.7-hotspot" ./gradlew --no-daemon -p tools/mapgen render

# 4. filter, build z=-1, quantise, write map/ + index.json, compare with Fairy Ring Atlas,
#    print the budget (about half a minute on 24 cores)
python tools/mapgen/mapgen.py build          # MAPGEN_PSNR=40 (default), MAPGEN_PREVIEWS=dir, MAPGEN_FRA=dir
python tools/build_trees.py                  # trees.json checks its places against the new index.json
python tools/mapgen/verify.py                # contract checks; exit 1 on failure
```

`render` accepts `-Pcache=<dir>` (any OSRS disk cache, e.g. a copy of
`~/.runelite/jagexcache/oldschool/LIVE`), `-Pout=<dir>` and `-Pregions=<file>`.
`build --tighten` drops open-sea surface regions that are more than 2 regions
from both land and trees (the plugin then paints them flat sea); it is not
needed at the current size, and it would break the identity with Fairy Ring Atlas.

`MAPGEN_FRA` points at Fairy Ring Atlas's `src/main/resources/com/fairyringatlas/map`
(default: the sibling checkout `../FairyRingAtlas`); set it empty to skip the comparison.
After a cache update both plugins' maps change, so regenerate Fairy Ring Atlas's first.

## Pipeline

1. **`mapgen.py plan`** reads `tools/data/spirit_trees.verified.json`, takes the
   whole surface (`SURFACE_RENDER`: x 960-4032, y 2048-4224, 48x34 regions,
   exactly the map squares of the world map's own "Gielinor Surface") and
   Prifddinas (`PRIF_RENDER`: x 3136-3392, y 5952-6208, the 4x4 map squares of
   the world map's "Prifddinas", file 29), checks every tree and house portal
   lies inside its layer and writes `regions.json`.
2. **`MapGen.java`** (`./gradlew -p tools/mapgen render`) loads the cache with
   `Store` + `RegionLoader`, runs `MapImageDumper` with
   `setRenderIcons(false)` / `setRenderLabels(false)` and writes
   `build/raw/2/<rx>_<ry>.png` for every requested region that has map data
   (`build/raw/missing.json` lists the rest). `drawRegion` returns 256x256 at
   4 px per tile with row 0 = north, and draws objects that overlap from the 8
   neighbouring regions. It then exports, from the whole world:
   - `labels.json`: every world-map element whose `AreaDefinition` has a name
     (text, position, `textScale` 0/1/2, `textColor`);
   - `icons.json`: every map-function icon: map locations whose object has a
     `mapAreaId` (`AreaDefinition.spriteId`), plus unnamed world-map elements
     (the intermap link arrows), exactly as `MapImageDumper` draws them;
   - `cache_regions.json`: every region with map data, and `worldmaps.json`:
     each world map's name and the map squares/zones it shows (from the
     `worldmap` index's `details` and `compositemap` archives). `build` checks
     that the surface's and Prifddinas's squares are still exactly the render areas.
3. **`mapgen.py build`**
   - **Layers.** Surface bounds = `SURFACE_BOUNDS`, x 1016-3976, y 2104-4168:
     the outermost 56 tiles of `SURFACE_RENDER` on every side are void
     (`#202f3d`), so the bounds are the content inside that frame (`build`
     recomputes it and warns if it moves), and the frame's pixels in the edge
     tiles are painted open sea. Prifddinas's index bounds are the bbox of its
     non-black pixels + 8 tiles, clamped to its 16 regions (today the whole
     4x4); pixels outside them would be painted black.
   - **Backgrounds.** Surface: `#4a5d89`, the colour of MapImageDumper's
     open-sea tiles (the most common single-colour surface tile).
     Prifddinas: `#000000` (empty map squares render black, `TYPE_INT_RGB`).
   - **Solid tiles** are not shipped. A solid tile whose colour differs from its
     layer background is recorded in `index.json` `solid` (today surface
     regions 17_55 and 17_56, `#536998`).
   - **z=-1**: for every 512x512-tile block touching a layer, a 2048x2048 mosaic
     of the (unquantised, masked) z=2 renders, with missing regions filled with
     the layer background, box-filtered 8:1 (`Image.reduce(8)`). Regions of no
     layer keep the block's fill, so the surface's northern row (ty 8) shows
     open sea where Fairy Ring Atlas had Zanaris and the realms.
   - **Encoding**: per tile, the smallest of PNG24 (`optimize=True`), an exact
     palette when the tile has <= 256 colours, and PNG8 candidates from
     MEDIANCUT/FASTOCTREE x dither off/Floyd-Steinberg x 256/192/128 colours x
     k-means palette refinement off/on, keeping only PNG8 candidates with PSNR
     >= 40 dB against the render. (Pillow here has no libimagequant.)
   - **Labels**: plane 0, inside a layer's bounds; `<br>` line breaks are joined
     with spaces; optional `c` = text colour when not white.
   - **Icons**: plane 0, inside a layer's bounds, de-duplicated. Every
     map-function icon is kept (fairy rings included), except the
     transportation icon (sprite 1504) within 6 tiles (per axis) of a spirit
     tree centre and the farming patch icon (sprite 1501) within 3 tiles of a
     patch tree, because the plugin draws its own markers there. Today that
     drops 8 transportation and 5 farming patch icons.
     The transportation radius was 3 until 2026-10-04: the Tree Gnome Village
     tree's icon, at (2539,3166), is 5.5 tiles from its centre (2544.5,3169.5),
     so it showed beside the marker. At 6 tiles the only icon the rule adds is
     that one; every icon within 6 tiles of a tree, before and after:

     | Tree | Icons within 6 tiles (x,y sprite: distance) | Shipped |
     |---|---|---|
     | Tree Gnome Village | 2539,3166 1504: 5.5 | dropped now (was kept) |
     | | 2543,3169 1454: 1.5; 2540,3171 1454: 4.5 | kept |
     | Gnome Stronghold, Battlefield of Khazard, Grand Exchange, Feldip Hills, Poison Waste | their 1504 at 0-1.5 | dropped (both rules) |
     | Prifddinas | 3275,6124 1504: 0.5 | dropped (both rules) |
     | | 3271,6125 1488: 3.5; 3269,6127 1487: 5.5 | kept |
     | Laguna Aurorae | 1203,2787 1504: 0.5 | dropped (both rules) |
     | | 1201,2792 1474: 5.5 | kept |
     | Port Sarim, Etceteria, Brimhaven, Hosidius | their 1501 at 2-3 | dropped (both rules) |
     | Farming Guild | 1255,3753 1501: 3.0 | dropped (both rules) |
     | | 1248,3754 1487: 5.0 | kept |

     The z=2 and z=-1 tiles do not change with this rule (icons are never baked in).
   - **Surface identity**: compares every surface tile with Fairy Ring Atlas's
     (above) and prints the result; `verify.py` fails on any unexplained difference.
   - Writes previews to `build/previews/` (or `$MAPGEN_PREVIEWS`): the whole
     surface at z=-1 with the trees (yellow) and house portals (cyan) ringed,
     Lumbridge/Draynor at z=2, and Prifddinas at z=2 with its tree and house
     portal ringed.

## The Prifddinas portal

On the surface, the world map draws Prifddinas as a place label at (2240,3328)
and four map-link icons (sprite 1535) at its gates: (2239,3270), (2183,3327),
(2297,3327) and (2239,3384). `trees.json`'s portal uses (2239,3327), where
the gates' axes cross: the plugin draws it at the tile centre (2239.5,3327.5),
in line with the north and south gates (drawn at x 2239.5) and the east and
west gates (drawn at y 3327.5), one tile from the label.

## Orientation check

Fairy Ring Atlas compared tile (50,50) at z=2 (Lumbridge, x 3200-3263,
y 3200-3263) with the OSRS Wiki's `0/2/0_50_50.png`: mean absolute difference
9.2 against 41.5 for the same tile flipped north/south. So row 0 is north and
z=2 tile (tx,ty) is region (tx,ty), as DESIGN 3.2 requires; the surface tiles
here are the same bytes. The Prifddinas preview shows the walled city with the
Tower of Voices at its centre and the spirit tree in the north-east (Meilyr)
district, where the wiki places it.
