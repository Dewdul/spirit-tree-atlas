# Quick-click group sources

Sources for `src/main/resources/com/fairyringatlas/groups.json`, the built-in
*Slayer* and *Farming* groups. Every code is a dialable code in `rings.json`.
Each group is in suggested order: most useful first for Slayer, run order for
Farming. Labels are the rows' names in the panel: purpose first, at most 24
printable ASCII characters, each taken from that row's note and sources below
(nothing beyond them). Notes are optional display hints of at most 40
printable ASCII characters, shown when a row is hovered. Details (see *Details*
at the end) say what is at the spot, at most 90 printable ASCII characters.

The OSRS Wiki was read via `?action=raw` on 2026-10-03. Ring tiles are the
`rings.json` landing tiles. Patch tiles are the wiki's `{{Map}}` pins on the
`*/Patches` transclusions of `Farming/Patch_locations`. Distances are tile
offsets from the ring's landing tile to the pin.

## Slayer

| # | Code | Label | Note | Source |
|---|---|---|---|---|
| 1 | CKS | Slayer Tower | Slayer Tower; Mazchna in Canifis | `Slayer_Tower`: "CKS lands you in the west of Canifis, just south of the tower". `Slayer_Master` (Mazchna/Achtryn): "CKS, arriving west of Canifis". `Chaeldar` (Fairy rings section) names CKS for Slayer Tower tasks. |
| 2 | AJR | Fremennik Slayer Dungeon | Kurasks, turoths, basilisks and more | `Fremennik_Slayer_Dungeon` (transportation: AJR). `Kurask` infobox location: Fremennik Slayer Dungeon (AJR). `Chaeldar` names AJR. Monster list from the `rings.json` AJR POIs. |
| 3 | CIR | Konar (Karuulm) | Konar; Karuulm Slayer Dungeon | `Slayer_Master` (Konar quo Maten): summit of Mount Karuulm, "CIR, arriving south of Mount Karuulm". `Konar_quo_Maten`: "Heading north of the CIR fairy ring". `Karuulm_Slayer_Dungeon`: the mountain is a short walk north of CIR. |
| 4 | BKS | Chaeldar (Zanaris) | Chaeldar (Zanaris) | `Slayer_Master` (Chaeldar): in Zanaris by the Fairy Queen's throne; reached by any fairy ring to Zanaris or the Lumbridge Swamp shed. BKS is the Zanaris code in `rings.json`. |
| 5 | BIQ | Kalphites | Kalphite Lair and Kalphite Cave | `Kalphite_Lair`: BIQ, then run north-west. `Kalphite_Cave`: BIQ, then run north-east (Slayer-only cave). |
| 6 | BKP | Smoke devils | Smoke Devil Dungeon | `Smoke_Devil_Dungeon`: BKP brings players just south-west of the dungeon. `Smoke_devil`: quick access via BKP. |
| 7 | DJR | Chasm of Fire | Chasm of Fire demons; lizardman shamans | `Chasm_of_Fire`: DJR lands just south-east of the Chasm; monsters are lesser, greater and black demons. `Lizardman_Canyon`: the easiest way there is DJR. `Lizardman_shaman`: DJR is just south of Lizardman Canyon. |
| 8 | AKQ | Cave kraken | Kraken Cove: cave kraken, Kraken boss | `Kraken_Cove`: AKQ brings players just east of the Cove. `Cave_kraken` location: Kraken Cove. |
| 9 | DIP | Abyssal Sire | Abyssal Sire (Abyssal Nexus) | `Abyssal_Nexus` (teleport: DIP; safe route without the Wilderness). `Abyssal_Sire`: "may be reached quickly using fairy ring DIP". |
| 10 | AIQ | Skeletal wyverns | Skeletal wyverns (Ice Dungeon); mogres | `Asgarnian_Ice_Dungeon`: trapdoor north of Mudskipper Point, AIQ. `Skeletal_Wyvern` location: Asgarnian Ice Dungeon (AIQ). `Mogre` location: Mudskipper Point (AIQ). |
| 11 | ALP | Dagannoths (Lighthouse) | Dagannoths under the Lighthouse | `Dagannoth`: they live in the Lighthouse basement, close to ALP, after Horror from the Deep. `Lighthouse`: the fastest way there is ALP. |
| 12 | BJS | Zulrah | Zulrah (76 Agility to cross) | `Zulrah`: BJS to the island west of Zul-Andra, then the stepping stones (76 Agility, boostable). Matches the `rings.json` BJS requirement line. |
| 13 | ALQ | Araxytes (Spider Cave) | Morytania Spider Cave (araxytes) | `Morytania_Spider_Cave`: ectophial or ALQ, then run south past the small bridge. `Araxyte`: 92 Slayer; the only location is the Morytania Spider Cave. |
| 14 | DKR | Krystilia (Edgeville) | Krystilia; Edgeville Dungeon | `Slayer_Master` (Krystilia): by the Edgeville jailhouse, "DKR, and run west over the river". `Slayer_Master` (Vannaka, who is in Edgeville Dungeon): "DKR, arriving east of Edgeville". `Krystilia`: Edgeville via DKR. |

