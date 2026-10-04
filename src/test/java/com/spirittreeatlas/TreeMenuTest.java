/*
 * Copyright (c) 2026, Dewdul
 * All rights reserved.
 * Licensed under the BSD 2-Clause License. See LICENSE.
 */
package com.spirittreeatlas;

import com.google.gson.Gson;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.WidgetPositionMode;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * The menu (DESIGN 3.3, 4.3): row parsing for both menus and the forms other plugins write; the
 * Map mode geometry for today's 15 rows and a 12-row list, modern and classic; and the changes
 * against fake menus: exactly recorded, put back exactly, never showing what we did not hide,
 * never touching the key listeners.
 */
public class TreeMenuTest
{
	private static final String GREY = "5f5f5f";
	private static final List<Tree> TREES = TreeRepository.load(new Gson(), "/fixtures/").getTrees();

	private static TreeMenu.Row parse(String raw)
	{
		return TreeMenu.parseRow(0, raw, null, TREES, GREY);
	}

	// ------------------------------------------------------------------ parsing

	@Test
	public void modernRows()
	{
		TreeMenu.Row r = parse("<col=ffffff>4</col>: Grand Exchange");
		assertEquals("4", r.getKey());
		assertEquals("Grand Exchange", r.getLabel());
		assertEquals("GRAND_EXCHANGE", r.getTreeId());
		assertFalse(r.isGrey());
		assertEquals("TREE_GNOME_VILLAGE", parse("<col=ffffff>1</col>: Tree Gnome Village").getTreeId());
		assertEquals("LAGUNA_AURORAE", parse("<col=ffffff>E</col>: Laguna Aurorae").getTreeId());
	}

	@Test
	public void classicRows()
	{
		TreeMenu.Row r = parse("<col=735a28>D</col>: Poison Waste");
		assertEquals("D", r.getKey());
		assertEquals("POISON_WASTE", r.getTreeId());
		assertFalse(r.isGrey());
	}

	@Test
	public void greyRowsWithAndWithoutTheClosingTag()
	{
		TreeMenu.Row modern = parse("<col=ffffff>6</col>: <col=5f5f5f>Prifddinas");
		assertTrue(modern.isGrey());
		assertEquals("6", modern.getKey());
		assertEquals("PRIFDDINAS", modern.getTreeId());
		TreeMenu.Row classic = parse("<col=735a28>7</col>: <col=5F5F5F>Port Sarim</col>");
		assertTrue(classic.isGrey());
		assertEquals("PORT_SARIM", classic.getTreeId());
		// Better Teleport Menu with no hotkey bound: no key, the grey tag first
		TreeMenu.Row bare = parse("<col=5f5f5f>Feldip Hills");
		assertTrue(bare.isGrey());
		assertNull(bare.getKey());
		assertEquals("FELDIP_HILLS", bare.getTreeId());
	}

	@Test
	public void theHouseMatchesByPrefixWhateverItsTown()
	{
		TreeMenu.Row r = parse("<col=ffffff>C</col>: Your house (Rimmington)");
		assertEquals("C", r.getKey());
		assertEquals("YOUR_HOUSE", r.getTreeId());
		// a house in Prifddinas is still the house, not Prifddinas
		assertEquals("YOUR_HOUSE", parse("<col=735a28>C</col>: Your house (Prifddinas)").getTreeId());
		assertEquals("YOUR_HOUSE", parse("<col=735a28>C</col>: Your house").getTreeId());
	}

	@Test
	public void cancelAndUnknownRowsMapToNoTree()
	{
		TreeMenu.Row cancel = parse("<col=ffffff>F</col>: Cancel");
		assertEquals("F", cancel.getKey());
		assertNull(cancel.getTreeId());
		assertNull(parse("<col=ffffff>G</col>: Somewhere new").getTreeId());
		assertNull(parse(""));
		assertNull(parse("<col=ffffff></col>"));
		assertNull(parse(null));
	}

