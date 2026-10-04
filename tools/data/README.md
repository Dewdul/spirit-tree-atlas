# Spirit tree data

This folder holds the inputs for `src/main/resources/com/spirittreeatlas/trees.json`, the
destination list the plugin bundles (DESIGN.md section 3.1). None of it ships in the jar.

## Files

| File | Role |
|---|---|
| `spirit_trees.verified.json` | The research dataset and source of truth for menu labels, menu order, `previousValue` (`VarbitID.SPIRIT_TREE_PREVIOUS`), layers, tree tiles and centres, arrival tiles and house portals. A copy of the research track's reconciled `destinations/verified.json` (2026-10-04) with the seven fixes below applied. It keeps the research record: per-entry sources, confidence, unlock analysis and research notes. Nothing in it is shown to players directly. |
| `tree_display.json` | The player-facing text, one entry per tree: card name, map label, area, locked hint, requirement lines, points of interest, notes and dangers, plus the global requirement and the surface portal of the Prifddinas layer. Each entry lists the wiki pages it was checked against under `sources`. |

`tools/build_trees.py` combines the two (and the layer bounds of `map/index.json`) into `trees.json`.

## Regenerating

```
python tools/build_trees.py            # validate, then write trees.json
python tools/build_trees.py --check    # validate only; exit 1 if trees.json is out of date
```

The script uses only the Python standard library (3.8 or newer). The output is deterministic:
rebuilding unchanged inputs gives identical bytes. It needs `map/index.json` (written by
`tools/mapgen`) for the layer bounds and the place labels.

## What the script enforces

The build fails with a message for each of these:

- **Trees.** Exactly 14, in menu order (`menuRow` 0-13); ids UPPER_SNAKE and unique; menu
  labels, map labels and names unique (case-insensitive); `tree_display.json` has exactly
  one entry per tree.
- **Previous values.** 1-14, each once; the house is 12.
- **Kinds and layers.** `kind` in fixed/patch/quest/house (the dataset's `island` for Laguna
  Aurorae becomes `quest`, DESIGN 2.5); `layer` in surface/prifddinas/poh; only the house is
  `house`, on `poh`, with no coordinates, and only it matches by `prefix`.
- **Places.** Every surface tree inside the surface bounds and the Prifddinas tree inside the
  prifddinas bounds of `index.json`, on plane 0; every house portal inside its layer. House
  portal values are exactly 1, 2, 3, 4, 5, 6, 8, 9 and 13, all high confidence. There is one
  portal per off-surface layer (Prifddinas), on integer tiles inside the surface bounds and
  within 2 tiles of the world map's own surface label for it.
- **Text.** Printable ASCII only (the plugin draws with RuneScape bitmap fonts); no stray
  whitespace; `label` and `menuLabel` at most 24 characters, `lockedHint` at most 32, `name`
  24, `area` 100, list items 140. 3-6 POIs and 0-3 notes per tree; no duplicate list items.
- **Requirements.** Each line starts with `Quest:`, `Skill:`, `Unlock:`, `Item:` or `Diary:`.
  Every quest and skill level of the dataset's requirement lines appears in the display's.
  Patch, quest and house trees have requirement lines. The global requirement names Tree
  Gnome Village.

It warns, without failing, when an arrival tile is more than 4 tiles from its tree, and
when "Your house (<town>)" is longer than a map label (today only Pollnivneach, 25
characters).

## Fixes applied to the research dataset

From the data challenge of 2026-10-04 (research `challenge/DATA_CHALLENGE.md`, its own scan of
cache 2727). They are also listed in the dataset's `_fixes`:

1. **Farming Guild planting level.** "Skill: 85 Farming to plant (boostable; the patch is in the
   advanced tier, which needs 85 to enter)", was 83 (Farming_Guild lines 9, 25, 106, 108;
   Spirit_Tree_(Farming)/Patches).
2. **House locations 9 (Prifddinas) and 13 (Aldarin)** are high confidence. Cache enum 252,
   used by cs2 `[proc,script5909]` line 102, maps exactly 1 Rimmington, 2 Taverley,
   3 Pollnivneach, 4 Rellekka, 5 Brimhaven, 6 Yanille, 8 Hosidius, 9 Prifddinas and 13
   Aldarin, so the list is complete; added to `housePortalSources`.
