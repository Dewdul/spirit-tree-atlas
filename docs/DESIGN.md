# Spirit Tree Atlas: design spec

A RuneLite Plugin Hub plugin by Dewdul. While the spirit tree travel menu is open, it covers the
menu with a high-resolution, pannable and zoomable map of every spirit tree destination. You click
a tree on the map; the real menu row for that tree then sits in the map's bottom-right corner as the
**Travel** button, and you click it (or press the row's own key) to travel.

It is a sibling of **Fairy Ring Atlas** (`C:\Users\scott\FairyRingAtlas`, github.com/Dewdul/fairy-ring-atlas,
live on the Hub) and reuses its map engine: the cache-rendered tiles, `MapView`, `TileStore`,
`MapRenderer`, `LabelPlacer`, `Ink`, the layout that grows the map from the interface's bottom-right
corner, "Use free space", the input and menu ownership, the cached chrome, the previews and the
compliance test. Fairy Ring Atlas's own `docs/DESIGN.md` (in that repo) explains those parts in
depth; this spec restates every rule the port depends on, so it is self-contained.

The user's scope (2026-10-04): "Similar to our Fairy Ring Atlas ... there are a lot less teleport
options so we really only need the map, groups aren't needed." So: no Groups panel, no Favourites
list, no Elsewhere panel, no search, no clue helper, no dials, no travel log.

After testing in game the same day the user asked: "a quick select menu on the left would be nice".
So the map has one side panel after all, the **quick-select panel** (4.6): every destination as a
row down the map's left edge, modelled on Fairy Ring Atlas's left panel.

- Internal hub name: `spirit-tree-atlas`
- Display name: "Spirit Tree Atlas"
- Package: `com.spirittreeatlas`
- Config group: `spirittreeatlas`
- Java target: release 11
- Built against RuneLite `latest.release` (1.13.1 today)
- Build with JDK 21: `JAVA_HOME="C:\Program Files\Microsoft\jdk-21.0.10.7-hotspot"`. Gradle 8.10
  cannot run on the default JDK 25. Always pass `--no-daemon`, and never run `gradlew --stop` or kill
  java processes: the user may have the game client open.

Research behind this spec (2026-10-04) was done against game cache 2727 (OpenRS2, 2026-09-30), the
decoded cs2 scripts, the OSRS Wiki, Skretzo's Shortest Path, the Teleport Maps / Better Teleport Menu
sources and the Plugin Hub. Confidence is marked [H]igh, [M]edium or [L]ow where it matters.

---

## 1. Hard rules (compliance; non-negotiable)

1. **The plugin never sends a game action.** It never calls `client.menuAction`, `client.runScript`,
   `createScriptEventBuilder`, `ScriptEvent.setCanSendPackets`, `setVarbit`, `setVarbitValue`,
   `setVarcIntValue`, `setVarcStrValue`, `java.awt.Robot`, `dispatchEvent`, `KeyboardFocusManager`,
   reflection (`setAccessible`, `getDeclaredField`, `getDeclaredMethod`), and never adds widget ops,
   listeners or text (`setAction`, `setOnOpListener`, `setOnKeyListener`, `setOnClickListener`,
   `setOnMouseOverListener`, `setText`, `setTextColor`). Teleport Maps and Better Teleport Menu travel
   by running script 1437 with `setCanSendPackets`; we must not, and a reviewer recently asked another
   plugin to remove exactly that (plugin-hub #17380). `ComplianceTest` scans `src/main/java` for every
   string above and fails on any hit.
2. **A map click only selects a tree.** The travel is the player's own click on the real menu row
   (menu option "Continue") or the player's own key press on the game's own key listener. The plugin
   adds no key listener and never captures keys.
3. **Menu entries the plugin adds are `MenuAction.RUNELITE`** with `onClick` callbacks that change only
   plugin state.
4. **Game widgets: hide and move only, inside the menu's own interface,** plus one resize. No other
   resize, no re-text, no recolour, no new ops.
   - **The one resize:** the selected tree's real row, while it is the Travel button in the map's
     corner, is made button-sized (`TRAVEL_W` x `TRAVEL_H`, 200x32, 4.3), exactly as Fairy Ring
     Atlas sets its real Teleport (CONFIRM) widget's size as well as its position. Only that row
     (MODERN: its TEXT and GRAPHICS children; CLASSIC: its LJ_LAYER1 child), only in `TreeMenu`;
     its size modes and size are recorded and put back exactly, by the same rules as a move. Its
     ops, text, colours and listeners are untouched. `ComplianceTest.onlyTheTravelRowIsResized`
     enforces this structurally.
   - Never unhide a component that we did not hide ourselves (the game or another plugin may have
     hidden it). Restore only what we changed, exactly (position modes, x, y; for the Travel row
     also width and height modes, width, height), and only while the widget is still the one we
     changed.
   - The movable components are listed in 4.3. Nothing else of 947 or 187 is touched. Never touch the
     key-listener layers (`MenuNew.KEYLISTENERS`, `Menu.KEYLISTENERS`) or their children: the game's
     own hotkeys must keep working in every mode.
   - "Use free space" may move the **position, never the size**, of the resizable main modal slot
     that holds the menu, exactly as Fairy Ring Atlas does (`ModalSlot`, 4.2).
5. **No network access at runtime.** All imagery and data are bundled.
6. **Image budget.** The hub bot rejects any bundled image whose decoded size (w*h*4) is about 1 MiB or
   more. Every bundled PNG is at most 256x256 and decodes to under 950,000 bytes. Keep total bundled
   resources at **7.6 MiB (7,969,177 bytes) or less** so the jar stays under the hub's 8 MiB warning.
7. **Source size.** The review bot counts only `src/main/java`, comments stripped, against a 200k-token
   budget. Keep the Java lean; `TreeDataTest` fails when `src/main/java` passes 300,000 raw bytes
   (line ends counted as one byte), as Fairy Ring Atlas's `RingDataTest` does: an early warning far
   below the bot's budget (Fairy Ring Atlas measured about 178 KB of Java as roughly 50k tokens with
   comments stripped). Generators live in `tools/` as standalone projects; the root `settings.gradle`
   must not include them.
8. **Act only on the spirit tree's menu.** Interfaces 947 and 187 are shared by many menus (skills
   necklace, obelisks, minecarts, Xeric's talisman, capes, Leagues). Everything keys off the title
   "Spirit Tree Locations" (3.3).

---

## 2. Game facts

### 2.1 Two menus, chosen by a game setting [H]

Using a spirit tree's "Travel" (or a Spiritual fairy tree's "Tree") opens a menu titled
**`Spirit Tree Locations`**, with no title graphic. Which interface depends on the player's
"Modern menu interface" setting (`VarbitID.SETTINGS_NEW_MENU_INTERFACE` = 19615; on by default only
for characters created after December 2025, so established players usually see the classic one).
Both must be supported.