	@Test
	public void betterTeleportMenuForms()
	{
		// a rebound key, with its highlight tag
		TreeMenu.Row bound = parse("<col=735a28>G</col>: <shad=ffffff>Grand Exchange");
		assertEquals("G", bound.getKey());
		assertEquals("GRAND_EXCHANGE", bound.getTreeId());
		// no key at all
		TreeMenu.Row none = parse("Battlefield of Khazard");
		assertNull(none.getKey());
		assertEquals("BATTLEFIELD_OF_KHAZARD", none.getTreeId());
		// a key too long to be one: the label is found inside the whole text
		TreeMenu.Row multi = parse("<col=735a28>Shift+1, 2</col>: Battlefield of Khazard");
		assertNull(multi.getKey());
		assertEquals("BATTLEFIELD_OF_KHAZARD", multi.getTreeId());
		// six characters still are a key
		assertEquals("Ctrl+1", parse("<col=735a28>Ctrl+1</col>: Hosidius").getKey());
		// inside the whole text, the house beats Prifddinas (same length, earlier)
		assertEquals("YOUR_HOUSE", parse("<col=735a28>Shift+C, D</col>: Your house (Prifddinas)").getTreeId());
	}

	@Test
	public void caseAndWhitespaceDoNotMatter()
	{
		TreeMenu.Row r = parse("<col=ffffff>2</col>:  gnome   STRONGHOLD ");
		assertEquals("GNOME_STRONGHOLD", r.getTreeId());
		assertEquals("gnome STRONGHOLD", r.getLabel());
	}

	@Test
	public void pleaseWaitKeepsTheRowsMapping()
	{
		TreeMenu.Row before = TreeMenu.parseRow(3, "<col=ffffff>4</col>: Grand Exchange", null, TREES, GREY);
		assertSame(before, TreeMenu.parseRow(3, "Please wait...", before, TREES, GREY));
		// another row's mapping is not taken
		TreeMenu.Row other = TreeMenu.parseRow(4, "Please wait...", before, TREES, GREY);
		assertNull(other.getTreeId());
		assertEquals(4, other.getIndex());
	}

	@Test
	public void wholeListsOfBothMenus()
	{
		List<String> modern = new ArrayList<>();
		List<String> classic = new ArrayList<>();
		for (int i = 0; i < FakeMenu.OPTIONS.length; i++)
		{
			modern.add(FakeMenu.modernRow(i, FakeMenu.OPTIONS[i]));
			classic.add(FakeMenu.classicRow(i, FakeMenu.OPTIONS[i]));
		}
		for (List<String> texts : Arrays.asList(modern, classic))
		{
			List<TreeMenu.Row> rows = TreeMenu.parseRows(texts, Collections.emptyList(), TREES, GREY);
			assertEquals(15, rows.size());
			for (int i = 0; i < 14; i++)
			{
				assertEquals(i, rows.get(i).getIndex());
				assertEquals(TREES.get(i).getId(), rows.get(i).getTreeId());
				assertEquals(String.valueOf(FakeMenu.KEYS.charAt(i)), rows.get(i).getKey());
			}
			assertNull(rows.get(14).getTreeId());
		}
		// a row mid-teleport keeps its tree across a re-read; children that are not text are skipped
		List<TreeMenu.Row> first = TreeMenu.parseRows(modern, Collections.emptyList(), TREES, GREY);
		List<String> later = new ArrayList<>(modern);
		later.set(3, "Please wait...");
		later.set(5, null);
		List<TreeMenu.Row> again = TreeMenu.parseRows(later, first, TREES, GREY);
		assertEquals(14, again.size());
		assertEquals("GRAND_EXCHANGE", again.get(3).getTreeId());
	}

	@Test
	public void titles()
	{
		assertTrue(TreeMenu.isTitle("Spirit Tree Locations", "Spirit Tree Locations"));
		assertTrue(TreeMenu.isTitle(TreeMenu.clean("<col=ff981f>spirit tree  locations</col> "), "Spirit Tree Locations"));
		assertFalse(TreeMenu.isTitle("Where would you like to teleport to?", "Spirit Tree Locations"));
		assertFalse(TreeMenu.isTitle(null, "Spirit Tree Locations"));
	}

	// ------------------------------------------------------------------ geometry

