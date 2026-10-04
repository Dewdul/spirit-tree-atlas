# Spirit Tree Atlas

Spirit Tree Atlas covers the spirit tree travel menu with a map of every spirit tree destination. You can zoom in and pan the map, which is drawn sharply from the game's own world map. Click a tree on the map, and the menu's own row for that tree moves into the map's corner as the **Travel** button: click it, or press its key, to travel.

## Features

- **Map of every spirit tree.** All 14 destinations are on one map: the five trees that need only Tree Gnome Village, the five farming patches (Port Sarim, Etceteria, Brimhaven, Hosidius and the Farming Guild), Prifddinas, Poison Waste, Laguna Aurorae and your house. The map opens fitted to all of them. You can zoom from that overview down to individual buildings: scroll to zoom and drag to pan.
- **Both menu styles.** The game has two looks for this menu: the classic scroll, and the newer menu you get with the *Modern menu interface* setting. The plugin works with either, and the Travel button is the real row of whichever one you use.
- **The Travel button.** The selected tree's real menu row sits in the map's bottom-right corner, with the menu's own close button just above it. Until you pick a tree you can travel to, the Travel spot says why it is empty: *Pick a tree on the map*, *Locked* and the reason, *You are here*, or *Not in this tree's list*.
- **Quick select list.** Down the map's left edge, every destination is listed in the menu's own order, with its key, a small marker showing whether you can go there, and its name. Click one to select it: the map pans to it if it is out of view. The tree you are standing at says *You*, and your last trip has the arrow badge. Click the small triangle to fold the list into a slim tab, and the tab to open it again. In fixed mode, where the map is small, it starts folded.
- **Tree markers.** Each marker shows what the menu says about that tree: available (green), locked (grey with a padlock, because the game lists it in grey), or not in this menu's list (hollow). A pin marks the tree you are standing at, a small arrow badge marks your last trip, and a badge shows the tree's key in the menu.
- **Tree cards.** Hover over a tree for a short card: its name and area, whether you can travel there, what to do next, its requirements while it is locked, and what is nearby. Turn on *Full tree details* for everything: every requirement, nearby place, danger and note.
- **Your house.** *Your house* is drawn at your house portal (Rimmington, Taverley, Pollnivneach, Rellekka, Brimhaven, Yanille, Hosidius, Prifddinas or Aldarin) and named after its town.
- **Prifddinas.** The elf city has a map of its own. On the world map, its tree (and your house, if it is there) is shown at the city in Tirannwn: click the city's map link, or right-click the tree and choose *Open Prifddinas map*. The orange **Back to Gielinor** button at its top-left returns to the world map.
- **World map details.** Place names, and map icons when zoomed in, come from the game's own world map.

## How to use it

1. Use a spirit tree's *Travel* option (or a spiritual fairy tree's *Tree* option). The map opens over the travel menu, fitted to every spirit tree. In resizable mode it fills the free space above the chatbox and left of the side panel, up to the Max width and height. The menu's close button and the Travel spot sit in the map's bottom-right corner.
2. **Click a tree** to select it, on the map or in the *Destinations* list on the left. Its card says what to do next:
   - **You can travel there.** The tree's own menu row appears in the Travel spot, and its frame pulses. Click it, or press the key shown on the marker (the key the menu shows for that row). The menu then closes and the game takes you there as usual.
   - **It is locked.** The menu lists it in grey. The Travel spot and the card say what the tree needs, such as *Needs Song of the Elves*.
   - **You are standing at it**, or **it is not in this tree's list.** The Travel spot says so.
3. To navigate:
   - **Mouse wheel:** zoom.
   - **Drag:** pan.
   - **+ / - buttons:** zoom in and out.
   - **Fit:** show every spirit tree (on the Prifddinas map, the whole city).
   - **List:** switch to the plain menu.
   - **Clear:** clear the selection.
   - **Right-click a tree:** *Select*, *Zoom to* or *Clear selection*. Right-click anywhere else on the map to clear the selection.
   - **Destinations list:** click a row to select that tree (the map pans to it when it is out of view), or right-click it for *Select*, *Zoom to* or *Clear selection*. Hover over a row to see the tree's card and its marker lit up. If the list is longer than the map is tall, scroll it with the mouse wheel. Click the triangle at its top to fold it into a tab; your choice is remembered.
4. **List mode** shows the plain menu as the game made it, with a small **Map** button just above it. Click the Map button to go back to the map.

The menu's own keys work all the time, in both modes, so you can press a tree's key without clicking anything. If you close and reopen the menu within about two seconds, the map keeps its view, its selection and the mode you were in.

### Why clicking the map doesn't make you travel