| | Modern (`MODERN`) | Classic (`CLASSIC`) |
|---|---|---|
| Group | 947, `InterfaceID.MENU_NEW` / `InterfaceID.MenuNew` | 187, `InterfaceID.MENU` / `InterfaceID.Menu` |
| Setup clientscript (server-run) | 9142 `menu_new(int keys, graphic, int, int, string title, string options)` | 217 `menu(string title, string options, boolean keys)` |
| Per-entry proc | 9143 `menu_new_createentry` | 218 `menu_createentry` |
| Other procs | 9144 builds the title bar and close button | 219 lays out the scroll (and resets 187:3's position) |
| Options | one string, `|`-separated, in menu order | same |

- The decompiled argument order is the decompiler's, not proven. **Do not read the script
  arguments.** Read the title and rows from the widgets after the setup script has run (3.3).
- `ScriptPostFired(9142)` / `ScriptPostFired(217)` fire once the layout is final. `ScriptPostFired`
  of 9143/218 fire once per entry. 9142 and 217 may run again inside an open menu (`cc_deleteall`
  first): rebuild every time they run. `ScriptPreFired` of a server-run script fires before it runs,
  with its `ScriptEvent` (the injected client posts it for every top-level script; Teleport Maps and
  Better Teleport Menu rely on `ScriptPreFired(9142)`) [H, bytecode]. `WidgetLoaded(947/187)` and
  `WidgetClosed(947/187)` fire on open and close; do not rely on their order relative to the
  scripts [M].
- `WidgetClosed` also fires, with `isUnload()` false, when an open interface is only moved to
  another parent (IF_MOVESUB, as on the switch between fixed and resizable): the injected client's
  one call with unload false is followed at once by the open at the new parent [M, from the
  bytecode]. The interface's widgets stay the same objects, with our changes on them.
- Script 378 `menu_indexed` also uses 187 (numbers, no hotkeys) and calls the same proc 219. Not used
  by spirit trees, but it could build another menu in an open 187, so it counts as a classic setup
  script (3.3).

### 2.2 Modern menu, group 947 [H]

Static components (from the cache; position modes 0 = left/top, 1 = centre, 2 = right/bottom; size
modes 0 = absolute, 1 = minus):

| child | constant | id | parent | x,y | w,h | pos | size |
|---|---|---|---|---|---|---|---|
| 0 | `INFINITE` | 62062592 | the main modal slot | 0,0 | 0,0 | centre,centre | minus,minus |
| 1 | `UNIVERSE` | 62062593 | 0 | 0,0 | 512,334 | centre,centre | abs,abs (noClickThrough) |
| 2 | `FRAME` | 62062594 | 1 | 0,0 | 0,0 | left,top | minus,minus |
| 3 | `TITLE` | 62062595 | 1 | 0,6 | 12,45 | centre,top | minus,abs |
| 4 | `CONTENT_FRAME` | 62062596 | 1 | 0,6 | 12,56 | centre,bottom | minus,minus |
| 5 | `CONTENT` | 62062597 | 4 | 0,2 | 4,2 | centre,top | minus,minus |
| 6 | `KEYLISTENERS` | 62062598 | 5 | 0,0 | 1,1 | left,top | abs,abs |
| 7 | `CONTENT_SCROLL` | 62062599 | 5 | 0,0 | 0,18 | left,top | minus,minus |
| 8 | `GRAPHICS` | 62062600 | 7 | 0,0 | fill | left,top | minus,minus |
| 9 | `TEXT` | 62062601 | 7 | 0,0 | fill | left,top | minus,minus |
| 10 | `SCROLLBAR` | 62062602 | 5 | 0,2 | 0,16 | centre,bottom | minus,abs |

- INFINITE fills the 512x334 main modal slot; UNIVERSE is centred in it.
- 9142 resets UNIVERSE to 512x334, lays the entries out, then shrinks UNIVERSE to fit. With every
  option on one line and two balanced columns: entry width `w` = widest `parawidth("W: "+opt)` + 20
  (min with 248), entries `w` x 20, column 0 gets `ceil(N/2)` rows, UNIVERSE =
  `(16 + 2w) x (58 + rows*20)`. For the spirit tree today: 15 entries (14 destinations + Cancel),
  `w` = 161, 8/7 rows, **UNIVERSE 338x218**, CONTENT at UNIVERSE (8,52) 322x160, entry i at
  UNIVERSE `(8 + (i/8)*161, 52 + (i%8)*20)`; UNIVERSE's origin in the slot is (87,58). Verified
  against the Dec 2025 news image (340x220 including a 1 px margin) and user screenshots.
- **Per entry i** (subid = 0-based index), created by 9143:
  - `GRAPHICS` child i: black filled rectangle, `w` x 20 (absolute size modes), transparency
    alternating 200/220, 240 on hover.
  - `TEXT` child i: `w` x 20 (absolute size modes), p12_full, colour 0xff981f (white on hover),
    centred both ways (`settextalign` centre/centre, line height 14); text
    `<col=ffffff>K</col>: ` + option, K the key.
  - Nothing the game runs while the menu is open depends on or resets an entry's size: the
    hover scripts only swap the colour (`cc_colour_swapper`) and the transparency
    (`cc_settrans`), and a key press (`chatbox_keyinput_matched`) only re-texts the row
    "Please wait...", recolours it and replaces its key listener [H, cs2]. Only 9142 itself
    lays the entries out (and it runs again only as a rebuild, 3.3).
  - `KEYLISTENERS` child i: rectangle with the onKey listener. **Never touched.**
- **Title bar** (dynamic children of `TITLE`, from 9144): 0-1 thinbox rectangles, 2 title graphic, 3
  title text (b12_full, the title), 4 **close button** (sprite 535, hover 536, 26x23, 12 px from
  TITLE's right end, vertically centred: UNIVERSE-relative (W-44, 17), its right end at W-18; op
  "Close", script 29). Find them by type and action rather than by index (3.3).

### 2.3 Classic menu, group 187 [H]

187:0, 187:2, 187:3 and 187:4 are roots in the slot. **187:1 (`KEYLISTENERS`) is not a root: it is a
static child of 187:0** at (0,0) 1x1 (cache: parent 187:0), so hiding 187:0 hides the hotkeys too.
After proc 219 (called from 217 with 33):

| child | constant | id | parent | layout after 219 | contents |
|---|---|---|---|---|---|
| 0 | `LJ_LAYER2` | 12255232 | the slot | (0,0) 512x334 | the static child 187:1; dynamic children: 0 the parchment scroll model (model 26397), 1 the title text (quill_oblique_large, 0x322805) |
| 1 | `KEYLISTENERS` | 12255233 | 187:0 | (0,0) 1x1 | one key-listener rectangle per entry. **Never touched, and 187:0 is never hidden.** |
| 2 | `LJ_SCROLL_BAR` | 12255234 | the slot | (441,70) 16x232 | vertical scrollbar (only when the list overflows) |
| 3 | `LJ_LAYER1` | 12255235 | the slot | (55,70) 386x232, scroll layer | the entries |
| 4 | `ROOT_GRAPHIC3` | 12255236 | the slot | (449,36) 26x23 | close button, sprite 537 (538 hover), op "Close", script 29 |

- Entries (proc 218): `LJ_LAYER1` dynamic child i is a text component, x 0 centre-anchored, y = 16*i
  (top-anchored), width minus 0 (386), height absolute 16 for one line, p12_full colour 0x322805
  (0x524825 on hover), centred both ways (`settextalign` centre/centre, line height 14); text
  `<col=735a28>K</col>: ` + option. As in 947, the hover script only swaps the colour and a key
  press only re-texts and recolours the row; nothing resets its size while the menu is open
  [H, cs2].
- Better Teleport Menu reads a classic row's height only while 218 runs (to drop a disabled row's
  height from the list), and its "Expand scroll menu" sizes 187:3 from its scroll height, which a
  row's height does not change; neither depends on a row's size later [H, its source].
- 15 entries take 240 px of a 232 px list: the list scrolls by 8 px. When it overflows, 217 calls
  proc 31 `scrollbar_vertical`, which builds the scrollbar's children and sets a mouse-wheel handler
  on 187:3 itself (`if_setonscrollwheel`), so the wheel over the moved row would scroll it out of
  its cell (4.4) [H, cs2].
- 219 deletes the dynamic children of 187:0, 187:2 and 187:3 (`cc_deleteall`; 187:0's static child
  187:1 stays, and 217 clears its rectangles itself), sets 187:0 to (0,0) 512x334 and 187:3 / 187:2
  with `if_setposition`, so a rebuild resets our move of 187:3 and replaces the parchment model; it
  hides and shows nothing [H, cs2].
- Better Teleport Menu's "Expand scroll menu" (default on) runs at `ScriptPostFired(217)` before us
  (priority 1): in resizable mode, when the list overflows, it makes the slot, 187:0, 187:2 and
  187:3 taller by the overflow (8 px today), hides the scroll model, hides the scrollbar it no
  longer needs, and draws the parchment itself after 187:0 (`drawAfterLayer`). It redoes this at
  every `ScriptPostFired(909)` (`toplevel_resize`: canvas resizes, toplevel sub changes), but gives
  up and stops drawing when the title (187:0 child 1) `isHidden()`, which follows the parents: so
  neither 187:0 nor the title is ever hidden, or List mode would come back with no parchment.

### 2.4 Rows, keys and availability [H unless marked]

- **Order today:** 1 Tree Gnome Village, 2 Gnome Stronghold, 3 Battlefield of Khazard, 4 Grand
  Exchange, 5 Feldip Hills, 6 Prifddinas, 7 Port Sarim, 8 Etceteria, 9 Brimhaven, A Hosidius, B
  Farming Guild, C "Your house (<town>)", D Poison Waste, E Laguna Aurorae, F Cancel. Match rows by
  label, never by index or key: an account missing content might not get the same list [M].
- **Keys:** 1-9, then A-Z (enum 1401/1402), for the first 35 entries, case-insensitive, only when the
  setup script's keys flag is on (always off on mobile). Pressing the key resumes that row, sets its
  text to "Please wait..." and debounces for 20 client cycles. Hotkeys live under the key-listener
  layers; hiding those, or any component that holds them (947: INFINITE, UNIVERSE, CONTENT_FRAME,
  CONTENT; 187: LJ_LAYER2), turns them off, so we never hide any of them.
- **Unavailable destinations stay listed**, with `<col=5f5f5f>` before the name (the key keeps its
  colour). A `</col>` may or may not follow: strip all tags before matching. Clicking a grey row is
  refused by the server with an explanation [M].
- **The tree you are standing at is listed** and is not greyed [M]. What picking it does is unknown.
- **Clicking a row:** the hover option is "Continue" (`MenuAction.WIDGET_CONTINUE`, param0 = subid,
  param1 = `MenuNew.TEXT` or `Menu.LJ_LAYER1`), set by the server's pause-button event; it sends
  RESUME_PAUSEBUTTON [M-H].
- **Other plugins re-text rows:** Better Teleport Menu sets each row's text to
  `preText + bind + postText + displayText` and may hide disabled classic rows. Spirit Tree Menu
  re-texts, re-fonts, moves and resizes classic rows. Row parsing must tolerate these (3.3).

### 2.5 Destinations (14) [H]

All tree tiles come from the cache's loc placements (cache 2727); arrival tiles from Shortest Path,
checked against the cache's collision data. The full dataset, with sources, is
`tools/data/spirit_trees.verified.json` (section 3.1).

| Key | Menu label | Layer | Tree centre | Kind | Needs (beyond Tree Gnome Village) |
|---|---|---|---|---|---|
| 1 | Tree Gnome Village | surface | 2544.5,3169.5 | fixed | - |
| 2 | Gnome Stronghold | surface | 2461.5,3446.5 | fixed | (The Grand Tree only to leave from it) |
| 3 | Battlefield of Khazard | surface | 2555.5,3260.5 | fixed | - |
| 4 | Grand Exchange | surface | 3184.5,3509.5 | fixed | - |
| 5 | Feldip Hills | surface | 2486.5,2850.5 | fixed | - |
| 6 | Prifddinas | prifddinas | 3274.5,6124.5 | quest | Song of the Elves |
| 7 | Port Sarim | surface | 3060,3258 | patch | a spirit tree grown in the patch |
| 8 | Etceteria | surface | 2613,3858 | patch | as above |
| 9 | Brimhaven | surface | 2802,3203 | patch | as above |
| A | Hosidius | surface | 1693,3542 | patch | as above |
| B | Farming Guild | surface | 1253,3750 | patch | as above; 85 Farming to enter the advanced tier |
| C | Your house (<town>) | poh | the house portal | house | a spirit tree (or spiritual fairy tree) built in the house garden |
| D | Poison Waste | surface | 2339,3111 | quest | The Path of Glouphrie (part way) |
| E | Laguna Aurorae | surface | 1202.5,2786.5 | quest | Pandemonium, and a first visit by boat |

Centres are in **tile-index** units, as Fairy Ring Atlas's ring tiles: the south-west tile plus
(size-1)/2, so a 2x2 tree at (3184,3509) has centre 3184.5,3509.5. The painter draws a tree at
(x + 0.5, y + 0.5), exactly as it draws a ring at its tile; distances to the player's tile use the
centre as given.

- **Every tree needs Tree Gnome Village** (a global requirement).
- **Last destination:** `VarbitID.SPIRIT_TREE_PREVIOUS` (20252, varp 5478 bits 0-6). Value N (1-14)
  is menu row N in the order above (1 Tree Gnome Village ... 14 Laguna Aurorae); 0 = never travelled.
  Source: each travel loc's conditional "Last-destination" op text in the cache. Each destination
  carries its `previousValue`.
- **The house:** `VarbitID.POH_HOUSE_LOCATION` (2187), cache enum 252: 1 Rimmington, 2 Taverley,
  3 Pollnivneach, 4 Rellekka, 5 Brimhaven, 6 Yanille, 8 Hosidius, 9 Prifddinas (prifddinas layer),
  13 Aldarin. The portal tiles are in the dataset (`housePortals`). The menu row reads
  "Your house (<town>)": match the "Your house" prefix.