	/** CONTENT_SCROLL's place in UNIVERSE and size, as the cache's modes lay them out for a UNIVERSE this size. */
	private static TreeMenu.Geometry modernFor(int universeW, int universeH)
	{
		// CONTENT_FRAME: centre/bottom, (0, 6), minus 12 x minus 56; CONTENT: centre/top, (0, 2), minus 4 x minus 2
		int frameW = universeW - 12;
		int frameH = universeH - 56;
		int frameX = (universeW - frameW) / 2;
		int frameY = universeH - frameH - 6;
		int contentW = frameW - 4;
		int contentX = (frameW - contentW) / 2;
		return TreeMenu.modern(512, 334, frameX + contentX, frameY + 2, contentW, 161, 20, 0, 0);
	}

	@Test
	public void modernGeometryForFifteenAndTwelveRows()
	{
		// 15 entries: 8/7 rows, UNIVERSE 338x218; 12 entries: 6/6 rows, UNIVERSE 338x178 (DESIGN 2.2)
		for (int[] universe : new int[][]{{338, 218}, {338, 178}})
		{
			TreeMenu.Geometry g = modernFor(universe[0], universe[1]);
			String at = universe[0] + "x" + universe[1];
			// UNIVERSE hangs below the slot: its title strip and first row line show in the corner
			assertEquals(at, new Point(174, 256), g.getRoot());
			// the top row of the last column, CONTENT_SCROLL-relative
			assertEquals(at, new Point(161, 0), g.getRow());
			assertEquals(at, new Rectangle(343, 308, 161, 20), g.getCell());
			assertEquals(at, 512 - TreeMenu.RIGHT, g.getCell().x + g.getCell().width);
			assertEquals(at, 334 - TreeMenu.BOTTOM, g.getCell().y + g.getCell().height);
			assertNull(g.getClose());
			// the close button rides on UNIVERSE at (W-44, 17): just above the cell's right end
			Rectangle close = new Rectangle(g.getRoot().x + universe[0] - 44, g.getRoot().y + 17, 26, 23);
			assertEquals(at, new Rectangle(468, 273, 26, 23), close);
			assertTrue(at, new Rectangle(0, 0, 512, 334).contains(close));
			assertTrue(at, close.y + close.height < g.getCell().y);
			assertTrue(at, close.x > g.getCell().x + g.getCell().width / 2 && close.x + close.width <= g.getCell().x + g.getCell().width);
			// what shows of UNIVERSE inside the slot: the 51 px title strip, the 20 px row line and the 6 px margin
			assertEquals(at, 78, 334 - g.getRoot().y);
		}
		// scrolled: the cell keeps its place on screen, the row moves with the scroll
		TreeMenu.Geometry scrolled = TreeMenu.modern(512, 334, 8, 52, 322, 161, 20, 30, 0);
		assertEquals(new Point(191, 0), scrolled.getRow());
		assertEquals(new Rectangle(343, 308, 161, 20), scrolled.getCell());
	}

	@Test
	public void classicGeometryForFifteenAndTwelveRows()
	{
		// 15 rows scroll the 232 px list by up to 8 px; 12 rows do not scroll
		for (int scrollY : new int[]{0, 8})
		{
			TreeMenu.Geometry g = TreeMenu.classic(512, 334, 386, 16, scrollY);
			assertEquals(new Point(226, 312), g.getRoot());
			assertEquals(new Point(0, scrollY), g.getRow());
			// the middle 170 px of the centred row line
			assertEquals(new Rectangle(334, 312, 170, 16), g.getCell());
			assertEquals(g.getRoot().x + 386 / 2, (int) g.getCell().getCenterX());
			assertEquals(new Point(478, 285), g.getClose());
			Rectangle close = new Rectangle(g.getClose().x, g.getClose().y, 26, 23);
			assertEquals(4, g.getCell().y - (close.y + close.height));
			assertEquals(g.getCell().x + g.getCell().width, close.x + close.width);
		}
	}

	// ------------------------------------------------------------------ changes against fake menus

	private static TreeMenu open(FakeMenu f, TreeMenu.Style style)
	{
		TreeMenu menu = new TreeMenu(f.client);
		assertEquals(style, menu.openStyle());
		assertEquals(FakeMenu.TITLE, menu.title(style));
		menu.open(style);
		menu.rebuilt(TREES, GREY);
		assertEquals(15, menu.getRows().size());
		return menu;
	}