3. **Hosidius area.** "Hosidius, next to the saltpetre deposits, south-east of the Forthos Ruin
   (Great Kourend)"; the tree is about 31 tiles south of the nearest Forthos Dungeon entrance,
   not "just south" (Spirit_tree line 62; Forthos_Dungeon line 22; cache labels Saltpetre
   1702,3520 and Forthos Ruin 1675,3575).
4. **Battlefield POI.** "Clock Tower to the south-east": the cache label is at 2573,3242,
   18 tiles east and 18 south of the tree (the wiki's "east" is from the whole Battlefield).
5. **Etceteria note.** "The only teleport onto Etceteria (otherwise the Rellekka boat to
   Miscellania, or moor at 65 Sailing after Royal Trouble)"; Sailing made "the only direct
   transport" untrue (Etceteria line 53). The optional requirement line "Quest: The
   Fremennik Trials (for the Rellekka boat, to reach and plant the patch)" is added too
   (Etceteria line 29).
6. **House portal centres** are exact footprint centres in tile-index units, as the tree
   centres are: Rimmington 2951.5,3224; Taverley 2891.5,3465; Pollnivneach 3340,3001.5;
   Rellekka 2670,3629.5; Brimhaven 2755.5,3178; Yanille 2544,3097.5; Hosidius 1740.5,3517;
   Prifddinas 3239,6077.5; Aldarin 1422,2962 (unchanged). They were rounded down.
7. **Last-destination source.** The varbit 20252 op text is on 9 locs, not 7: 40778 (the
   Christmas house tree) and 44936 (the Leagues house tree) are added to
   `previousDestination.source`. The value mapping is unchanged.

## Sources

- **Game cache 2727** (OpenRS2, 2026-09-30), decoded with `net.runelite:cache` 1.13.1 by the
  research track: loc placements and sizes (tree tiles and centres), multiloc varbits, the
  conditional "Last-destination" op text (previous values), enum 252 (house towns), the house
  portal placements, and the world map labels and icons (the Prifddinas portal point; the
  Clock Tower, Saltpetre and Forthos Ruin labels).
- **Shortest Path** (Skretzo/shortest-path @18983976) `transports/spirit_trees.tsv`: arrival
  tiles (not checked in game).
- **OSRS Wiki**, https://oldschool.runescape.wiki, read via `?action=raw` on 2026-10-04:
  - `Spirit_tree` (locations, requirements, fastest routes, planting limits, payment, the
    Treasure Trails challenge answer), `Spirit_Tree_(Farming)/Patches` (gardeners, nearby
    activities), `Spirit_tree_(Construction)` (house tree requirements; grows at once),
    `Spirit_Tree_(Incomitatus)`, `Transcript:Spirit_tree` (refusal lines);
  - the destination pages: `Tree_Gnome_Village_(location)`, `Tree_Gnome_Stronghold`,
    `Battlefield`, `Grand_Exchange`, `Feldip_Hills`, `Myths'_Guild` (Wrath Altar via its
    basement), `Corsair_Cove` (its bank after The Corsair Curse), `Prifddinas`, `Etceteria`,
    `Miscellania_and_Etceteria_dungeon` (sea snakes), `Hosidius`, `Forthos_Dungeon`,
    `Farming_Guild`, `Poison_Waste_Dungeon`, `Laguna_Aurorae`, `Kurask` (70 Slayer);
  - quest pages for requirements: `Tree_Gnome_Village`, `The_Grand_Tree`,
    `Song_of_the_Elves`, `The_Path_of_Glouphrie`, `Pandemonium`.

  Each `tree_display.json` entry names its pages.

## Known uncertainties

- **Arrival tiles** come from Shortest Path only and are not checked in game. They are
  informational.
- **Poison Waste.** The cache's tree turns to its Travel form at The Path of Glouphrie stage
  37 and from 39 up (38 is talk-only); whether travel is refused at 38 is unchecked. The
  requirement line says "part way".
- **Laguna Aurorae.** "Moor at Laguna Aurorae once" comes from the wiki and the tree's refusal
  line; the client varbit for it (`LAGUNA_AURORAE_VISITED`) is inferred from its name.
- **Farming Guild locked hint.** "Grow a tree here (85 Farming)" covers both reasons the row
  could be grey; whether the game greys the row below 85 Farming or only refuses the trip is
  unknown.
- **Your house.** No client variable is known for "the house has a spirit tree"; the menu row
  colour is the only signal.
- **Tree Gnome Village icon.** The map's transportation icon for this tree sits 5.5 tiles from
  the tree centre, outside the 3-tile drop radius, so the map still shows it next to the
  marker (see `tools/mapgen/README.md`).