- **Prifddinas** lies outside the surface map (world map file 29, map squares rx 49-52, ry 93-96,
  x 3136-3392, y 5952-6208). On the surface world map, Prifddinas is linked at about (2240,3328) in
  Tirannwn (map-link icons, sprite 1535); that is where the surface shows it.

### 2.6 Layout facts (shared with Fairy Ring Atlas)

- **Fixed mode:** the main modal slot (`Toplevel.MAINMODAL`, 35913769) is the 512x334 viewport at
  canvas (4,4).
- **Resizable:** the slot (`ToplevelOsrsStretch.MAINMODAL` 10551312 / `ToplevelPreEoc.MAINMODAL`
  10747920) is 512x334, centred in `HUD_CONTAINER_FRONT` (the canvas minus 250x165 in classic layout,
  215x165 in modern). Always use live `Widget.getBounds()`.
- **The game clips** every component to its parents' bounds for drawing and for the mouse, so a
  component moved partly outside its parent (or outside the slot) shows and clicks only inside.
  Fairy Ring Atlas relied on this in game (2026-10-03).

---

## 3. Bundled resources and data at runtime

All resources live under `src/main/resources/com/spirittreeatlas/`.

### 3.1 `trees.json` (written by `tools/build_trees.py`)

Source: `tools/data/spirit_trees.verified.json` (the reconciled research dataset, copied from the
research with the challenge fixes applied, see `tools/data/README.md`). The build script validates
and writes, deterministically:

```json
{
  "_about": "...",
  "title": "Spirit Tree Locations",
  "unavailableColour": "5f5f5f",
  "global": { "requirements": ["Quest: Tree Gnome Village (every spirit tree)"] },
  "trees": [
    {
      "id": "GRAND_EXCHANGE",           // UPPER_SNAKE, stable
      "menuLabel": "Grand Exchange",     // matched against the row text (3.3)
      "match": "exact",                  // exact | prefix (prefix only for the house)
      "previousValue": 4,                // SPIRIT_TREE_PREVIOUS value; 0 when none
      "name": "Grand Exchange",          // card title
      "label": "Grand Exchange",         // map label beside the marker (short)
      "area": "Varrock, north-west of the city (Misthalin)",
      "kind": "fixed",                   // fixed | patch | quest | house
      "layer": "surface",                // surface | prifddinas | poh
      "x": 3184.5, "y": 3509.5, "plane": 0,   // tree centre in tile-index units (2.5); drawn at +0.5
      "arrival": [3185, 3508, 0],        // where travel lands; informational
      "lockedHint": "Grow a spirit tree here first",  // <= 32 chars; why a grey row is grey
      "requirements": ["Skill: ...", "Quest: ..."],   // lines start Quest:/Skill:/Unlock:/Item:/Diary:
      "poi": ["..."], "notes": ["..."], "danger": ["..."]
    }
  ],
  "housePortals": [ { "value": 1, "town": "Rimmington", "x": 2951.5, "y": 3224, "plane": 0, "layer": "surface" } ],
  "portals": [ { "layer": "prifddinas", "x": 2240, "y": 3328, "label": "Prifddinas", "description": "..." } ]
}
```

- Text is printable ASCII only (the RuneScape bitmap fonts have nothing else). `label` at most 24
  characters, `lockedHint` at most 32.
- The house entry has `layer: "poh"` and no coordinates; the plugin places it at
  `housePortals[value]` (3.4).
- `portals` lists where an off-surface layer is entered on the surface map. Only Prifddinas today.

### 3.2 Map tiles and `map/index.json` (written by `tools/mapgen`)

Exactly Fairy Ring Atlas's contract (its DESIGN 3.2): `map/<z>/<tx>_<ty>.png`, 256x256 tiles, ppt
`2^z`, image row 0 north; z=2 (4 ppt) full detail with no icons or labels baked in, and z=-1 (0.5 ppt)
overview; solid tiles not shipped (`solid` per level); `index.json` holds `cache`, `tileSize`,
`levels`, `tiles`, `solid`, `layers`, `labels` (`t`, `x`, `y`, `s`, optional `c`, `layer`) and
`icons` (`[x, y, sprite]`).

- **Layers:** `surface` "Gielinor", bounds [1016,2104]-[3976,4168] (the world map's whole surface,
  as Fairy Ring Atlas), and `prifddinas` "Prifddinas", the bounding box of the city's content plus 8
  tiles inside rx 49-52, ry 93-96, background black. No `poh` entry (card only).
- **Icons:** keep every map-function icon (including fairy rings, which Fairy Ring Atlas dropped),
  except the transportation icon (sprite 1504) within 6 tiles (per axis) of each spirit tree centre
  (Tree Gnome Village's sits 5.5 tiles from its tree) and the farming patch icon (sprite 1501) within
  3 tiles of each patch tree: our markers sit there.
- **Surface z=2 and z=-1 tiles** must come out byte-identical to Fairy Ring Atlas's (same cache, same
  renderer); the generator checks this when the FRA checkout is present. Surface z=-1 tiles that
  contain off-surface pixels from FRA's layers are repainted with the surface background where they
  lie outside every layer.

### 3.3 The menu at runtime (`TreeMenu`)

`TreeMenu` is the one place that knows the two menus. Client thread only. Its parsing and geometry
are pure static methods with unit tests.

**Recognising the menu.** On `ScriptPostFired(9142)` (MODERN) or `ScriptPostFired(217)` /
`ScriptPostFired(378)` (CLASSIC; 378 is `menu_indexed`, 2.1):
- Read the title from the widgets: MODERN, the first dynamic child of `MenuNew.TITLE` of type TEXT;
  CLASSIC, the first dynamic child of `Menu.LJ_LAYER2` of type TEXT. Strip tags and trim.
- It is the spirit tree menu when the title equals `trees.json` `title` ("Spirit Tree Locations"),
  case-insensitive. Otherwise it is not ours: if we were open on that interface, treat it as a close.
- Also check on `startUp` (the menu may already be open) and once per `GameTick` while not open
  (missed script events), by the same title test on whichever group is loaded and visible.
- **While open**, each `GameTick` checks that the menu is still mounted and visible and, unless we
  are stepping aside (4.10), that its title still passes: a menu built in the same interface by a
  script we do not hook is a close within a tick. (Teleport Maps deletes the classic title when it
  builds its map, so the title is not checked while stepping aside.)
- **Rebuilds.** On `ScriptPreFired` of the open style's setup script (9142; 217 or 378), everything
  we changed is put back (4.3) before the script runs, so it works on the game's own state and
  whatever is hidden or moved during the rebuild (by the script, or by another plugin at its
  `ScriptPostFired`) is never recorded as ours. `ScriptPostFired` then re-reads the rows, re-checks
  stepping aside and re-applies Map mode with fresh records.
- **Interface moves.** A `WidgetClosed` with `isUnload()` false (2.1) is not a close.

**Reading the rows.** MODERN: the dynamic children of `MenuNew.TEXT`; CLASSIC: the dynamic children
of `Menu.LJ_LAYER1`. A child hidden by the game or another plugin (self-hidden, and not by us) is
not listed: Better Teleport Menu hides disabled rows. For each other child of type TEXT with
non-empty text, `parseRow(rawText)`:
1. `grey` = the raw text contains `<col=5f5f5f>` (case-insensitive) anywhere after the first `: `
   (anywhere when it has no `: `, as Better Teleport Menu writes an unbound row).
2. Strip all tags (`Text.removeTags`), collapse whitespace, trim.
3. If the result is "Please wait..." the row is mid-teleport: keep the mapping it had.
4. `key` = the text before the first `": "` when that part is 1-6 characters; `label` = the rest
   (the whole text when there is no such prefix).
5. Match `label` against the trees: case-insensitive, whitespace collapsed; `exact` trees must equal
   it, `prefix` trees must start it. Failing that, the tree whose `menuLabel` occurs in the whole
   stripped text, longest label first (covers Better Teleport Menu's forms). "Cancel" and unknown
   rows map to no tree.

The result is a list of `Row {index, key, label, grey, treeId}`. The rows are read at open, at
every rebuild and again on every `GameTick` while open: Better Teleport Menu can rebind a row's key
(re-text it) while the menu is open, and the key badges must show what the rows show. The tick read
is cheap: the raw texts are compared with the last read and parsed only when they differ, and
"Please wait..." keeps the row's mapping (step 3). Row widgets are always fetched live by index
(`getChild(index)`), never cached across events.

**Who is open.** `TreeMenu.getStyle()` is MODERN, CLASSIC or null. `TreeMenu.slot()` is the main
modal slot widget that holds the menu (the parent of `MenuNew.INFINITE`, or of `Menu.LJ_LAYER2`);
its bounds are the "menu rect" (512x334) that the map layout grows from, as the dials' rect did in
Fairy Ring Atlas.

### 3.4 Tree state at runtime (`TreeRepository`)

Loads `trees.json` and `map/index.json` with the injected Gson (the bundled RuneLite Gson is old: no
`JsonParser.parseString`). Per open of the menu, on the client thread:
- **available / grey / absent** per tree, from `TreeMenu`'s rows: a tree with a non-grey row is
  available; with a grey row, unavailable; with no row (or only a row someone else hid), absent
  (unknown). Updated whenever the rows are read (3.3).
- **here:** the tree whose centre is within 6 tiles (Chebyshev, same plane) of the player when the
  menu opened, never the house; otherwise null. **The house is never "here"**: in a player-owned
  house (an instance) the client cannot tell whose house it is. In a friend's house (a house party,
  using the host's spirit tree or spiritual fairy tree) the "Your house (<town>)" row still means
  the player's own house, a real destination, so marking it "here" would put the "You" pin on the
  wrong house and hide its Travel row. In the player's own house, the row shows as Travel too and
  clicking it only asks the server, which decides. So with `FIT_ALL`, using the tree in a house
  in Prifddinas opens the surface overview, not the Prifddinas layer (4.7).
- **last:** the tree whose `previousValue` equals `SPIRIT_TREE_PREVIOUS`, when non-zero.
- **The house:** `placeHouse(value)` puts the `house` tree at `housePortals[value]` (and its layer);
  unknown values leave it unplaced (not drawn). Read at start-up when logged in, on every open, and
  on `VarbitChanged` of `POH_HOUSE_LOCATION`. Its card title and label name the town:
  "Your house (Rimmington)".
- **Surface stand-ins for other layers.** Every tree on a non-surface layer (Prifddinas, and the house
  when it is in Prifddinas) is also drawn on the surface at its layer's `portals` point, so the
  overview shows every destination. When two share a point, they are spread 18 px apart on screen.

---

## 4. Plugin behaviour (UX)

### 4.1 Modes

When the spirit tree menu opens, the overlay shows in **Map mode** (config `openInMapMode`, default
on). The overlay draws only while the menu is open.

- **Map mode.** The map covers the menu and grows from the slot's bottom-right corner (4.2). The
  menu's widgets are changed as in 4.3 so that only two real components show, through **holes** in
  the map: the **close button** (always) and the **Travel row** (the selected tree's real row, only
  while it is usable). Everything else of the menu is under the map.
- **List mode.** Every change is put back: the plain menu shows and works. The overlay draws only a
  small floating **"Map"** button just above the menu's top-left corner (MODERN: UNIVERSE's top-left;
  CLASSIC: the scroll's top-left, slot (55,37)), which switches back. The top bar's **List** button
  switches to it.
- **Stepping aside** (4.10): when another plugin is drawing its own spirit tree map or rearranging the
  rows, nothing is changed and the overlay draws only a one-line notice above the menu. Map or List
  mode is kept underneath and comes back if stepping aside ends while the menu is open.

A reopen within 3 ticks keeps the mode, selection and view; otherwise the selection is cleared and
the initial view (4.7) applies.

### 4.2 Map layout (`MapLayout`) and Use free space (`ModalSlot`)

Unchanged from Fairy Ring Atlas, with the dials' rect replaced by the menu rect (the slot, 512x334):
- **Fixed mode:** the map rect equals the slot (the viewport).
- **Resizable:** the usable area is the canvas inset by 6 px, cut clear of the chat container (even
  when collapsed), the side panel and its tab bars (each obstacle cut from the side that clears it
  and keeps most room, never past the menu rect's edge; obstacles not drawn yet, at -1,-1, are left
  out). The map grows up and left from the menu rect's bottom-right corner, `W x H` = min(config
  max, room), never smaller than the menu rect.