	private static TreeMenu.Row row(TreeMenu menu, String treeId)
	{
		for (TreeMenu.Row r : menu.getRows())
		{
			if (treeId.equals(r.getTreeId()))
			{
				return r;
			}
		}
		return null;
	}

	/** Snapshot of every widget's hidden flag and position fields, to compare after a restore. */
	private static List<String> snapshot(FakeMenu f)
	{
		List<String> out = new ArrayList<>();
		for (FakeMenu.W w : f.all())
		{
			out.add(w.id + "/" + w.index + " " + w.hidden + " " + Arrays.toString(w.position()) + " " + w.relX + "," + w.relY);
		}
		Collections.sort(out);
		return out;
	}

	private static void assertKeyListenersUntouched(FakeMenu f, int keys)
	{
		FakeMenu.W k = f.get(keys);
		assertEquals(0, k.writes);
		for (FakeMenu.W c : k.children)
		{
			assertEquals(0, c.writes);
			assertFalse(c.hidden);
		}
	}

	@Test
	public void modernMapModeAndRestore()
	{
		FakeMenu f = FakeMenu.modern(FakeMenu.OPTIONS);
		TreeMenu menu = open(f, TreeMenu.Style.MODERN);
		// another plugin hides Port Sarim's row after the rows were read
		f.get(InterfaceID.MenuNew.TEXT).children[6].hidden = true;
		List<String> before = snapshot(f);
		assertEquals(FakeMenu.SLOT, menu.slotBounds());
		// List mode's anchor: UNIVERSE's top-left, centred in the slot
		assertEquals(new Point(100 + 87, 200 + 58), menu.anchor());

		TreeMenu.Row ge = row(menu, "GRAND_EXCHANGE");
		menu.apply(ge);
		FakeMenu.W universe = f.get(InterfaceID.MenuNew.UNIVERSE);
		assertArrayEquals(new int[]{WidgetPositionMode.ABSOLUTE_LEFT, WidgetPositionMode.ABSOLUTE_TOP, 174, 256}, universe.position());
		FakeMenu.W[] text = f.get(InterfaceID.MenuNew.TEXT).children;
		FakeMenu.W[] graphics = f.get(InterfaceID.MenuNew.GRAPHICS).children;
		for (int i = 0; i < text.length; i++)
		{
			assertEquals("row " + i, i != 3, text[i].hidden);
			assertEquals("row " + i, i != 3, graphics[i].hidden);
		}
		// the shown row is in the cell, which is in the slot's bottom-right corner
		Rectangle cell = new Rectangle(100 + 343, 200 + 308, 161, 20);
		assertEquals(cell, text[3].canvas());
		assertEquals(cell, graphics[3].canvas());
		assertEquals(cell, menu.rowCell(ge));
		assertEquals(new Rectangle(100 + 468, 200 + 273, 26, 23), menu.closeRect());
		assertEquals("GRAND_EXCHANGE", menu.liveRow(ge, TREES, GREY).getTreeId());
		// UNIVERSE keeps its size: nothing is resized
		assertEquals(338, universe.w);
		assertEquals(218, universe.h);

		// applying again writes nothing
		int writes = universe.writes + text[3].writes;
		menu.apply(ge);
		assertEquals(writes, universe.writes + text[3].writes);

		// another selection: Grand Exchange goes back and hides, Feldip Hills comes out
		TreeMenu.Row feldip = row(menu, "FELDIP_HILLS");
		menu.apply(feldip);
		assertTrue(text[3].hidden);
		assertArrayEquals(new int[]{0, 0, 0, 60}, text[3].position());
		assertFalse(text[4].hidden);
		assertEquals(cell, text[4].canvas());

		// a row someone else hid is never shown, even as the Travel row
		TreeMenu.Row sarim = row(menu, "PORT_SARIM");
		menu.apply(sarim);
		assertTrue(text[6].hidden);
		assertNull(menu.liveRow(sarim, TREES, GREY));

		// nothing shown: every row hidden, the cell still known
		menu.apply(null);
		for (FakeMenu.W t : text)
		{
			assertTrue(t.hidden);
		}
		assertEquals(cell, menu.rowCell(null));
		// read again, the row it hid is no longer listed; ours still are
		assertTrue(menu.read(TREES, GREY));
		assertNull(row(menu, "PORT_SARIM"));
		assertEquals(14, menu.getRows().size());

		menu.restore();
		assertFalse(menu.isChanged());
		assertEquals(before, snapshot(f));
		assertTrue(text[6].hidden);
		assertKeyListenersUntouched(f, InterfaceID.MenuNew.KEYLISTENERS);
	}

