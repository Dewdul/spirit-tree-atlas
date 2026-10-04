# Ring data

This folder holds the inputs for `src/main/resources/com/spirittreeatlas/rings.json`, the
ring list the plugin bundles (DESIGN.md section 3.1). None of it ships in the jar.

## Files

| File | Role |
|---|---|
| `fairy_rings.verified.json` | The research dataset and source of truth for codes, landing tiles, the game's `no_staff_return` flag and the `FAIRYRINGS_LOG_*` varbit ids. A byte-identical copy of the research track's verified file (sha256 `9ee09794b468823e57b51596a67689604fa03e7b66b321d0be84b8fc33ecaf43`). |
| `ring_display.json` | The player-facing text, one entry per ring: short name, area, one-line description, requirement lines, danger lines, points of interest, search tags and notes. Each entry lists the wiki pages it was checked against under `sources`. This text replaces the dataset's own `name`, `description`, `poi` and `notes` fields, which were written as research notes. |
| `portals.json` | Surface entrances of underground layers, each with its wiki source, plus the reasons other layers have none. |
| `unlock.json` | What must hold before a first visit can unlock a ring: the quest states, varbits and varps the plugin reads for the card's "Locked - needs ...". Each condition has a source, a confidence and, where it is an inference, the reasoning. Copied into `rings.json` as `unlock`, without the source text. |

`tools/build_rings.py` combines the four into `rings.json`.

## Regenerating

```
python tools/build_rings.py            # validate, then write rings.json
python tools/build_rings.py --check    # validate only; exit 1 if rings.json is out of date
```

The script uses only the Python standard library (3.8 or newer). The output is
deterministic, so rebuilding unchanged inputs gives identical bytes.

Two optional flags cross-check the data against fresh game data:

```
python tools/build_rings.py --dbrow dump.dbrow --varbits VarbitID.java
```

- `--dbrow`: `config/dump.dbrow` from https://github.com/Joshua-F/osrs-dumps. The script
  checks the 64 `[fairyrings_xxx]` rows of DB table 89. Every code with a description (and
  DIQ) must be in `rings.json`, at the decoded `dest_coord` and with the same
  `no_staff_return` flag.
- `--varbits`: any text holding RuneLite's `VarbitID.FAIRYRINGS_LOG_<CODE> = n` constants
  (the gameval `VarbitID.java`, or a javap dump of it). Each `logVarbit` must match,
  including `FAIRYRINGS_LOG_HIDEOUT` for the hideout sequence.

On 2026-10-03 both passed against osrs-dumps `2026-09-30-rev241` and RuneLite API 1.12.38
gamevals: 64 rows and 56 varbits, with no mismatches.

## What the script enforces

The build fails with a message for each of these:

- **Codes.** Codes are unique and valid. There are exactly 55 dialable codes, including
  DIQ, and they plus the 9 unused codes cover all 64 combinations. There is one sequence
  entry and two exit entries. 42 rings are on the surface.
- **Coverage.** `ring_display.json` has exactly one entry per dataset entry. Entries are
  keyed by the code, `HIDEOUT` for the sequence, or the dataset id for the Zanaris exits.
- **Layers.** Every ring lies inside its layer's bounds from DESIGN 3.3 (inclusive-exclusive)
  and on plane 0. DIQ is `poh` at 0,0,0. The dataset's `surface` flag agrees with the layer.
- **Log varbits.** Every code and the hideout have a positive, unique `logVarbit`. The
  exits have -1.
- **Requirements.** A ring whose dataset entry has a non-optional requirement must have at
  least one requirement line, and every quest or skill the dataset names must appear in
  those lines. Each line starts with `Quest:`, `Skill:`, `Unlock:`, `Item:` or `Diary:`.
  Optional or recommended items (a light source at AJQ, greegrees at CLR, the 50 Agility
  shortcut at BIP) go in notes instead.
- **Text.** Names are at most 28 characters and unique. Descriptions fit on one line of
  at most 120 characters. List items are at most 140 characters. All text is printable
  ASCII, because the info card uses RuneScape bitmap fonts. Tags are lowercase. Lists have
  no duplicates, and there are at most 12 POIs.
- **Unlock conditions.** Each `unlock.json` code is a dialable ring with requirement lines.
  A condition is `quest` (a `Quest` constant name and `FINISHED` or `IN_PROGRESS`), `varbit`
  or `varp` (an id, `>=`, `>` or `==`, an integer, and the gameval constant's `name`), or
  `unknown` (a need the client cannot read). Labels are at most 32 characters, so "Locked -
  needs <label>" fits on one line of the compact card. Every condition has a source and a
  confidence, and a `low` one must be `unknown`, so a guess is only ever a hint. A ring cannot
  have only `unknown` conditions. `RingDataTest` also checks every quest name against
  `net.runelite.api.Quest` and every `name` against its gameval id.
- **Portals.** Each portal is on an underground layer that has rings, inside the surface
  crop (x 1152-3776, y 2368-4032), with at most one per layer and a source.
- **Map index.** When `src/main/resources/com/spirittreeatlas/map/index.json` exists (written
  by `tools/mapgen`), every ring and portal must also lie inside that file's layer bounds,
  which may be tighter than DESIGN 3.3's.

The script also prints these warnings without failing:

- a ring less than one map region (64 tiles) from its layer edge; today only the hideout
  landing, 24 tiles from Zanaris's west edge;
- a dialable ring with fewer than 5 POIs. Some islands and realms simply have little there.

## Sources

- **Game cache.** DB table 89 `fairyring` from `dump.dbrow`/`dump.dbtable` (osrs-dumps
  `2026-09-30-rev241`) gives codes, landing tiles (`dest_coord`) and `no_staff_return`.
- **RuneLite.** `net.runelite.api.gameval.VarbitID` gives the `FAIRYRINGS_LOG_*` ids.
  `plugins/fairyring/FairyRing.java` provides the names and search tags that were merged
  into `tags`.
- **OSRS Wiki.** https://oldschool.runescape.wiki was read via `?action=raw` on 2026-10-03:
  - `Fairy_ring` (its points-of-interest column and closest-points list);
  - every destination page, plus `Farming/Patch_locations` and its `*/Patches`
    transclusions for farming patches near rings;
  - quest pages for requirements.

  Each `ring_display.json` entry names its pages.

## Known uncertainties

- **BLS.** It is unconfirmed that BLS needs a first visit to Great Kourend. The wiki
  states this for AKR, CIR, CIS and DJR only. The line says "probably".
- **BLQ.** The `Fairy_ring` page says Land of the Goblins must be partly complete, while
  the quest and Yu'biusk pages say complete. The requirement names the quest, and a note
  records the disagreement.
- **BJR.** The quest guide uses BJR part-way through Holy Grail, so the requirement says
  partial.
- **Landing tiles.** These are the game's `dest_coord`. Wiki pins differ by 1-3 tiles for
  AIS, AKP, AKR, BLQ and DLP, because the arrival tile is anywhere in the ring's 3x3
  footprint.
- **Grimstone.** It has no portal. Its dungeon entrance at (2912,4066) is north of the
  surface crop. If the map generator extends the crop past y 4066, add one to
  `portals.json`.
