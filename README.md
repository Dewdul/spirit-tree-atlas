# Fairy Ring Atlas

Fairy Ring Atlas replaces the fairy ring dials with a map of every fairy ring destination. You can zoom in and pan the map, which is drawn sharply from the game's own world map. Choose a ring on the map. The plugin then shows you the travel log entry or the dial turns that get you there.

## Features

- **Map of every ring.** All 42 surface rings appear on a world map. You can zoom from an overview of the whole map down to individual buildings: scroll to zoom and drag to pan.
- **Names in the ring's own menu.** Right-click a fairy ring and *Last-destination (AJR)* reads *Last (AJR) Fremennik Slayer Dungeon*, and the *Favourites* codes show the destination's name, e.g. *CIR Mount Karuulm*. Entries Fairy Ring Favourites has already renamed are left alone.
- **Your house.** DIQ is drawn at your house portal (Rimmington, Taverley, Pollnivneach, Rellekka, Brimhaven, Yanille, Hosidius or Aldarin), and the *Your house* card zooms to it. It shows as locked until you have used a fairy ring in your house. (A house in Prifddinas is outside the bundled map, so DIQ is shown on its card only.)
- **Favourites list.** Your in-game favourites (starred in the travel log) are listed at the top of the side panel. Click one to select it and zoom to it. Drag a favourite to put the list in your own order (saved per account; the game's own favourites are not changed).
- **Groups panel.** A second panel on the right of the map holds quick-click groups of rings. It comes with a *Slayer* group (task masters and slayer areas) and a *Farming* group (herb, tree, hops and other patches), each in a suggested order (Farming in run order). Each row says what the ring is for, such as *Dagannoths (Lighthouse)* or *Herbs: Ardougne*. It is closed by default: click the *Groups* tab on the map's right edge to open it. Click a ring in a group to select it and zoom to it; hover it to see why it is in the group and what is there (the patch types for Farming, the slayer monsters or master for Slayer). You can make your own groups and change the prebuilt ones (see below). Groups are saved in your RuneLite settings (shared by all your accounts).
- **Off-surface destinations.** These include Zanaris, the Abyss, Mor Ul Rek, the Cosmic entity's plane and the other realms. Each one has a map of its own, opened from the *Elsewhere* section of the side panel or from a portal marker on the surface. Opening one also selects its ring (the first one in your travel log, when an area has two). The orange **Back to Gielinor** button at its top-left returns to the world map. *Your house* (DIQ) has a card.
- **Ring cards.** Hover over any ring for a short card: its code and name, whether it is unlocked (in your travel log), what is nearby, its requirements while it is still locked, and its main danger. For a locked ring the card names the one thing you still need before a visit can unlock it, such as *Locked - needs Beneath Cursed Sands progress* at AKP, read from your own quest progress. Once you have it, the card says *a first visit unlocks it*. Turn on *Full ring details* for the full card: area, description, every requirement and danger, notes, and on rings you can leave without a staff a line saying so (unless you have the elite Lumbridge & Draynor Diary).
- **Marker states.** Markers show at a glance which rings are in your travel log, which are favourites, your last destination, the ring you are standing at and the code currently on the dials.
- **Travel log search.** Typing in the game's own travel log search dims the rings that don't match and zooms the map to the ones that do.
- **Clue helper.** The map marks your active clue location, and a fairy ring clue's code is selected for you. This needs the core *Clue Scroll* plugin to be enabled. A hot-cold clue is marked once it is solved.

## How to use it

1. Use a fairy ring. The map opens over the dials, centred on the ring you are standing at. In resizable mode it fills the free space above the chatbox and left of the side panel, up to the Max width and height, and is centred in that space beyond them. The real **Teleport** and close buttons sit in the map's bottom-right corner. Teleport is greyed out until the dials show your chosen ring, so you can't teleport somewhere else by mistake.
2. **Click a ring** to select it. The greyed-out Teleport now reads *Teleport to* its code, and it and the card tell you the next step:
   - **The ring is in your travel log.** The log is narrowed to that code and its row is highlighted. Click the row (*Use code*), then click **Teleport**. If you click a row in the log yourself, the map follows and the log is left as it is.
   - **The ring is not in your travel log yet** (locked) **and you can unlock it.** A first visit unlocks it. The Teleport spot turns into an orange **Dial it by hand** button: click it (or **Show dials** on the card). Arrows on the real dials show which way to turn each one and how many times. Turn the dials, then click **Teleport**. The **Map** button takes you back to the map.
   - **The ring needs something first** (a quest, quest progress or a first visit somewhere, such as Great Kourend for AKR, CIR, CIS and DJR). The card and the Teleport spot say what, in red, and there is no dial button until you have it. Some needs can't be read from the game, such as a fairy ring built in your house (DIQ) or Daero's training (CLR): the card names them, and the dial button stays.
   - **The dials already show the code.** The card reads *Ready: click Teleport* and the Teleport button pulses.
3. To navigate:
   - **Mouse wheel:** zoom.
   - **Drag:** pan.
   - **+ / - buttons:** zoom in and out.
   - **Fit:** show every ring on this map (on an underground map, the whole area).
   - **Dials:** switch to the plain dials.
   - **Right-click a ring:** *Select*, *Zoom to* or *Clear selection*. Right-click anywhere else on the map, or press **Clear**, to clear the selection.
4. To use the **Groups** panel:
   - **Open or close it** with the *Groups* tab on the map's right edge, or the arrow at the panel's top-left. It stays the way you left it (but see *Fixed mode* below).
   - **Add a ring to a group:** right-click the ring (on the map, in the side panel or in another group), then *Add to group* and pick a group. Groups that already have it are marked *(added)*. *New group...* asks for a name in the chatbox and makes a group with that ring in it.
   - **Make an empty group:** click the **+** at the panel's top-right. You can have up to 30 groups.
   - **Reorder a group:** drag its rings up or down.
   - **Remove a ring:** right-click it in the group, then *Remove from*.
   - **Rename a row:** right-click it in the group, then *Rename*, and type a new name in the chatbox. A renamed row also offers *Reset name*, which puts back its prebuilt name (or, in your own groups, the ring's name). Rings you add to a group start with the ring's name.
   - **Collapse or expand a group:** click its header.
   - **Rename or delete a group:** right-click its header. A prebuilt group you have changed also offers *Reset to default*. To bring back a prebuilt group you deleted, right-click the **+**, then *Restore group*.

### Why clicking the map doesn't teleport you

RuneLite plugins may not act in the game for you. They can't send clicks, turn dials or choose a travel log entry. A click on the map therefore only selects a ring. The plugin then highlights the real log row, or shows you the dial turns, and you make those clicks yourself: the travel log row (or the dials) is what sets the code for **Teleport**. The plugin changes the fairy ring interfaces only while they are open: it hides the dials under the map, and narrows and recolours the travel log (in map or dial mode). Under the map it also removes the game's own right-click options, such as *Walk here*. It moves the Teleport and close buttons into the map's bottom-right corner and turns off the game's mouse-over text (top-left) while the map shows; with Use free space it also moves the dial interface's place on screen (see the notes). It puts everything back when you close the interface.

## Settings

| Section | Setting | Default | What it does |
|---|---|---|---|
| Map | Open as map | on | Start with the map rather than the dials |
| | Use free space | on | In resizable mode, move the fairy ring interface into the corner of the free screen space while the map shows, so the map can fill it (see the notes) |
| | Max width / Max height | 2000 / 1400 | Largest map size in resizable mode. With Use free space, a smaller map is centred in the free space |
| | Open at | Around you | Centre on the ring you are standing at (*Around you*), *Fit all* rings, or *Remember* the last view this session |
| | Place names / Map icons | on | World map place names, and icons when zoomed in |
| | Fit to search | on | Zoom to the rings that match the travel log search |
| | Full ring details | off | Show everything about a ring on its card (description, every nearby place, notes) instead of a short summary |
| Markers | Code labels | All | Show codes for *All* rings, *Favourites* only, or *None* |
| | Dim locked rings | off | Draw rings you have not unlocked (not in your travel log yet) at half opacity |
| | Visited / Selected / Favourite colour | teal / orange / gold | Marker colours |
| Travel log | Filter travel log | on | Narrow the log to the selected ring, moved to the top |
| | Dial guidance | on | Turn arrows on the dials for the selected ring |
| | Names in ring menu | on | Destination names in a fairy ring's right-click menu (Last-destination and the Favourites codes) |
| Advanced | Clue helper | on | Mark the active clue and preselect fairy ring clue codes (needs the core Clue Scroll plugin) |

## Notes

- **Stretched mode.** The map is drawn at the game's own resolution and then stretched along with the rest of the game. It can't be sharper than the game itself.
- **Use free space.** The game centres the fairy ring interface, and the map grows from its bottom-right corner, where Teleport sits. To let the map fill the screen, the plugin moves that interface's place on screen (never its size) into the corner of the free space while the map shows, and puts it back exactly when you switch to the dials, close the ring, log out or hop. It leaves the place alone if the game or another plugin has already changed it. With Fixed Resizable Hybrid, the interface is moved within that plugin's wider play area. If it clashes with another layout plugin, turn the option off.
- **Fixed mode.** The map fills the dial area. The side panel (*Favourites* and *Elsewhere*) starts collapsed, and you can open it with its tab. The *Groups* panel also starts closed in fixed mode, even if you left it open on a bigger map, until you open it there.
- **Fairy Ring Map.** That plugin also draws over the dials, so the two are marked as conflicting: RuneLite offers to turn the other off when you enable one. If both are on anyway, the map shows a notice asking you to turn one of them off.
- **Naming a group or a row** uses RuneLite's own chatbox input, as the core Fairy Rings plugin does for tags. It takes the place of the travel log's search box while you type; reopen the search from the log if you need it.
- **Memory.** About 30 seconds after you close the fairy ring, the plugin frees its decoded map tiles and image buffers.

## Data sources

- **Map imagery.** The map was rendered from the Old School RuneScape game cache with RuneLite's `MapImageDumper` (`net.runelite:cache` 1.13.1), using OpenRS2 cache 2727 (2026-09-30, build 241). It is bundled as small tiles, so the plugin makes no network requests. Place names and map icons come from the same cache.
- **Ring codes and landing tiles.** These were checked against the cache's fairy ring table. While the dials are open, the plugin also reads that table from the game.
- **Ring cards.** The card text (descriptions, nearby places, requirements and dangers) was written for this plugin, using the [Old School RuneScape Wiki](https://oldschool.runescape.wiki/) as reference.
- **Regenerating.** The generators live in `tools/`. See `tools/mapgen/README.md` and `tools/data/README.md`.

## Credits

- Fairy Ring Map (Plugin Hub) by jdkrupa, for the idea of a map in place of the travel log and for the travel log filtering technique this plugin also uses.
- RuneLite, for the client, the cache tools and `MapImageDumper`.
- OpenRS2, for the cache archive.
- The Old School RuneScape Wiki and its editors.

## License

BSD 2-Clause. See [LICENSE](LICENSE).