	@Test
	public void modernRebuildDropsTheOldRowsRecords()
	{
		FakeMenu f = FakeMenu.modern(FakeMenu.OPTIONS);
		TreeMenu menu = open(f, TreeMenu.Style.MODERN);
		menu.apply(row(menu, "GRAND_EXCHANGE"));
		FakeMenu.W[] old = f.get(InterfaceID.MenuNew.TEXT).children;
		// the setup script runs again: cc_deleteall, then new rows (12 this time)
		FakeMenu g = FakeMenu.modern(Arrays.copyOf(FakeMenu.OPTIONS, 12));
		for (int id : new int[]{InterfaceID.MenuNew.TEXT, InterfaceID.MenuNew.GRAPHICS})
		{
			f.get(id).children = new FakeMenu.W[0];
			for (FakeMenu.W c : g.get(id).children)
			{
				f.child(f.get(id), c.index, c.type, c.x, c.y, c.w, c.h, c.text);
			}
		}
		int[] oldWrites = new int[old.length];
		for (int i = 0; i < old.length; i++)
		{
			oldWrites[i] = old[i].writes;
		}
		menu.rebuilt(TREES, GREY);
		assertEquals(12, menu.getRows().size());
		menu.apply(row(menu, "HOSIDIUS"));
		FakeMenu.W[] text = f.get(InterfaceID.MenuNew.TEXT).children;
		assertFalse(text[9].hidden);
		assertTrue(text[3].hidden);
		menu.restore();
		for (int i = 0; i < old.length; i++)
		{
			assertEquals("old row " + i, oldWrites[i], old[i].writes);
		}
		for (FakeMenu.W t : text)
		{
			assertFalse(t.hidden);
		}
		assertArrayEquals(new int[]{WidgetPositionMode.ABSOLUTE_CENTER, WidgetPositionMode.ABSOLUTE_CENTER, 0, 0},
			f.get(InterfaceID.MenuNew.UNIVERSE).position());
	}

	@Test
	public void aWidgetThatIsNoLongerLiveIsNotRestored()
	{
		FakeMenu f = FakeMenu.modern(FakeMenu.OPTIONS);
		TreeMenu menu = open(f, TreeMenu.Style.MODERN);
		menu.apply(null);
		FakeMenu.W universe = f.get(InterfaceID.MenuNew.UNIVERSE);
		int writes = universe.writes;
		// the interface was reloaded: a new UNIVERSE object under the same id
		f.add(InterfaceID.MenuNew.UNIVERSE, f.get(InterfaceID.MenuNew.INFINITE), 1, 1, 0, 0, 338, 218);
		menu.restore();
		assertEquals(writes, universe.writes);
		assertEquals(174, universe.x);
	}