RuneLite plugins may not act in the game for you. They can't send clicks or choose a menu row. A click on the map therefore only selects a tree. The plugin then moves that tree's real menu row into the corner of the map, and you click it (or press its key) yourself. The menu still decides where you go: if a tree is locked, the game refuses as it always does.

While the map shows, the plugin changes the spirit tree menu only by hiding its parts and moving them, never by changing their text, size or options. It hides the rows under the map and moves the selected row and the close button into the corner. It also removes the game's own right-click options under the map, such as *Walk here*, and turns off the game's mouse-over text (top-left of the screen). With Use free space it moves the menu's place on screen (see the notes). It puts everything back when you switch to List mode or close the menu.

## Settings

| Section | Setting | Default | What it does |
|---|---|---|---|
| Map | Open as map | on | Start with the map rather than the plain list |
| | Use free space | on | In resizable mode, move the spirit tree menu into the corner of the free screen space while the map shows, so the map can fill it (see the notes) |
| | Max width / Max height | 2000 / 1400 | Largest map size in resizable mode. With Use free space, a smaller map is centred in the free space |
| | Open at | Fit all | Fit every spirit tree (*Fit all*), centre on where you are (*Around you*), or *Remember* the last view this session |
| | Quick select list | on | The *Destinations* list down the map's left edge (see Features). Turn it off to hide the list and its tab |
| | Place names / Map icons | on | World map place names, and icons when zoomed in |
| | Full tree details | off | Show everything about a tree on its card (every requirement, nearby place, danger and note) instead of a short summary |
| Markers | Tree names | on | Each tree's name beside its marker |
| | Key hints | on | Each tree's menu key on its marker |
| | Dim locked trees | off | Draw the trees the menu lists in grey at half opacity |
| | Available / Selected colour | green / orange | Marker colours |

## Notes

- **Stretched mode.** The map is drawn at the game's own resolution and then stretched along with the rest of the game. It can't be sharper than the game itself.
- **Use free space.** The game centres the spirit tree menu, and the map grows from its bottom-right corner, where Travel sits. To let the map fill the screen, the plugin moves the menu's place on screen (never its size) into the corner of the free space while the map shows. It puts it back exactly when you switch to the list, close the menu, log out or hop. It leaves the place alone if the game or another plugin has already changed it. If it clashes with another layout plugin, turn the option off.
- **Fixed mode.** The map fills the game view, which is the menu's own area. The *Destinations* list starts folded there to leave room for the map; open it from its tab.
- **Teleport Maps.** Teleport Maps can also replace this menu with a map. While its *Spirit Tree Map* option is on, Spirit Tree Atlas changes nothing and shows a one-line notice above the menu instead. Turn that option off in Teleport Maps to use this map; its other maps are not affected. If you turn it off while the menu is open, close and reopen the menu.
- **Spirit Tree Menu.** That plugin rearranges the classic menu. While it is on, Spirit Tree Atlas leaves the classic menu to it and shows a notice; the modern menu is not affected.
- **Better Teleport Menu.** The two work together. Keys you set in Better Teleport Menu show on the markers, and its *Set Hotkey* option is on the Travel button. Rows it hides count as not in the list. Its *Expand scroll menu* option makes the classic menu's area taller, so Use free space then leaves the classic menu where it is.
- **Memory.** About 30 seconds after you close the menu, the plugin frees its decoded map tiles and image buffers.

## Data sources

- **Map imagery.** The map was rendered from the Old School RuneScape game cache with RuneLite's `MapImageDumper` (`net.runelite:cache` 1.13.1), using OpenRS2 cache 2727 (2026-09-30). It is bundled as small tiles, so the plugin makes no network requests. Place names and map icons come from the same cache.
- **Tree places.** Every tree and house portal position comes from the cache's object placements. Arrival tiles come from the Shortest Path plugin's transport data, checked against the cache's collision data.
- **Tree cards.** The card text (areas, requirements, nearby places and dangers) was written for this plugin, using the [Old School RuneScape Wiki](https://oldschool.runescape.wiki/) as reference.
- **Regenerating.** The generators live in `tools/`. See `tools/mapgen/README.md` and `tools/data/README.md`.

## Credits

- Teleport Maps (Plugin Hub) by MJHylkema, for the idea of a spirit tree map in place of the travel menu.
- Fairy Ring Atlas (Plugin Hub), also by Dewdul, whose map engine this plugin reuses.
- Shortest Path (Plugin Hub) by Skretzo, for its spirit tree arrival tiles.
- RuneLite, for the client, the cache tools and `MapImageDumper`.
- OpenRS2, for the cache archive.
- The Old School RuneScape Wiki and its editors.

## License

BSD 2-Clause. See [LICENSE](LICENSE).