- **Use free space** (`useFreeSpace`, default on; resizable only): `ModalSlot` moves the slot's
  position (never its size) so its bottom-right corner sits at the bottom-right of the free space
  (`MapLayout.slotCorner`), with every guard of Fairy Ring Atlas: only the two resizable MAINMODALs;
  only from the pristine state (both position modes centre, offsets 0, size modes absolute, 512x334
  defined and laid out) **with the menu's root filling it exactly** (MODERN: `MenuNew.INFINITE` laid
  out at 0,0 512x334; CLASSIC: `Menu.LJ_LAYER2` at 0,0 512x334); anchored ABSOLUTE_RIGHT /
  ABSOLUTE_BOTTOM; written again or restored only while its fields still hold what we wrote; wait for
  the HUD to be laid out after a canvas resize; recompute on `GameTick`, config changes and
  `ResizeableChanged`. Restore triggers: List mode, stepping aside, the menu closing (`WidgetClosed`
  with unload, the per-tick check, a non-spirit-tree menu on the same interface),
  `GameStateChanged` to anything but LOGGED_IN/LOADING/CONNECTION_LOST, shutdown, the menu's parent
  no longer being the held slot (checked at once on a `WidgetLoaded` of any group while held, the
  menu's own included: the fixed/resizable switch moves the menu into the other toplevel's slot),
  the option turned off, no worthwhile corner. Better Teleport Menu may resize the slot for the
  classic menu (2.3); the pristine guard then leaves it alone.

### 4.3 Widget changes in Map mode (`TreeMenu.apply` / `restore`)

The aim is Fairy Ring Atlas's corner: the **Travel** row in the map's bottom-right corner with the
**close** button just above it. The slot clips everything, so a layer moved partly outside the slot
shows only its part inside (2.6). All targets are computed from live layout values
(`getRelativeX/Y`, `getWidth/Height`, `getScrollX/Y`) after the setup script ran, in slot
coordinates (`slotW` x `slotH`, 512x334). Margins: `RIGHT = 8`, `BOTTOM = 6`. The **Travel
button** is `TRAVEL_W` x `TRAVEL_H` = 200x32 (the game's rows are 161x20 and 386x16): the shown row
is resized to it (hard rule 4's one resize), and both menus give the same corner in a 512x334 slot:
the **cell** at slot (304,296) 200x32, ending `RIGHT`/`BOTTOM` from the slot's corner, and the
**close button** at slot (478,261) 26x23, its right end over the cell's and `CLOSE_GAP` = 12 px
above it, as Fairy Ring Atlas's Teleport (169x36) and close button.

**MODERN (947):**
1. Let `cs` be CONTENT_SCROLL's position relative to UNIVERSE (sum of the relative positions of
   CONTENT_SCROLL, CONTENT and CONTENT_FRAME) and `csW x csH` its size. The button is
   `cw x ch` = `min(csW, TRAVEL_W) x min(csH, TRAVEL_H)`. Its right end, UNIVERSE-relative, is
   `right` = the close button's right end (TITLE's relative x + width - 12, i.e. W-18), kept
   between `cs.x + cw` and `cs.x + csW`. The **row cell** is the top of the scroll area at that
   right end: CONTENT_SCROLL-relative `(scrollX + right - cs.x - cw, scrollY)`, i.e.
   UNIVERSE-relative `(right - cw, cs.y)`. Today: `right` = 320 (CONTENT_SCROLL ends at 330), the
   row at CONTENT_SCROLL (112, 0).
2. Move UNIVERSE (record its position modes and x/y) to position modes ABSOLUTE_LEFT/ABSOLUTE_TOP,
   `x = slotW - RIGHT - right`, `y = slotH - BOTTOM - (cs.y + ch)`: today (184, 244). UNIVERSE now
   hangs below and 10 px past the right of the slot: only its title strip and the button show, in
   the slot's bottom-right corner, and the close button (UNIVERSE (W-44,17)) sits just above the
   button's right end. The gap between them, 12 px, is the title bar's (close bottom at UNIVERSE
   y 40, CONTENT_SCROLL top at 52), so the two never overlap.
3. Hide every row's TEXT child and GRAPHICS child (when not already hidden), except the selected
   tree's when it is to be shown (4.4); move that one's TEXT and GRAPHICS child to the row cell and
   resize both to `cw x ch` (record their position modes and x/y, and their size modes and size;
   set ABSOLUTE_LEFT/TOP and the cell's x/y, absolute size modes and `cw x ch`). The text stays
   centred both ways in the bigger rectangle, and the GRAPHICS child is its black backing (and
   hover highlight) at the same size.
4. Leave FRAME, TITLE and its children, CONTENT_FRAME, SCROLLBAR and KEYLISTENERS alone (they are
   under the map, or hang outside the slot). UNIVERSE is moved, never hidden: it, CONTENT_FRAME and
   CONTENT hold KEYLISTENERS.

**CLASSIC (187):**
1. Hide `LJ_LAYER2`'s parchment model (its dynamic child of type MODEL, index 0 today, recorded as a
   dynamic child `(LJ_LAYER2, index)`, so a rebuild drops the record) and `LJ_SCROLL_BAR` (a root),
   each only when not already hidden (Better Teleport Menu may have hidden the model; then it is not
   ours and is never shown again). **Never `LJ_LAYER2` itself**: it holds `Menu.KEYLISTENERS` (2.3).
   The title (child 1) stays visible under the map, so Better Teleport Menu's title check passes.