	@Test
	public void classicMapModeAndRestore()
	{
		FakeMenu f = FakeMenu.classic(FakeMenu.OPTIONS);
		f.get(InterfaceID.Menu.LJ_LAYER1).scrollY = 8;
		List<String> before = snapshot(f);
		TreeMenu menu = open(f, TreeMenu.Style.CLASSIC);
		assertEquals(new Point(100 + 55, 200 + 37), menu.anchor());
		TreeMenu.Row ge = row(menu, "GRAND_EXCHANGE");
		menu.apply(ge);
		assertTrue(f.get(InterfaceID.Menu.LJ_LAYER2).hidden);
		assertTrue(f.get(InterfaceID.Menu.LJ_SCROLL_BAR).hidden);
		FakeMenu.W list = f.get(InterfaceID.Menu.LJ_LAYER1);
		assertArrayEquals(new int[]{WidgetPositionMode.ABSOLUTE_LEFT, WidgetPositionMode.ABSOLUTE_TOP, 226, 312}, list.position());
		assertEquals(386, list.w);
		FakeMenu.W[] rows = list.children;
		for (int i = 0; i < rows.length; i++)
		{
			assertEquals("row " + i, i != 3, rows[i].hidden);
		}
		// the row moved to the list's scroll position, its centred x kept
		assertArrayEquals(new int[]{WidgetPositionMode.ABSOLUTE_CENTER, WidgetPositionMode.ABSOLUTE_TOP, 0, 8}, rows[3].position());
		Rectangle cell = new Rectangle(100 + 334, 200 + 312, 170, 16);
		assertTrue(rows[3].canvas().contains(cell));
		assertEquals(cell, menu.rowCell(ge));
		assertArrayEquals(new int[]{WidgetPositionMode.ABSOLUTE_LEFT, WidgetPositionMode.ABSOLUTE_TOP, 478, 285},
			f.get(InterfaceID.Menu.ROOT_GRAPHIC3).position());
		assertEquals(new Rectangle(100 + 478, 200 + 285, 26, 23), menu.closeRect());
		// the title is still read from the hidden parchment layer
		assertEquals(FakeMenu.TITLE, menu.title(TreeMenu.Style.CLASSIC));

		menu.restore();
		assertEquals(before, snapshot(f));
		assertKeyListenersUntouched(f, InterfaceID.Menu.KEYLISTENERS);
	}

	@Test
	public void classicNeverShowsWhatItDidNotHide()
	{
		FakeMenu f = FakeMenu.classic(FakeMenu.OPTIONS);
		// hidden by the game or another plugin before we came
		f.get(InterfaceID.Menu.LJ_SCROLL_BAR).hidden = true;
		TreeMenu menu = open(f, TreeMenu.Style.CLASSIC);
		menu.apply(null);
		menu.restore();
		assertTrue(f.get(InterfaceID.Menu.LJ_SCROLL_BAR).hidden);
		assertFalse(f.get(InterfaceID.Menu.LJ_LAYER2).hidden);
	}

	@Test
	public void aMoveTheGameRedidIsNotUndone()
	{
		FakeMenu f = FakeMenu.classic(FakeMenu.OPTIONS);
		TreeMenu menu = open(f, TreeMenu.Style.CLASSIC);
		menu.apply(null);
		FakeMenu.W list = f.get(InterfaceID.Menu.LJ_LAYER1);
		// proc 219 runs again and sets the list's place itself
		list.x = 55;
		list.y = 71;
		int writes = list.writes;
		menu.restore();
		assertEquals(writes, list.writes);
		assertEquals(71, list.y);
		// and a re-apply records that place as the one to go back to
		menu.apply(null);
		assertEquals(312, list.y);
		menu.restore();
		assertEquals(71, list.y);
	}

	@Test
	public void openStyleNeedsAMountedVisibleMenu()
	{
		FakeMenu f = FakeMenu.modern(FakeMenu.OPTIONS);
		TreeMenu menu = new TreeMenu(f.client);
		assertEquals(TreeMenu.Style.MODERN, menu.openStyle());
		assertFalse(menu.isOpen(TreeMenu.Style.CLASSIC));
		f.slot.hidden = true;
		assertNull(menu.openStyle());
		assertNull(new TreeMenu(new FakeMenu().client).openStyle());
		assertNotNull(TreeMenu.Style.forScript(9142));
		assertEquals(TreeMenu.Style.CLASSIC, TreeMenu.Style.forScript(217));
		assertEquals(TreeMenu.Style.CLASSIC, TreeMenu.Style.forGroup(187));
		assertNull(TreeMenu.Style.forScript(219));
		// menu_indexed builds in the classic menu too: it may replace ours without a close
		assertEquals(TreeMenu.Style.CLASSIC, TreeMenu.Style.forScript(TreeMenu.MENU_INDEXED));
	}

