# mapgen: map tiles for Fairy Ring Atlas

Generates everything under `src/main/resources/com/spirittreeatlas/map/`:
the z=2 tiles (4 px per game tile, one 256x256 PNG per 64x64 region), the
z=-1 overview tiles (0.5 px per game tile, 8x8 regions per tile) and
`map/index.json` (DESIGN.md section 3.2).

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
| Surface | the whole world map surface: bounds x 1016-3976, y 2104-4168 (1632 regions rendered) |
| z=2 | 1632 tiles, 6,591,284 bytes (1629 PNG8, 3 PNG24); 2 solid surface tiles recorded, 54 plain-sea regions not shipped |
| z=-1 | 42 tiles, 852,226 bytes (38 PNG8, 4 PNG24) |
| index.json | 97,782 bytes: 12 layers, 578 labels, 2697 icons |
| `map/` total | **7,541,292 bytes (7.19 MiB)** in 1675 files |
| `src/main/resources` | 7,585,896 bytes (7.23 MiB) with `rings.json` (44,604 bytes); plugin jar 7,692,277 bytes |
| Budget | `map/` + `rings.json` <= 7.6 MiB (7,969,177 bytes), so the jar stays under the hub's 8 MiB warning |

Lossy PNG8 tiles: 540, PSNR >= 40.0 dB (median 42.5 dB). The rest are
lossless palette tiles (<= 256 colours) or PNG24. No ocean-specific
compression was needed to fit the budget.

## Regenerate

From the repo root, with JDK 21 for Gradle (Gradle 8.10 cannot run on JDK 25)
and Python 3 with Pillow and numpy:

```bash
# 1. the cache (git-ignored). Check https://archive.openrs2.org/caches.json for a newer
#    oldschool/live id, and update CACHE_PROVENANCE in mapgen.py if you change it.
mkdir -p tools/mapgen/cache && cd tools/mapgen/cache
curl -LO https://archive.openrs2.org/caches/runescape/2727/disk.zip
curl -LO https://archive.openrs2.org/caches/runescape/2727/keys.json
unzip disk.zip && rm disk.zip       # -> tools/mapgen/cache/cache/main_file_cache.*
cd ../../..

# 2. which regions to render (ring/layer bounds check included)
python tools/mapgen/mapgen.py plan

# 3. render z=2 regions + export world-map labels/icons (about 1 minute)
JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.10.7-hotspot" ./gradlew -p tools/mapgen render

# 4. filter, build z=-1, quantise, write map/ + index.json, print the budget
python tools/mapgen/mapgen.py build          # MAPGEN_PSNR=40 (default), MAPGEN_PREVIEWS=dir
python tools/mapgen/verify.py                # contract checks; exit 1 on failure
```

`render` accepts `-Pcache=<dir>` (any OSRS disk cache, e.g. a copy of
`~/.runelite/jagexcache/oldschool/LIVE`), `-Pout=<dir>` and `-Pregions=<file>`.
`build --tighten` drops open-sea surface regions that are more than 2 regions
from both land and rings (the plugin then paints them flat sea); it is not
needed at the current size.

## Pipeline

1. **`mapgen.py plan`** reads `tools/data/fairy_rings.verified.json`, takes the
   whole surface (`SURFACE_RENDER`: x 960-4032, y 2048-4224, 48x34 regions,
   exactly the map squares of the world map's own "Gielinor Surface") and the
   off-surface render areas (DESIGN 3.3), checks every ring lies inside its
   layer and writes `regions.json`.
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
     that the surface map's squares are still exactly `SURFACE_RENDER`.
3. **`mapgen.py build`**
   - **Layers.** Surface bounds = `SURFACE_BOUNDS`, x 1016-3976, y 2104-4168:
     the outermost 56 tiles of `SURFACE_RENDER` on every side are void
     (`#202f3d`), so the bounds are the content inside that frame (`build`
     recomputes it and warns if it moves), and the frame's pixels in the edge
     tiles are painted open sea. Each off-surface layer keeps only the
     regions that belong to the destination (`keep` in `LAYERS`; the wiki-basemap
     bounds also pull in unrelated areas packed next to them in the cache). Its
     index bounds are the bbox of non-black pixels + 8 tiles, clamped to the kept
     regions (manual `clip` for the Cosmic plane, whose west edge has a ground-blend
     band from its neighbour). Pixels outside the bounds are painted black.
   - **Backgrounds.** Surface: `#4a5d89`, the colour of MapImageDumper's
     open-sea tiles (the most common single-colour surface tile).
     Off-surface: `#000000` (empty map squares render black, `TYPE_INT_RGB`).
   - **Solid tiles** are not shipped. A solid tile whose colour differs from its
     layer background is recorded in `index.json` `solid`. (Today only two
     are: surface regions 17_55 and 17_56, `#536998`; every other solid tile
     equals the background, as most of the Sailing-era ocean is textured.)
   - **z=-1**: for every 512x512-tile block touching a layer, a 2048x2048 mosaic
     of the (unquantised, masked) z=2 renders, with missing regions filled with
     the layer background, box-filtered 8:1 (`Image.reduce(8)`).
   - **Encoding**: per tile, the smallest of PNG24 (`optimize=True`), an exact
     palette when the tile has <= 256 colours, and PNG8 candidates from
     MEDIANCUT/FASTOCTREE x dither off/Floyd-Steinberg x 256/192/128 colours x
     k-means palette refinement off/on, keeping only PNG8 candidates with PSNR
     >= 40 dB against the render. (Pillow here has no libimagequant.)
   - **Labels**: plane 0, inside a layer's bounds; `<br>` line breaks are joined
     with spaces; optional `c` = text colour when not white.
   - **Icons**: plane 0, inside a layer's bounds, de-duplicated; the
     transportation icon (sprite 1504) on each fairy ring in the dataset is
     dropped because the plugin draws its own ring markers.
   - Writes previews to `build/previews/` (or `$MAPGEN_PREVIEWS`): the whole
     surface at z=-1, Lumbridge/Draynor at z=2, Zanaris at z=2 and a contact
     sheet of every off-surface layer.

## Orientation check

Tile (50,50) at z=2 (Lumbridge, x 3200-3263, y 3200-3263) was compared with the
OSRS Wiki's `0/2/0_50_50.png` (map version 2026-08-12_a, downloaded once for
the comparison, not shipped): mean absolute difference 9.2 with 90% of pixels
within 8 levels (all differences are the wiki's baked icons), versus 41.5 for
the same tile flipped north/south and 36-77 for the neighbouring regions. A
Zanaris tile (37,69) matches the wiki to 97%. So row 0 is north and z=2 tile
(tx,ty) is region (tx,ty), as DESIGN 3.2 requires.