### Considered and left out

- **Nieve/Steve** (Tree Gnome Stronghold). No ring is near. The wiki's routes
  are slayer ring, necklace of passage, balloon, spirit tree and glider.
- **Duradel** (Shilo Village, CKR). The wiki lists CKR last, after Karamja
  gloves 4/3 and the cart. The walk goes round by the Kharazi Jungle
  (`Duradel`), so the ring is not the usual route.
- **Catacombs of Kourend.** DJR and CIS reach the Reeking Cove and Demon's Run
  exits only after the player has used them once (`Catacombs_of_Kourend`).
  DJR is already in the group.
- **Brimhaven Dungeon** (CKR, `Brimhaven_Dungeon`). The wiki lists it after the
  house portal, Tai Bwo Wannai scroll, spirit tree and glory routes.
- **Stronghold Slayer Cave** has no nearby ring; the slayer ring goes there.
- **Mortimer** (Wyrmscraig Cavern). `Slayer_Master` lists no ring route.
- **Isle of Souls Dungeon** (BJP), **Brine Rat Cavern** (DKS) and **TzHaar**
  (BLP) are real ring routes (`Isle_of_Souls_Dungeon`, `Brine_Rat_Cavern`,
  `Mor_Ul_Rek`). They were cut to keep the group at 14 entries. Players can
  add them.

## Farming

Order: herb run (wiki *Farming runs* herb run sequence, ring stops only), then
tree, fruit tree and calquat, then special, hops and bush patches.

| # | Code | Label | Note | Patch tile, distance from ring | Source |
|---|---|---|---|---|---|
| 1 | ALQ | Herbs: Port Phasmatys | Port Phasmatys herb patch (north) | 3602,3526; 31 N | `Farming_runs` herb run: "Use the fairy ring ALQ and run north". `Allotment_patch/Patches` lists ALQ. |
| 2 | BLR | Herbs: Ardougne | Ardougne herb patch (north-west) | 2667,3375; 73 W, 24 N | `Allotment_patch/Patches` (north of Ardougne) lists BLR among its closest teleports. |
| 3 | AKR | Herbs: Hosidius | Hosidius herb patch (west); vinery | 1735,3555; 91 W. Vinery 1808,3556 | `Allotment_patch/Patches`: "AKR to Hosidius Vinery, then run west". `Farming_runs` herb run lists AKR. `Special_patches/Patches` (grape) lists AKR first. |
| 4 | CIR | Farming Guild | Farming Guild: herbs, trees, Hespori | Herb 1239,3727; 63 W, 35 S | `Farming_runs` (herb, tree, bush runs): "CIR and run south-west". `Allotment_patch/Patches`, `Tree_patch/Patches`, `Fruit_tree_patch/Patches`, `Bush_patch/Patches`, `Spirit_Tree_(Farming)/Patches` and `Special_patches/Patches` (Hespori, anima, celastrus, redwood) all list CIR. |
| 5 | AJP | Herbs: Ortus Farm | Ortus Farm herbs (NW); hardwood (SE) | Ortus 1586,3099; 65 W, 89 N. Locus Oasis 1687,2972; 36 E, 38 S | `Farming_runs` herb run: "AJP and run north-west". Hardwood run: "AJP and run south-east to the patch". `Special_patches/Patches` (Locus Oasis hardwood, after The Ribbiting Tale of a Lily Pad Labour Dispute). |
| 6 | AIS | Tree: Nemus | Nemus tree patch; belladonna patch | Nemus 1366,3321; 63 W. Belladonna 1450,3354; 30 N | `Tree_patch/Patches` and `Farming_runs` tree run: AIS, then south-west, cross the river, north-west. `Special_patches/Patches` (Auburnvale belladonna): "AIS, then run north". |
| 7 | CIQ | Fruit tree: Gnome Vill. | Gnome Village fruit tree; Yanille hops | Fruit 2490,3180; 38 W, 53 N. Hops 2576,3105; 48 E, 22 S | `Fruit_tree_patch/Patches` (west of the Tree Gnome maze) lists CIQ. `Hops_patch/Patches` (Yanille) lists CIQ. `Farming_runs` hop run: "CIQ and run south-east". |
| 8 | CKR | Calquat: Tai Bwo Wannai | Tai Bwo Wannai calquat (north) | 2796,3101; 98 N | `Special_patches/Patches` (calquat north of Tai Bwo Wannai) lists CKR. |
| 9 | CJQ | Calquat: Summer Shore | Summer Shore calquat; coral nursery | Calquat 3128,2405; 50 W, 42 S | `Special_patches/Patches`: Summer Shore calquat, "CJQ then run south-west". `Special_patches/Patches` (coral nursery, after Troubled Tortugans) lists CJQ as its teleport. `Farming_runs` fruit tree run: "CJQ and run south (50 Agility) and then west". |
| 10 | CKS | Mushrooms (Canifis) | Mushroom patch beside the ring | 3452,3473; 5 | `Special_patches/Patches` (mushroom, west of Canifis) lists CKS first. |
| 11 | ALS | Hops: McGrubor's Wood | McGrubor's Wood hops patch (north) | 2667,3526; 23 E, 31 N | `Hops_patch/Patches` lists ALS. `Farming_runs` hop run: "ALS and run north". |
| 12 | CKQ | Hops: Aldarin | Aldarin hops patch | 1365,2939; 6 | `Hops_patch/Patches` (Aldarin) lists CKQ. `Farming_runs` hop run lists CKQ. |
| 13 | DJP | Bush: Ardougne | Bush patch south of Ardougne (west) | 2618,3226; 40 W | `Bush_patch/Patches` (south of Ardougne) lists DJP. `Farming_runs` hop and bush run: "DJP and run west". |
| 14 | CIP | Bush: Etceteria | Etceteria bush and spirit tree (east) | Bush 2592,3864; 79 E. Spirit tree 2613,3858; 100 E | `Bush_patch/Patches` and `Spirit_Tree_(Farming)/Patches` (Etceteria) list CIP. `Farming_runs` bush run: "CIP and run east". |