	@Test
	public void rowsAreReadAgainCheaplyAndFollowARebind()
	{
		FakeMenu f = FakeMenu.modern(FakeMenu.OPTIONS);
		TreeMenu menu = open(f, TreeMenu.Style.MODERN);
		menu.apply(row(menu, "GRAND_EXCHANGE"));
		// nothing changed, rows hidden by us for the map included: nothing to do
		assertFalse(menu.read(TREES, GREY));
		assertEquals(15, menu.getRows().size());
		// Better Teleport Menu rebinds Grand Exchange to G while the menu is open
		FakeMenu.W[] text = f.get(InterfaceID.MenuNew.TEXT).children;
		text[3].text = "<col=ffffff>G</col>: Grand Exchange";
		assertTrue(menu.read(TREES, GREY));
		assertEquals("G", row(menu, "GRAND_EXCHANGE").getKey());
		assertFalse(menu.read(TREES, GREY));
		// mid-teleport the row keeps its tree and key
		text[3].text = "Please wait...";
		menu.read(TREES, GREY);
		assertEquals("G", row(menu, "GRAND_EXCHANGE").getKey());
		assertEquals(3, row(menu, "GRAND_EXCHANGE").getIndex());
	}

	@Test
	public void aRowSomeoneElseHidIsNotListed()
	{
		FakeMenu f = FakeMenu.classic(FakeMenu.OPTIONS);
		f.get(InterfaceID.Menu.LJ_LAYER1).children[6].hidden = true;
		TreeMenu menu = new TreeMenu(f.client);
		menu.open(TreeMenu.Style.CLASSIC);
		menu.rebuilt(TREES, GREY);
		assertEquals(14, menu.getRows().size());
		assertNull(row(menu, "PORT_SARIM"));
		// the rows we hide for the map stay listed
		menu.apply(row(menu, "HOSIDIUS"));
		assertFalse(menu.read(TREES, GREY));
		assertNotNull(row(menu, "GRAND_EXCHANGE"));
		assertTrue(f.get(InterfaceID.Menu.LJ_LAYER1).children[3].hidden);
		// someone shows it again: listed again
		f.get(InterfaceID.Menu.LJ_LAYER1).children[6].hidden = false;
		assertTrue(menu.read(TREES, GREY));
		assertNotNull(row(menu, "PORT_SARIM"));
	}

	@Test
	public void aRebuildOnTheGamesOwnStateNeverShowsWhatOthersHid()
	{
		FakeMenu f = FakeMenu.classic(FakeMenu.OPTIONS);
		TreeMenu menu = open(f, TreeMenu.Style.CLASSIC);
		menu.apply(row(menu, "GRAND_EXCHANGE"));
		FakeMenu.W bar = f.get(InterfaceID.Menu.LJ_SCROLL_BAR);
		assertTrue(bar.hidden);
		// before the setup script runs again, everything is put back...
		menu.restore();
		assertFalse(bar.hidden);
		// ...so what is hidden during the rebuild is not taken for ours (Better Teleport Menu's
		// "Expand scroll menu" hides the scrollbar at ScriptPostFired(217) when the list fits)
		f.rebuild(Arrays.copyOf(FakeMenu.OPTIONS, 12));
		bar.hidden = true;
		menu.rebuilt(TREES, GREY);
		assertEquals(12, menu.getRows().size());
		menu.apply(row(menu, "HOSIDIUS"));
		assertFalse(f.get(InterfaceID.Menu.LJ_LAYER1).children[9].hidden);
		menu.close();
		assertTrue(bar.hidden);
		assertFalse(f.get(InterfaceID.Menu.LJ_LAYER2).hidden);
		for (FakeMenu.W r : f.get(InterfaceID.Menu.LJ_LAYER1).children)
		{
			assertFalse(r.hidden);
			assertEquals(WidgetPositionMode.ABSOLUTE_TOP, r.yMode);
			assertEquals(16 * r.index, r.y);
		}
		assertArrayEquals(new int[]{WidgetPositionMode.ABSOLUTE_LEFT, WidgetPositionMode.ABSOLUTE_TOP, 55, 70},
			f.get(InterfaceID.Menu.LJ_LAYER1).position());
		assertNull(menu.getStyle());
	}
}