2. Let `rw` = LJ_LAYER1's width (386, the rows' width), `cw` = min(`rw`, `TRAVEL_W`) the visible
   cell width and `ch` = min(LJ_LAYER1's height, `TRAVEL_H`) the button's height. Move LJ_LAYER1
   (record its modes and x/y) to ABSOLUTE_LEFT/TOP, `x = slotW - RIGHT - cw - (rw - cw)/2`,
   `y = slotH - BOTTOM - ch`: today (211, 296). It hangs below the slot.
3. Hide every row except the selected tree's when shown; move that row to `y = LJ_LAYER1.scrollY`
   (keep its x and x mode; record its y and y mode; y mode ABSOLUTE_TOP) and make it `ch` tall
   (record its size modes and size; height mode absolute; its width mode, minus, and width, 0, are
   kept, so it stays 386 wide). Its text is centred both ways, so it shows in the middle `cw` px of
   the row, half way down: that is the row cell, slot (304, 296) 200x32.
4. Move the close button `ROOT_GRAPHIC3` (record) to ABSOLUTE_LEFT/TOP
   `(slotW - RIGHT - 26, slotH - BOTTOM - ch - CLOSE_GAP - 23)`: today (478, 261), as in MODERN.

**Both:** the row cell's canvas rect is the slot's bounds plus the cell's slot-relative rect,
intersected with the row widget's live bounds when it is shown. The close rect is the close button's
live bounds. Only the shown row is ever resized: a row that stops being the Travel row gets its
own place and size back before it is hidden.

**Re-apply** after every `ScriptPostFired` of the setup script (the rows are new: any records of
dynamic children left are dropped; static components keep theirs), on each `GameTick`, when the
selection changes, when Map mode comes back and when stepping aside ends. `apply` writes only what
differs. A move is recorded again whenever the widget no longer holds what we wrote (the game laid
it out afresh), so that new place is what a restore puts back.
**Restore** (exactly what we recorded, only while each widget is the live widget for its id and,
for hidden ones, still self-hidden; a move only while the widget still holds the position we
wrote, a resize only while it still holds the size we wrote, each judged apart; never unhide
anything we did not hide) at `ScriptPreFired` of the open style's setup script (3.3), on
List mode, close (including the per-tick title test failing and a non-spirit-tree menu on the same
interface), logout and hop (whatever is still live), stepping aside and shutdown (on the client
thread, after the overlays are removed).

**Backdrop.** The real row has no solid background of its own once the parchment (classic) is
hidden or the frame (modern) hangs away. A second overlay, `RowBackdrop` (`OverlayLayer.UNDER_WIDGETS`,
so the game draws the real row text over it), fills the Travel hole while it shows: CLASSIC parchment
`#D9C9A0` with a 1 px `#5A4A2A` border; MODERN black at alpha 200 with a 1 px `#3E3529` border. It
draws nothing else and takes no input.

### 4.4 Holes, the Travel slot and the stand-in

- **Holes** (the overlay draws nothing there and `AtlasInput` consumes no press there): the close
  rect always; the Travel row cell only while `Scene.rowShown()`. Each hole gets Fairy Ring Atlas's
  thin frame and drop shadow; the frames set their own stroke.
- **The mouse wheel never reaches the game anywhere on the map in Map mode**, holes included and
  while a right-click menu is open: where the map has input it zooms, elsewhere on the map (the
  holes, an open menu) it is consumed and does nothing. The classic list scrolls (2.3): the wheel
  over the moved row, or over the parts of the hanging list the map covers, would scroll the row
  up to 8 px out of its cell until the next tick's re-apply, leaving the hole and the backdrop
  behind. Consuming the event only keeps it from the game and sends nothing; re-applying on every
  scroll instead would still show the jump and needs a per-frame check. In List mode, and outside
  the map, the wheel is the game's.
- **`rowShown()`:** a tree is selected, it has a row in the current menu, the row is not grey, it is
  not `here`, and the row text still maps to the selected tree (re-checked every frame from the live
  widget; "Please wait..." keeps the old mapping).
- **Stand-in.** Otherwise the overlay draws a disabled stand-in over the row cell (the same 200x32
  as the button), consumes its input and says (RuneScape small font, centred):
  - nothing selected: one line, "Pick a tree on the map" (grey);
  - a tree selected: two lines, the tree's label (white; the house names its town) over why it
    cannot travel:
    - its row is grey: a padlock and `<lockedHint>` (amber);
    - it is `here`: "You are here" (grey);
    - it has no row: "Not in this tree's list" (grey).

  A cell too short for two lines (a scroll area under 26 px) shows only the reason. Every label,
  hint and fixed line of today's data fits the 200 px cell (`PainterTest.standInLinesFitTheCell`);
  the stand-in may still grow up to 24 px left of the cell over the map for a longer line
  (`Scene.standInRect`, which labels and the card keep clear of); "..." only beyond that.
- **Caption.** A small "Travel" caption sits on the map just above the cell's left end, left of the
  close button, so the corner reads as a button pair even while the stand-in shows. Where that
  would hide a marker (the fixed-mode overview has Feldip Hills over the 200 px cell's left end), it
  slides right along the cell's top, 8 px at a time, to the first place clear of every marker,
  never under the close button (`ChromePainter.captionRect`; `PainterTest.theFixedModeCardCoversNoMarker`).
- **Backdrop.** `RowBackdrop` fills the Travel hole as drawn, so it covers the bigger button
  without change.
- When the Travel row shows, its frame pulses gently (as Fairy Ring Atlas's Teleport when ready).

### 4.5 Selection flow

- Hovering a marker shows its card. The left-click menu entry is `Select` with target the tree's
  label; right-click adds `Zoom to` and (when a selection exists) `Clear selection`; a surface
  stand-in for Prifddinas also has `Open Prifddinas map`.
- **Selecting** (left-click on a marker) sets `selected = id` and re-applies 4.3 at once, so the real
  row appears in the corner in the same frame where possible. The card's next-step line says:
  - usable: "Click Travel, or press K." (K the row's key; without a key, "Click Travel.");
  - grey: "Not available yet: <lockedHint>." with the requirements in amber;
  - here: "You are at this tree.";
  - no row: "This tree is not in the list here."
- The player then clicks the real row (game "Continue") or presses its key. The menu closes and the
  teleport happens; nothing else is ours to do.
- **Deselecting:** right-click `Clear selection` (on empty map it sits below Cancel, because a left
  press there pans), the top bar's `Clear`, or selecting another tree.
- The game's hotkeys work in every mode, so a player can press a marker's key without clicking.

### 4.6 Chrome

- **Frame:** 2 px dark border `#1e1a14`, 1 px inner highlight, soft outer shadow.
- **Top bar** (about 22 px, translucent dark, inside the top edge): breadcrumb "Gielinor" or
  "Gielinor > Prifddinas" (vector triangle), the last trip ("Last: Grand Exchange") when known, and
  buttons on the right: `-`, `+`, `Fit`, `List`, and `Clear` while there is a selection.
- **Back button:** on the Prifddinas map, a large orange "Back to Gielinor" button at the top-left
  under the bar, right of the quick-select panel.
- **Quick-select panel** (from the user's in-game feedback, 2026-10-04: "a quick select menu on the
  left would be nice"). Fairy Ring Atlas's left panel, with one list:
  - Inside the map's left edge, 6 px in, below the top bar; `ChromePainter.PANEL_W` (150 px) wide,
    wider when the longest row (label plus its "You" tag or last-trip badge) needs it, but never past
    30% of the map (`panelWidth`). Fairy Ring Atlas's look: the same dark translucent fill and bronze
    rim, a header row with "Destinations" (bold, orange) and a small hide button with a left-pointing
    triangle. As tall as its rows need (`Scene.panel`).
  - **One row per destination in menu order** (`TreeRepository.menuOrder`: the live rows' order;
    trees the menu does not list follow, in `trees.json` order), 18 px tall, 1 px apart: the row's
    key badge (the live row's key; blank when it has none), a 6.5 px copy of the tree's marker glyph
    in its state (available, locked with its padlock, not listed hollow), and the tree's `label` (the
    house names its town), cut with "..." only when it does not fit. The selected row is filled dark
    orange with a `selectedColor` rim and label; the hovered row is lighter with a white label; locked
    rows have dimmer text, not-listed rows dimmer still. The tree you are at gets a small red "You"
    tag and the last trip the return-arrow badge, at the row's right end.
  - **Closed**, it is a 20 px tab (`TAB_W`) at the same top-left spot with a right-pointing triangle
    and "Destinations" written down it; clicking the tab or the header's triangle toggles it.
    Open by default; on maps narrower than 700 px (fixed mode) it starts closed until the player
    opens it there (Fairy Ring Atlas's rule for its Groups panel). The player's choice holds at any
    size for the session and is saved in the hidden key `quickSelectOpen` ("false" once closed).
  - **Off** with the `quickSelect` setting: neither panel nor tab.
  - **Hits:** `PANEL` for the body (absorbs presses, never pans; not actionable), `ROW` per visible
    row (tree set, option "Select", target the label), and the `TOGGLE_PANEL` button (header
    triangle "Hide", or the whole tab "Show", target "Destinations").
  - **Overflow:** rows outside the panel are clipped, with a small chevron at the top or bottom while
    there is more; the wheel scrolls them (4.8). The painter reports `panelScrollMax`.
  - **Keeping clear:** the panel stops above the holes, the Travel cell, its caption and the stand-in
    (`Scene.standInRect`) when any of them, grown by 8 px for their frames, is in its column (with
    every real layout they are not: the corner is on the right). Fits keep clear of the open panel or
    the tab (`SpiritTreeAtlasPlugin.chromeInsets`); the card sits beside the open panel, or below the
    tab (which is one of its blockers); the panel is a `LabelPlacer` obstacle, and markers under it
    get no label. Markers under it still draw (beneath the translucent fill) but take no input there.
- **Info card** for the hovered tree, else the selected one; when neither, a one-line hint
  ("Click a tree to travel there"). Contents: name (the house names its town) and area; status line
  (Available / Locked - <lockedHint> in red / You are here / Not in the list), with the last trip
  shown as the marker's return-arrow badge and "last trip"; the title leads with the tree's marker
  glyph; the hint card's legend shows the Available / Locked / Not listed glyphs; the next-step line for the selected tree (4.5); requirements (amber while locked), dangers
  (red), "Nearby" POIs and notes. Compact by default (name, area, status, next step, first locked
  requirement, two POIs); `fullDetails` shows everything. Placement exactly as Fairy Ring Atlas
  (`ChromePainter.place`: bottom-left first, keeping clear of the holes, the Travel cell and its
  caption, the top bar, the quick-select panel or tab and the tree it describes); about 45% of the
  map wide (at least 220 px), at most 62% of its height (48% on maps under 450 px tall); long text
  wraps; "..." when cut. While a
  right-click menu is open, the card keeps describing the tree it was opened on.

### 4.7 Markers, labels and the initial view

- **Tree marker:** a vector spirit tree glyph (a lobed canopy of four round lobes over a short trunk
  with a root flare, dark outline) in a dark disc, 16 px, up to 22 px at 16 ppt, 25% larger when
  selected. States:

  | State | Look |
  |---|---|
  | Available | canopy `availableColor` (default `#5BD45B`) |
  | Locked (grey row) | grey `#8C8C8C` canopy with a small padlock; 50% alpha when `dimLocked` |
  | Not in the list | hollow outline, dashed rim |
  | Hover | white outline |
  | Selected | `selectedColor` (`#FF981F`) ring, larger, soft pulsing halo |
  | You are here | a pin with "You", over the marker's top-left |
  | Last trip | small vector return-arrow badge |
  | Key | `keyHints` (default on): a small dark rounded square with the row's key in white at the marker's top-right |

- **Labels:** the tree's `label` beside its marker (`treeLabels`, default on), RuneScape small font,
  1 px dark outline, placed by `LabelPlacer` (8 candidates; selected and hovered first; a label that
  does not fit is hidden; none for markers under the chrome or in a hole).
- **Place labels and map icons** from `index.json`, as Fairy Ring Atlas (`placeLabels`, `mapIcons`).
- **Portal:** on the surface, the Prifddinas portal point shows the surface stand-ins (3.4) plus a
  small map-link glyph; `Zoom to` / `Open Prifddinas map` opens the Prifddinas layer fitted.
- **Initial view** (`openAt`): `FIT_ALL` (default) fits every surface marker (stand-ins included),
  clear of the chrome; `AROUND_YOU` centres on `here` (or the player) at 2 ppt; `REMEMBER` restores
  the last view of the session. With `FIT_ALL` and `here` on the Prifddinas layer, open that layer.
  (Fairy Ring Atlas opens around you; with only 14 destinations spread over the whole map, fitting
  them all is the useful start here.)

### 4.8 Navigation, input and menu ownership

Exactly Fairy Ring Atlas (its DESIGN 4.6 and 4.7), minus its panels and row dragging, plus the
quick-select panel:
- Wheel zooms about the cursor (x1.25 per notch, precise rotation, 0.125-16 ppt); left-drag on empty
  map pans; `+`/`-` zoom x1.5 about the centre, animated, compounding from a running animation;
  `Fit` fits all surface markers (or the Prifddinas layer bounds); animations ease out over 250 ms;
  the view is clamped so the layer cannot be dragged out of sight; fits (the initial view, `Fit`,
  `Zoom to`, opening a layer, a row's pan) keep clear of the top bar and of the quick-select panel or
  its tab (`chromeInsets`).
- `AtlasInput` (a `MouseAdapter` + `MouseWheelListener`, registered with the normal appending calls
  so Stretched Mode translates first) is active only while the menu is open, in Map mode, no menu is
  open, the point is inside the map rect and not in a hole (the wheel: anywhere on the map, 4.4). A
  marker press is held: release within 4 px selects, a drag pans. A button press is let through only
  when `onPostMenuSort` built the game's menu for that same hit (`menuKey`), otherwise swallowed.
  Empty-map presses start a pan. Drag and move events are never consumed; release and click are
  consumed until release.
- **Quick-select panel** (4.6). A left press on a row follows the rule for actionable chrome: it is
  let through only when `onPostMenuSort` built the game's menu for that same row (`menuKey`: kind,
  id and tree), so the game runs our "Select"; otherwise it is swallowed and a stale game entry can
  never run. A press on the panel's body is absorbed (no pan). The wheel over the panel (body or row)
  scrolls it by two rows a notch while its rows overflow (`panelScrollMax` > 0, fixed mode); otherwise
  it zooms the map as everywhere else. Right-click a row: `Select` (the left-click entry), `Zoom to`
  (when the tree is on a map) and `Clear selection` (with a selection), all `MenuAction.RUNELITE`.
  `Select` (`SpiritTreeAtlasPlugin.showTree`) selects the tree exactly as its marker does (the Travel
  row or the stand-in updates at once) and, when its marker (on the surface, a Prifddinas tree's
  stand-in) is not inside the map clear of the chrome (16 px in), animates a pan to it at the current
  zoom; a surface tree picked on the Prifddinas map brings the surface back first. Hovering a row
  shows that tree's card and the hover ring on its marker. The panel's scroll is reset on each open
  that is not a quick reopen.
- `onPostMenuSort` (menu closed): inside the map and outside the holes, remove every entry but
  CANCEL (no "Walk here" through the map, no hidden rows), then add ours. In List mode only the
  floating Map button owns its rect: its "Show Map" entry, and a left press there is let through
  only when the game's menu was built for the button (otherwise swallowed), as for buttons on the
  map. While stepping aside nothing is ours.
- While the map shows, the game's mouse-over text is turned off (`setMouseoverTextEnabled(false)`)
  and turned back on only if we turned it off.
- **Threading:** the input thread touches only volatile/atomic state; all widget and var access is
  on the client thread.

### 4.9 Rendering

Fairy Ring Atlas (its DESIGN 4.8 and the caching deviations), but for the loading: `AtlasOverlay` at
`OverlayPosition.DYNAMIC`, `OverlayLayer.ABOVE_WIDGETS`, `PRIORITY_HIGHEST` (never `ALWAYS_ON_TOP`),
drawing in canvas coordinates and returning null; an opaque base-map cache rebuilt only when the view
key or the tile generation changes, retried every 250 ms while incomplete, clipped to the layer
bounds; a cached translucent chrome layer keyed by value; pre-rendered marker and label sprites;
`TileStore` (loading, below) with stale requests dropped, a soft ~48 MB LRU that keeps the last two
views' tiles, in-memory decoding and derived levels by 2x2 area averaging; bitmap fonts with
antialiasing off and vector glyphs instead of Unicode arrows; stretched mode is a known limit. The
painters (`AtlasPainter`, `ChromePainter`, `MapRenderer`, `Ink`) never touch `Client`, so
`MapPreview` renders exactly what the overlay draws.

**Loading the tiles** (deviation 27). The default view (FIT_ALL) of a large resizable map is z=0
(0.75 ppt on a 1738x905 map), which is not bundled: each of its ~60 tiles is derived from 16 z=2
tiles, about 960 PNG decodes. Smaller maps open on z=-1 (bundled; 1100x600 is 0.46 ppt) or z=-2
(fixed mode, 0.22 ppt, derived from z=-1).
- **Workers.** `TileStore.newExecutor()`: min(3, cores - 1) daemon threads (at least one) at
  `NORM_PRIORITY - 1`, ending after 30 s idle. The store keeps its own queue: every request hands
  the executor one run, and each run takes the **newest** queued tile, so the current view comes
  first whatever order the executor keeps. A request the latest rebuild did not repeat is dropped,
  as before. The LRU and its byte count are guarded by one lock; the queue and the requests are
  concurrent collections.
- **No tile twice at once.** A tile being loaded or derived is registered; another worker that needs
  it (a direct request and a derivation reading the same source) waits for it instead of decoding it
  again. Waits always point to a finer level or another tile, so they cannot deadlock.
- **Decoding.** Only the PNG reader lookup takes the `ImageIO` class lock (as the client's own
  loaders do); each worker then decodes with its own reader, in memory.
- **Deriving.** A coarse tile reads each source once, through the image's own pixel array (no
  per-pixel `getRGB`/`setRGB`). Sources read only to derive a coarser tile are not kept: the z=1
  tiles between a z=0 overview and z=2 (four times its size) used to fill the cache and were never
  drawn. Solid 1x1 tiles are still kept (they cost nothing).
- **Coarse first.** `MapRenderer` queues the bundled z=-1 tile over each missing tile **after** the
  view's own tiles, so (newest first) the whole view is covered by z=-1 in about 40 ms before the
  slow z=0 tiles come in.
- **Ahead of the menu.** `onMenuOptionClicked`: "Travel" on a spirit tree, or "Tree" on a spiritual
  fairy tree (game object ops only; ids below, or the target "Spirit tree" / "Spiritual Fairy Tree"),
  with the menu closed and Map mode the default, queues the tiles of the view the menu will open on
  (`MapRenderer.prefetch`): the initial view (4.7) on the session's last map rect, or before the first
  open on the rect the layout would give now (fixed: 512x334; resizable: `MapLayout.compute` around
  the slot where Use free space would put it). It reads only our own resources and leaves the click
  alone. Ids (gameval `ObjectID`): the travel locs 26260, 26261, 26263, 35950, 49595, 8355, POH
  29227, 44936, 40778, and the world trees' multiloc parents 1293, 1294, 1295, 37329, 49598, 8338,
  8382, 8383, 27116, 33733 (a menu entry carries the parent's id); spiritual fairy trees 29229,
  27097, 40779. Walking to the tree gives seconds of head start; next to it, one tick (600 ms) is
  enough for the whole 1738x905 view on the bench machine.
- **Trimming.** 50 ticks after the menu closes (or after such a click) the tiles finer than z=0 and
  the map-sized buffers are released; the **overview** (z <= 0, what FIT_ALL opens on) stays for 500
  ticks (5 minutes), so the next tree of a farming run opens at once; the login screen still releases
  everything, the queued tiles included (else a logout mid-load would leave what loads after it
  cached until the menu next closes). The overview held is what the last views drew at z <= 0: 18.8 MB after a 1738x905
  open (60 z=0 tiles and the z=-1 cover), 4.5 MB at 1100x600, 3.0 MB in fixed mode; at most the
  LRU's soft cap.

**Measured** with `./gradlew bench` (`TileBench`: the real store and executor, rebuilt as the overlay
does at 50 fps, FIT_ALL; AMD Ryzen 9 9900X, 12 cores / 24 threads, JDK 21; milliseconds from the
first frame, JIT warm (the median of the rounds after the first); the very first open in a fresh JVM
in brackets):

| Map | Before: first imagery / covered / complete | After | Warm (second open) before / after |
|---|---|---|---|
| 1738x905, z=0 | 30 / 950 / 950 (131 / 1273 / 1304) | 38 / 38 / 223 (139 / 160 / 514) | 10 / 10 |
| 1100x600, z=-1 | 34 / 34 / 34 | 34 / 34 / 34 | 4 / 4 |
| 512x334, z=-2 | 32 / 48 / 48 | 31 / 31 / 31 | 1 / 1 |

A 1738x905 open decodes 959 z=2 tiles either way (and derives 60 z=0 tiles through 240 z=1 ones);
after it the cache holds 18.8 MB instead of 47.8 MB. With the Travel click one tick ahead, every size
opens complete on its first frame (10 / 4 / 1 ms, the rebuild itself). A cold 1738x905 load now
rebuilds the base map about 11 times on the client thread (about 10 ms each) instead of 42-50. In
game the old single `MIN_PRIORITY` worker also yielded to the client and everything else, which
likely stretched a 1 s load into the few seconds reported.

### 4.10 Coexistence with other plugins

- **Teleport Maps** (`com.mjhylkema.TeleportMaps.TeleportMapsPlugin`, ~70k installs) replaces this
  menu with its own map when enabled and its config `teleportmaps.showSpiritTreeMap` is not "false"
  (default true). Then **step aside**: change nothing, draw only the notice "Teleport Maps is showing
  its spirit tree map - turn that off in Teleport Maps to use Spirit Tree Atlas". Detect by plugin
  class name and `PluginManager.isPluginEnabled`. Do **not** declare `conflicts`: RuneLite would turn
  off all seven of Teleport Maps' maps. Teleport Maps acts only when the menu is built
  (`ScriptPreFired(219/9142)`: it hides the menu, then builds its map in an `invokeLater`).
- **Spirit Tree Menu** (`com.spirit.SpiritTreeMenuPlugin`) rearranges classic rows: when enabled and
  the menu is CLASSIC, step aside with "Spirit Tree Menu is rearranging this menu - turn it off to use
  Spirit Tree Atlas". It acts only on `WidgetLoaded(187)` (re-texts, re-fonts, moves and resizes the
  rows).
- **When it is checked.** At open and at every rebuild (when those plugins act), and again whenever
  a plugin starts or stops (`PluginChanged`) or a `teleportmaps` setting changes while the menu is
  open. Stepping aside **starts** at once: everything we changed is put back. It **ends** at once
  only if the other plugin did not have the menu at its last build (it was turned on and off again
  while the menu stayed open, so it never touched it); otherwise the menu may still hold its
  changes (Teleport Maps' hidden layers and leftover widgets, Spirit Tree Menu's moved rows), so the
  notice becomes "Close and reopen the spirit tree menu to use Spirit Tree Atlas" until the menu is
  opened again.
- **Better Teleport Menu** (abex): coexist. Its re-texted rows still parse (3.3), also when it
  rebinds a key while the menu is open (the rows are read each tick); the rows it hides are not
  listed and never unhidden; its "Set Hotkey" op shows on the Travel row; its "Expand scroll menu"
  slot resize (2.3) disables "Use free space" for the classic menu through the pristine guard, and
  the Map mode geometry follows the taller slot because it is computed from live values. Its own
  hotkeys replace the game's key listeners (it clears them and listens itself); we touch neither.
- Fairy Ring Atlas: unrelated interfaces; both can be enabled.

### 4.11 Config (`SpiritTreeAtlasConfig`)

Group `spirittreeatlas`. Sections: Map, Markers.

| Key | Type | Default |
|---|---|---|
| `openInMapMode` | boolean | true |
| `useFreeSpace` | boolean | true (resizable only) |
| `mapMaxWidth` | int px | 2000, range 512-2000 |
| `mapMaxHeight` | int px | 1400, range 334-1400 |
| `openAt` | enum FIT_ALL / AROUND_YOU / REMEMBER | FIT_ALL |
| `quickSelect` ("Quick select list") | boolean | true: the quick-select panel (4.6); off, neither panel nor tab |
| `placeLabels` | boolean | true |
| `mapIcons` | boolean | true |
| `fullDetails` | boolean | false |
| `treeLabels` | boolean | true |
| `keyHints` | boolean | true |
| `dimLocked` | boolean | false |
| `availableColor` | Color | `#5BD45B` |
| `selectedColor` | Color | `#FF981F` |

Hidden (not in the config panel): `quickSelectOpen`, "false" once the player closed the
quick-select panel (absent or "true": open, 4.6), as Fairy Ring Atlas saves `groupsOpen`.

---

## 5. Code structure

`src/main/java/com/spirittreeatlas/`:

| File | Responsibility | From FRA |
|---|---|---|
| `SpiritTreeAtlasPlugin` | lifecycle, events, mode, selection, view state, menu ownership, stepping aside | adapted from `SpiritTreeAtlasPlugin` |
| `SpiritTreeAtlasConfig` | config interface | adapted |
| `Tree` | one destination (data) | replaces `Ring` |
| `TreeRepository` | loads `trees.json` + `index.json`; availability, here, last, house placement, surface stand-ins | replaces `RingRepository` |
| `TreeMenu` | recognises the menu, parses rows, applies and restores 4.3, row cell / close rects | new (replaces `TravelLogController`) |
| `Layer`, `MapIndex`, `Portal` | data | kept |
| `MapView`, `MapRenderer`, `TileStore`, `LabelPlacer`, `Ink` | map engine | kept (rename only) |
| `MapLayout` | map rect from the slot rect; `slotCorner` | adapted (no travel-log obstacle) |
| `ModalSlot` | Use free space | adapted (root that must fill the slot) |
| `Scene`, `Hit` | per-frame data and hit regions | adapted |
| `AtlasPainter` | markers, labels, icons, portals | adapted |
| `ChromePainter` | frame, top bar, quick-select panel, back button, card, stand-in, caption, hole frames | adapted |
| `AtlasOverlay` | draws everything, publishes hits/holes, List-mode Map button and notices | adapted |
| `RowBackdrop` | UNDER_WIDGETS backdrop for the Travel hole | new |
| `AtlasInput` | mouse and wheel; the quick-select panel's rows and wheel | adapted (no row dragging) |

Dropped from FRA: `DialMath`, `FavouriteOrder`, `RingGroups`, `TravelLogController`, `RingMenuNames`,
`ClueHelper`, `UnlockCheck`, `Ring`, `RingRepository`, `groups.json`, `rings.json`.

**Tests** in `src/test/java/com/spirittreeatlas/`: `ComplianceTest` (the full list in rule 1),
`TreeMenuTest` (row parsing for both menus, grey rows, Better Teleport Menu forms, "Please wait...",
house prefix, Cancel; the 4.3 geometry for 15 and 12 rows, modern and classic: the 200x32 button,
the close button above its right end and clear of it, UNIVERSE / LJ_LAYER1's places; the changes and
restores against fake menus parented and sized as in the cache, sizes and size modes put back
exactly and only while they hold ours, only the Travel row ever resized, a rebuild dropping the old
rows' records, the tick re-read and rows hidden by others; in Map mode no component holding either
key-listener layer is hidden), `PluginEventsTest` (the
plugin's event handling against the fake menus: open on the title only, rebuilds put back first,
another menu on the same interface, a script we do not hook, a missed script, reopen within 3
ticks, interface moves, logout and hop, stepping aside on and off while open, key rebinds, the
wheel on the map and List mode's Map button; the quick-select panel: a row press with and without
a menu built for it, the panel's body absorbing a press, the wheel over the panel scrolling only
while its rows overflow, a row's Select panning only when the marker or stand-in is out of view and
bringing the surface back, the panel closed on narrow maps and the player's choice kept and saved,
and the `quickSelect` default), `PainterTest` (holes untouched, markers and buttons, the stand-in,
the fixed-mode card covering no marker; the quick-select panel: rows in the live menu order with
unlisted trees last, every row state drawn differently, the tab and the setting off, no overlap with
the holes, cell, caption or stand-in at 512x334 and 1738x905 for every selection in both styles, and
stopping above them in its column, the card and labels clear of it, scrolling), `MapViewTest`,
`LabelPlacerTest`, `ModalSlotTest`, `TileStoreTest`, `TreeDataTest` (the real `trees.json` and
`index.json`: unique ids and labels, every tree inside its layer, previous values 1-14 unique, house
portals 1-6, 8, 9, 13, ASCII and length limits, tiles on disk equal the index, every image at most
256x256 and under 950,000 bytes decoded, resources at most 7.6 MiB, layers in `index.json`, source
size), `EventBusRegistrationTest`, `MapPreviewTest`, and the launcher `SpiritTreeAtlasPluginTest`.
`TileStoreTest` also covers the loading of 4.9 (newest first, stale requests dropped, no tile
decoded twice at once, a consistent cache under many workers, sources not kept, the trims, the
coarse cover first, the prefetch), and `PrewarmTest` the Travel click and the trim clock against
the fake menus. `TileBench` (`gradlew bench`, `-PbenchArgs="rounds prewarmMs"`) times an in-game
open (4.9).

**Previews** (`gradlew preview`, `MapPreview` into `build/preview/`). Maps 700 px and wider show the
quick-select panel open, the fixed-mode (512x334) shots its tab, as on a first open. Full fit, Grand Exchange at
4 ppt, 16 ppt, the Prifddinas layer, fixed mode 512x334 with the modern corner (Travel shown), fixed
mode with the classic corner (a locked tree's two-line stand-in), fixed mode with the classic
Travel row shown (`11-fixed-classic-travel`), a locked tree's card, a full-details card
(`12-full-card`), every marker state at 1/4/8/16 ppt and every stand-in and the shown row in both
menus' 200x32 cells (`13-marker-states`), an
alignment sheet of every tree at 8 ppt, the free-space size (1738x905), and `icon.png` (the hub
icon, 48x72: a large selected marker over Varrock at 2 ppt), which the preview writes to the repo
root, and `14-fixed-quick-select`: fixed mode with the panel opened, the pointer over Hosidius's
row (its card and its marker's hover ring), Grand Exchange selected and the "You" tag on the Gnome
Stronghold. The previews draw the game's own row and close button in the holes, as they look in game.

---

## 6. Generators (`tools/`; never shipped)

- **`tools/data/spirit_trees.verified.json`:** the research dataset with the challenge fixes applied;
  `tools/data/README.md` lists the sources and every fix.
- **`tools/build_trees.py`:** validates (ids, labels, ASCII, lengths, layers, previous values, house
  portals, requirement prefixes) and writes `trees.json` deterministically.
- **`tools/mapgen/`:** Fairy Ring Atlas's generator, ported: layers `surface` and `prifddinas`; reads
  the spirit tree dataset; icon rule of 3.2; regenerates every tile and `index.json`; checks the
  surface tiles against FRA's; reports the budget. The OSRS cache is git-ignored under
  `tools/mapgen/cache/` (a copy of FRA's, OpenRS2 2727).

---

## 7. Hub packaging

- `LICENSE` BSD-2 (Dewdul). `icon.png` 48x72 at the repo root.
- `runelite-plugin.properties`: displayName "Spirit Tree Atlas", author Dewdul, description "Turns the
  spirit tree menu into a high-resolution, zoomable map of every destination: click a tree, then
  click Travel or press its key", tags `spirit,tree,map,teleport,travel,transport,gnome`, plugins
  `com.spirittreeatlas.SpiritTreeAtlasPlugin`.
- `README.md`: features, how to use, why a map click can't travel by itself, both menu styles,
  settings, coexistence (Teleport Maps, Spirit Tree Menu, Better Teleport Menu), stretched mode, data
  provenance (cache 2727 via RuneLite's `MapImageDumper`; dataset sources), credits (Teleport Maps
  for the idea of a spirit tree map; Fairy Ring Atlas for the engine; OpenRS2; the OSRS Wiki;
  Shortest Path).

---

## 8. Not verified without the game (test in game)

Nothing below has been seen in the game yet. Everything was built from the cache, the decoded cs2,
the injected client's bytecode, other plugins' sources and fake widgets in the tests.

- **Layout.** The hanging UNIVERSE (modern) and LJ_LAYER1 (classic) show only their part inside the
  slot; the moved row and close button click normally ("Continue" travels, "Close" closes); the
  hotkeys still work with the rows hidden. Fixed, resizable classic and resizable modern layouts;
  Use free space (and the slot put back when the bank or another interface opens right after).
- **Readability.** The classic Travel row on the parchment backdrop; the modern one on its own
  rectangle over the black backdrop.
- **The 200x32 button.** That the resized row clicks over its whole cell ("Continue" anywhere in
  it, modern and classic), reads well with its text centred in the taller row, and still turns
  white (modern) / 0x524825 (classic) on hover, with the modern backing going to transparency
  240; that the other rows, hidden, keep 161x20 / 16 px when List mode shows the plain menu again;
  that a key press's "Please wait..." shows in the big button. At 32 px the game may wrap a row's
  text onto a second line (FRA: line breaking is off only for a text component shorter than about
  two lines of its font), which today's labels never need at 200 px; a long Better Teleport Menu
  re-text would wrap within the button rather than be cut [M]. That nothing (the game, Better
  Teleport Menu) resizes the row back while the map shows; if something did, the next tick's
  apply writes our size again.
- **Events.** `ScriptPreFired(9142/217)` fires before the setup script, and the put-back there leaves
  no flicker (2.1); a rebuild while the map shows comes back with the selection kept; the order of
  `WidgetClosed`/`WidgetLoaded` against the scripts; `WidgetClosed` with `isUnload()` false on the
  fixed/resizable switch, with the map staying up across it.
- **The wheel.** Over the classic Travel row nothing scrolls; elsewhere on the map it zooms.
- **Quick-select panel.** A row's left click selects (the game runs our Select; no stale entry runs
  after moving onto the panel from the game view); the tab and the header triangle toggle it, and
  the choice survives a client restart.
- **Rows.** What a grey row and the tree you stand at do when clicked; whether grey rows have
  `</col>`; that "Please wait..." after a key press keeps the Travel row in place until the menu
  closes.
- **Hotkeys in classic Map mode.** With the classic menu (Better Teleport Menu off), a row's key
  travels while the map shows, with and without a selection (187:0 stays visible; only its parchment
  model is hidden).
- **Other plugins.** Better Teleport Menu with the classic menu in Map mode: its own parchment
  (drawn after 187:0, so now also while the map shows) and the game's title stay under the map and
  do not show through the Travel and close holes or below the slot beside the lowered list; after a
  window resize in Map mode (script 909), List mode still has a parchment; after the fixed to
  resizable switch in Map mode (it may hide the model and the scrollbar we hid, and we show them
  again in List mode), the parchment does not look doubled and no needless scrollbar shows beside
  the taller list. Better Teleport Menu: re-texted rows parse, a key rebound while the menu is open
  shows on the marker within a tick, its hidden rows count as not listed, "Expand scroll menu" with
  the classic menu in resizable mode (the slot 8 px taller: the Travel cell and the close button
  still in the corner, Use free space off). Teleport Maps and Spirit Tree Menu: the notice at open;
  turning them on with the map showing (everything put back at once) and off again (the map comes
  back, or the reopen notice when they had the menu at open).
- **Lifecycle.** Turning the plugin on with the menu open, and off with the map showing (everything
  put back, mouse-over text on again); logout and world hop with the menu open; LOADING and a brief
  connection loss keep the map.
- **Data.** Every arrival tile; the Poison Waste stage-38 question; Laguna Aurorae's first-visit
  gate; the last-destination values; what the "Your house" row does when clicked inside your own
  house, and whether a guest at a house party sees it (3.4).
- **Loading (4.9).** How long the first open takes in game now, on a slower machine too; that a
  "Travel" click on each kind of tree (world trees by their multiloc parent ids, farming trees, POH
  trees) and "Tree" on a spiritual fairy tree start the prefetch (the ids and option texts come from
  the cache, not from a live menu entry); that the estimated rect before the first open gives the
  same level as the real one.

## 9. Deviations

Where the code differs from this spec as first written, checked against the code on 2026-10-04.
Items marked **(spec updated)** are now described in the sections above; the others still differ
from the text above.

**Menu handling (`TreeMenu`, `SpiritTreeAtlasPlugin`)**

1. **Rebuilds start from the game's own state (spec updated, 3.3, 4.3).** The spec dropped the
   dynamic children's records after `ScriptPostFired`. The code puts everything back at
   `ScriptPreFired` of the open style's setup script, so the script, and any plugin acting after
   it, works on an unchanged menu; nothing hidden during the rebuild (Better Teleport Menu hides the
   classic scrollbar there) can be mistaken for ours and unhidden later.
2. **378 `menu_indexed` counts as a classic setup script (spec updated, 2.1, 3.3)**, so a numbered
   menu built in an open 187 is a close at once.
3. **The title is checked every tick while open (spec updated, 3.3)**, unless stepping aside. The
   first port checked only that the menu was still open, because Teleport Maps deletes the classic
   title; that case is still covered, since we change nothing while stepping aside.
4. **Rows are read every tick (spec updated, 3.3)**, cheaply (texts compared first), so key badges
   and availability follow Better Teleport Menu's rebinding while the menu is open.
5. **A row the game or another plugin hid is not listed (spec updated, 3.3, 3.4)**: its tree is
   absent ("Not in this tree's list"). Such a row is also never moved or shown, not even as the
   Travel row (hard rule 4).
6. **Moves are restored only while the widget still holds what we wrote.** If the game has laid it
   out afresh since (proc 219 resets the classic list), it is left where the game put it, and that
   place becomes the record on the next apply. Stricter than "live and self-hidden" (4.3).
7. **Modern geometry is measured relative to INFINITE** (UNIVERSE's parent), not the slot. It gives
   the same result while INFINITE fills the slot, as it always does after 9142.
8. **An interface move is not a close (spec updated, 2.1, 3.3)**: `WidgetClosed` with
   `isUnload()` false is ignored, so the map survives the fixed/resizable switch.
9. **Logout and hop put back whatever is still live (4.3)** rather than dropping the records unseen,
   as the first port did (Fairy Ring Atlas drops them).
10. **Stepping aside is re-checked while the menu is open (spec updated, 4.10)**, on
    `PluginChanged` and `teleportmaps` config changes, with the reopen notice when the other plugin
    had the menu at its last build. The spec checked only on each open.
11. **Grey without a key (spec updated, 3.3)**: with no `": "` in the row, `<col=5f5f5f>` anywhere
    makes it grey (Better Teleport Menu's unbound rows).

**Input and ownership (`AtlasInput`, `SpiritTreeAtlasPlugin`)**

12. **The mouse wheel is kept from the game anywhere on the map (spec updated, 4.4, 4.8)**, in the
    holes and while a right-click menu is open too, so the classic list never scrolls the Travel row
    out of its cell. The spec said nothing is consumed in the holes; presses still are not.
13. **List mode's Map button checks its menu (spec updated, 4.8)**: a left press there goes to the
    game only when the game's menu was built for the button; otherwise it is swallowed, so a stale
    entry (such as "Walk here") built one frame earlier never runs.
14. **Use free space re-checks on a `WidgetLoaded` of any group while held (spec updated, 4.2)**, the
    menu's own included, not only other groups.

**Data and state (`TreeRepository`, `Tree`, `Portal`)**

15. **The house is never "here" (spec updated, 3.4)**: it is left out of the 6-tile check, and the
    first port's "in an instance, the house" rule is gone (it marked our own house "here" in a
    friend's house, and opened the Prifddinas layer in a house in Prifddinas).
16. **Portal coordinates are doubles**, because the house portal centres have half tiles (3.1).

**Drawing (`AtlasOverlay`, `RowBackdrop`; and, for the record, the painters)**

17. **`RowBackdrop` fills the Travel hole as the map overlay drew it in the previous frame** (it
    draws under the widgets, before the map overlay of the same frame).
18. **Stand-in fallback.** Between two tick reads, a row re-texted to another tree makes the stand-in
    say "Not in this tree's list" (the live text no longer maps; 4.4) while the marker still shows
    the tree as available; the next read makes them agree.
19. **The card first tries a corner that covers no marker** (`ChromePainter.place`), then the first
    spot along the bottom edge, the top, the left and the right (8 px steps) that covers none, and
    only then falls back to Fairy Ring Atlas's rules; otherwise the fitted overview hides Laguna
    Aurorae under the card. On a 512x334 map (fixed mode) every corner covers a marker, so the card
    slides: right along the bottom for a compact card, to the left edge's middle for a taller
    locked one (`PainterTest.theFixedModeCardCoversNoMarker`).
20. **The card also keeps clear of a surface stand-in it describes**, and of the widened stand-in
    box (`Scene.standInRect`).

**Smaller differences**

21. `Scene.layer()` is a method, not a field.
22. The stand-in's "Open Prifddinas map" entry is option "Open" with target "Prifddinas map".
23. The source-size test counts each line end as one byte, so a CRLF checkout measures the same as
    the committed files (rule 7).
24. `ComplianceTest` goes further than rule 1: it also forbids widget resizes (but the Travel
    row's, allowed only in `TreeMenu.writeSize` and checked structurally, item 26), child creation,
    other listeners, widget restyles (`setTextShadowed`, `setFontId` and the like), key managers,
    every other widget setter (the other resizes and layout writes, scroll writes, drag, hold,
    release and scroll-wheel listeners, click masks and target verbs, model and animation
    settings), network classes (all of `java.net`), `getScriptEvent` (re-running a game script's
    event) and `setParam0` / `setParam1` / `setIdentifier` (retargeting a game menu entry), and fails
    if any code refers to the key-listener layers. It scans each file with comments stripped and
    whitespace collapsed, matching names as whole words, so method references
    (`client::runScript`) and spaced calls (`runScript (`) are caught; every `setType(` must be
    `setType(MenuAction.RUNELITE)`; and, structurally, `setHidden(true)` appears only in
    `TreeMenu.hide`, whose only static target is `LJ_SCROLL_BAR` (nothing that holds a key-listener
    layer is ever hidden).

**Review fixes (2026-10-04)**

25. **Classic Map mode hides only the parchment model (spec updated, 2.3, 4.3).** The first port
    hid `LJ_LAYER2` itself, which also hid `Menu.KEYLISTENERS` (its static child) and so the
    game's hotkeys, and tripped Better Teleport Menu's title check.

26. **The Travel row is a 200x32 button (spec updated, 1, 2.2, 2.3, 4.3, 4.4).** The user, testing in
    game: "the button should be bigger". The shown row's real widgets are resized as well as
    moved, as Fairy Ring Atlas does for CONFIRM; hard rule 4 now allows exactly that one resize.
    The modern button's right end sits under the close button's (10 px inside the scroll area's
    right end, which the first port used), so both menus give the same corner; the classic close
    button keeps the modern 12 px gap (it was 4). The stand-in uses the taller cell for two lines.
    The "Travel" caption slides right along the cell's top off a marker (4.4), since the wider cell
    put its left end under Feldip Hills in the fixed-mode overview. `ComplianceTest` now also
    forbids every other widget setter that resizes or lays out (`setWidth`, `setHeight`,
    `setSize`, `setPos`, `setForcedPosition`, the relative position, scroll size and position)
    and the remaining listener, click-mask and appearance setters (item 24).

**Loading (2026-10-04)**

27. **Tiles load on a small pool, coarse first, ahead of the menu, and the overview stays (spec
    updated, 4.9).** The user saw the map take a few seconds to load. Fairy Ring Atlas's design (one
    `MIN_PRIORITY` newest-first worker, every decode under the `ImageIO` class lock, the z=1 tiles
    between a z=0 overview and z=2 kept in the cache, the z=-1 cover queued between the slow z=0
    tiles, everything released 50 ticks after a close) became: up to three workers below normal
    priority that still take the newest request and drop stale ones, no tile loaded twice at once,
    only the reader lookup under the lock, sources read for a derivation not kept, the coarse cover
    queued last (so served first), a prefetch on "Travel" / "Tree", and the overview (z <= 0) kept
    500 ticks. Previews are pixel-identical (hashes of every `build/preview` image compared).