### Considered and left out

- **Weiss herb patch** (DKS, then Larry's boat). It is listed by the wiki, but
  the boat ride makes it slow. Players with Making Friends with My Arm usually
  have the icy basalt.
- **Brimhaven fruit tree** (BJR, then a magic whistle). The wiki lists it last,
  and it needs Holy Grail and a whistle.
- **Tree Gnome Stronghold, Lletya, Catherby, Falador, Taverley, Lumbridge,
  Varrock, Kastori, Prifddinas, Troll Stronghold and Harmony Island** have no
  ring among their listed closest teleports.
- **Tithe Farm** is a minigame, not a patch. AKR is already in the group for
  the vinery.

## Details

Read via `?action=raw` on 2026-10-04. Slayer details name the task monsters
from the location page's monster tables (`LocationMonsterTableLine`) or
monster list, most task-relevant first, and Slayer levels from those tables.
Farming details name the patch types from the `*/Patches` transclusions of
`Farming/Patch_locations`, with counts above one. No compost bins and no tool
leprechauns: every farming spot has them. Where a list is longer than 90
characters, the lowest-level or least-used entries were left off.

### Slayer

| Code | Details | Source |
|---|---|---|
| CKS | Monsters: abyssal demons, nechryael, gargoyles, aberrant spectres, bloodvelds, banshees | `Slayer_Tower` monster tables: basement and floors 0-2 (crawling hands and infernal mages left off). |
| AJR | Monsters: kurasks, turoths, basilisks, jellies, pyrefiends, cockatrices, rockslugs | `Fremennik_Slayer_Dungeon` overview: the eight chambers (cave crawlers left off for length). |
| CIR | Master: Konar (75 combat). Dungeon: hydras, drakes, wyrms, sulphur lizards, fire giants | `Slayer_Master` (Konar quo Maten: 75 combat). `Karuulm_Slayer_Dungeon` monster tables (hellhounds, greater demons and the Alchemical Hydra left off). |
| BKS | Master: Chaeldar (70 combat, Lost City) | `Slayer_Master` (Chaeldar: Lost City and 70 combat). |
| BIQ | Monsters: kalphite workers, soldiers and guardians; Kalphite Queen (Lair) | `Kalphite_Lair` monster list (workers, soldiers, guardians, Queen). `Kalphite_Cave` monster table (workers, soldiers, guardians). |
| BKP | Monsters: smoke devils (93 Slayer); Thermonuclear smoke devil (boss) | `Smoke_Devil_Dungeon` monster table (both 93 Slayer). |
| DJR | Monsters: black, greater and lesser demons (Chasm); lizardmen and shamans (canyon) | `Chasm_of_Fire` monster table. `Lizardman_Canyon`: lizardmen and brutes in both valleys, shamans in the west one. |
| AKQ | Monsters: cave kraken (87 Slayer), waterfiends; Kraken (boss) | `Kraken_Cove` monster table. |
| DIP | Monsters: Abyssal Sire (85 Slayer; on an abyssal demon or boss task) | `Abyssal_Sire`: 85 Slayer; fought only when assigned abyssal demons or the Sire itself. |
| AIQ | Monsters: skeletal wyverns, ice giants, ice warriors, hobgoblins; mogres (shore) | `Asgarnian_Ice_Dungeon` monster table (muggers and pirates left off: not tasks). `Mudskipper_Point`: mogres are driven to the shore. |
| ALP | Monsters: dagannoths (basement, after Horror from the Deep) | `Lighthouse`: after the quest the dungeon is crawling with dagannoth. |
| BJS | Monsters: Zulrah (boss task); 76 Agility stepping stones | `Zulrah` (boss task changes; the stepping stones need 76 Agility, as in the note's source). |
| ALQ | Monsters: araxytes (92 Slayer); Araxxor (boss) | `Morytania_Spider_Cave` (araxytes, Araxxor's cavern). `Araxyte`: 92 Slayer. |
| DKR | Masters: Krystilia, Vannaka. Edgeville Dungeon: hill giants, chaos druids, earth warriors | `Slayer_Master` (Krystilia by the Edgeville jail; Vannaka in Edgeville Dungeon). `Edgeville_Dungeon` monster tables (chaos druids and earth warriors are in its Wilderness part). |

### Farming

| Code | Details | Source |
|---|---|---|
| ALQ | Patches: herb, 2 allotments, flower | `Allotment_patch/Patches` (west of Port Phasmatys). |
| BLR | Patches: herb, 2 allotments, flower | `Allotment_patch/Patches` (north of Ardougne). |
| AKR | Patches: 12 grape vines (vinery); west: herb, 2 allotments, flower, spirit tree | `Special_patches/Patches` (grape, 12 patches, Hosidius Vinery). `Allotment_patch/Patches` (Hosidius). `Spirit_Tree_(Farming)/Patches` (south-west of Hosidius, 1693,3542: 42 W, 13 S of the allotments). The herb, allotments, flower and spirit tree are all west of the ring (91 to 133 tiles), so "west:" covers all four, as the compact card drops the note. |
| CIR | Patches: herb, 2 allotments, flower, bush, cactus, 5 trees (to redwood), anima, Hespori | `Allotment_patch/Patches`, `Bush_patch/Patches`, `Special_patches/Patches` (cactus, anima, Hespori, celastrus, redwood), `Tree_patch/Patches`, `Fruit_tree_patch/Patches`, `Spirit_Tree_(Farming)/Patches`: all list the Farming Guild. The 5 trees are tree, fruit tree, spirit tree, celastrus and redwood. |
| AJP | Patches: herb, 2 allotments, flower (Ortus Farm); hardwood tree (Locus Oasis) | `Allotment_patch/Patches` (Ortus Farm). `Special_patches/Patches` and `Locus_Oasis` (one hardwood patch). |
| AIS | Patches: tree (Nemus Retreat); belladonna (Auburnvale) | `Tree_patch/Patches`, `Nemus_Retreat`. `Special_patches/Patches`, `Auburnvale`. |
| CIQ | Patches: fruit tree (Gnome Village); hops (Yanille) | `Fruit_tree_patch/Patches`, `Hops_patch/Patches`. |
| CKR | Patches: calquat | `Special_patches/Patches`, `Tai_Bwo_Wannai`. |
| CJQ | Patches: calquat; 2 coral nurseries (after Troubled Tortugans) | `Special_patches/Patches` (Summer Shore calquat; coral nursery). `Coral_nursery_(patch)`: two nurseries after Troubled Tortugans. |
| CKS | Patches: mushroom | `Special_patches/Patches` (west of Canifis). |
| ALS | Patches: hops | `Hops_patch/Patches` (McGrubor's Wood). |
| CKQ | Patches: hops | `Hops_patch/Patches`. `Aldarin`: the hops patch is next to the ring. |
| DJP | Patches: bush | `Bush_patch/Patches` (south of Ardougne). |
| CIP | Patches: bush, spirit tree | `Bush_patch/Patches`, `Spirit_Tree_(Farming)/Patches`, `Etceteria` (its vegetable and flower patches are kingdom-only). |
