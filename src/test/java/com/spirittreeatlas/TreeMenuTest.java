/*
 * Copyright (c) 2026, Dewdul
 * All rights reserved.
 * Licensed under the BSD 2-Clause License. See LICENSE.
 */
package com.spirittreeatlas;

import com.google.gson.Gson;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.WidgetPositionMode;
import net.runelite.api.widgets.WidgetSizeMode;
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
 * Map mode geometry for today's 15 rows and a 12-row list, modern and classic, with the Travel
 * row made button-sized; and the changes against fake menus: exactly recorded, put back exactly
 * (sizes and size modes too), never showing what we did not hide, never resizing anything but the
 * Travel row, never touching the key listeners.
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

	/**
	 * CONTENT_SCROLL's place in UNIVERSE and size, as the cache's modes lay them out for a UNIVERSE
	 * this size, and the close button's right end (TITLE: centre/top, minus 12 wide; the button 12
	 * px inside its right end).
	 */
	private static TreeMenu.Geometry modernFor(int universeW, int universeH)
	{
		// CONTENT_FRAME: centre/bottom, (0, 6), minus 12 x minus 56; CONTENT: centre/top, (0, 2), minus 4 x minus 2
		int frameW = universeW - 12;
		int frameH = universeH - 56;
		int frameX = (universeW - frameW) / 2;
		int frameY = universeH - frameH - 6;
		int contentW = frameW - 4;
		int contentX = (frameW - contentW) / 2;
		int closeRight = 6 + (universeW - 12) - TreeMenu.MODERN_CLOSE_INSET;
		return TreeMenu.modern(512, 334, frameX + contentX, frameY + 2, contentW, frameH - 2, closeRight, 0, 0);
	}

	/** The button pair: the close button's bottom just above the cell, their right ends aligned, both in the slot. */
	private static void assertButtonPair(String at, Rectangle cell, Rectangle close)
	{
		Rectangle slot = new Rectangle(0, 0, 512, 334);
		assertTrue(at, slot.contains(cell));
		assertTrue(at, slot.contains(close));
		assertFalse(at, close.intersects(cell));
		assertEquals(at, TreeMenu.CLOSE_GAP, cell.y - (close.y + close.height));
		assertEquals(at, cell.x + cell.width, close.x + close.width);
		assertEquals(at, 512 - TreeMenu.RIGHT, cell.x + cell.width);
		assertEquals(at, 334 - TreeMenu.BOTTOM, cell.y + cell.height);
	}

	@Test
	public void modernGeometryForFifteenAndTwelveRows()
	{
		// 15 entries: 8/7 rows, UNIVERSE 338x218; 12 entries: 6/6 rows, UNIVERSE 338x178 (DESIGN 2.2)
		for (int[] universe : new int[][]{{338, 218}, {338, 178}})
		{
			TreeMenu.Geometry g = modernFor(universe[0], universe[1]);
			String at = universe[0] + "x" + universe[1];
			// UNIVERSE hangs below the slot: its title strip and the button show in the corner
			assertEquals(at, new Point(184, 244), g.getRoot());
			// the Travel button, 200x32, at the top of the scroll area, its right end under the close button's
			assertEquals(at, new Dimension(TreeMenu.TRAVEL_W, TreeMenu.TRAVEL_H), g.getRowSize());
			assertEquals(at, new Point(112, 0), g.getRow());
			assertEquals(at, new Rectangle(304, 296, 200, 32), g.getCell());
			assertNull(g.getClose());
			// the close button rides on UNIVERSE at (W-44, 17): just above the cell's right end
			Rectangle close = new Rectangle(g.getRoot().x + universe[0] - 44, g.getRoot().y + 17, 26, 23);
			assertEquals(at, new Rectangle(478, 261, 26, 23), close);
			assertButtonPair(at, g.getCell(), close);
			// the button stays inside CONTENT_SCROLL (UNIVERSE (8, 52), 322 wide)
			assertTrue(at, g.getRow().x >= 0 && g.getRow().x + 200 <= 322);
			// what shows of UNIVERSE inside the slot: the 52 px title strip, the 32 px button and the 6 px margin
			assertEquals(at, 90, 334 - g.getRoot().y);
		}
		// scrolled: the cell keeps its place on screen, the row moves with the scroll
		TreeMenu.Geometry scrolled = TreeMenu.modern(512, 334, 8, 52, 322, 160, 320, 30, 0);
		assertEquals(new Point(142, 0), scrolled.getRow());
		assertEquals(new Rectangle(304, 296, 200, 32), scrolled.getCell());
		// close button unknown: the button ends at the scroll area's right end, the cell keeps its place
		TreeMenu.Geometry unknown = TreeMenu.modern(512, 334, 8, 52, 322, 160, 0, 0, 0);
		assertEquals(new Point(174, 244), unknown.getRoot());
		assertEquals(new Point(122, 0), unknown.getRow());
		assertEquals(new Rectangle(304, 296, 200, 32), unknown.getCell());
		// a scroll area smaller than the button: the button is what fits, still in the corner
		TreeMenu.Geometry small = TreeMenu.modern(512, 334, 8, 52, 150, 20, 140, 0, 0);
		assertEquals(new Dimension(150, 20), small.getRowSize());
		assertEquals(new Point(0, 0), small.getRow());
		assertEquals(new Rectangle(354, 308, 150, 20), small.getCell());
	}

	@Test
	public void classicGeometryForFifteenAndTwelveRows()
	{
		// 15 rows scroll the 232 px list by up to 8 px; 12 rows do not scroll
		for (int scrollY : new int[]{0, 8})
		{
			TreeMenu.Geometry g = TreeMenu.classic(512, 334, 386, 232, scrollY);
			assertEquals(new Point(211, 296), g.getRoot());
			assertEquals(new Point(0, scrollY), g.getRow());
			// the row keeps its full width (minus 0 of the list) and becomes 32 tall
			assertEquals(new Dimension(386, TreeMenu.TRAVEL_H), g.getRowSize());
			// the middle 200 px of the centred row line: the same corner as the modern menu
			assertEquals(new Rectangle(304, 296, 200, 32), g.getCell());
			assertEquals(g.getRoot().x + 386 / 2, (int) g.getCell().getCenterX());
			assertEquals(new Point(478, 261), g.getClose());
			assertButtonPair("scroll " + scrollY, g.getCell(), new Rectangle(g.getClose().x, g.getClose().y, 26, 23));
		}
		// Better Teleport Menu's "Expand scroll menu": the slot and the list 8 px taller; the corner follows
		TreeMenu.Geometry tall = TreeMenu.classic(512, 342, 386, 240, 0);
		assertEquals(new Rectangle(304, 304, 200, 32), tall.getCell());
		assertEquals(new Point(478, 269), tall.getClose());
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

	/** Snapshot of every widget's hidden flag, position and size fields and layout, to compare after a restore. */
	private static List<String> snapshot(FakeMenu f)
	{
		List<String> out = new ArrayList<>();
		for (FakeMenu.W w : f.all())
		{
			out.add(w.id + "/" + w.index + " " + w.hidden + " " + Arrays.toString(w.position()) + " " + Arrays.toString(w.size())
				+ " " + w.relX + "," + w.relY + " " + w.w + "x" + w.h);
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
		assertArrayEquals(new int[]{WidgetPositionMode.ABSOLUTE_LEFT, WidgetPositionMode.ABSOLUTE_TOP, 184, 244}, universe.position());
		FakeMenu.W[] text = f.get(InterfaceID.MenuNew.TEXT).children;
		FakeMenu.W[] graphics = f.get(InterfaceID.MenuNew.GRAPHICS).children;
		for (int i = 0; i < text.length; i++)
		{
			assertEquals("row " + i, i != 3, text[i].hidden);
			assertEquals("row " + i, i != 3, graphics[i].hidden);
		}
		// the shown row, text and backing, is the 200x32 button in the cell, in the slot's bottom-right corner
		Rectangle cell = new Rectangle(100 + 304, 200 + 296, 200, 32);
		int[] button = {WidgetSizeMode.ABSOLUTE, WidgetSizeMode.ABSOLUTE, 200, 32};
		assertArrayEquals(button, text[3].size());
		assertArrayEquals(button, graphics[3].size());
		assertEquals(cell, text[3].canvas());
		assertEquals(cell, graphics[3].canvas());
		assertEquals(cell, menu.rowCell(ge));
		// the close button just above the button's right end
		Rectangle close = new Rectangle(100 + 478, 200 + 261, 26, 23);
		assertEquals(close, menu.closeRect());
		assertFalse(close.intersects(cell));
		assertEquals(cell.x + cell.width, close.x + close.width);
		assertEquals("GRAND_EXCHANGE", menu.liveRow(ge, TREES, GREY).getTreeId());
		// nothing else is resized: UNIVERSE keeps its size, the other rows theirs
		assertOnlyResized(f, text[3], graphics[3]);
		assertEquals(338, universe.w);
		assertEquals(218, universe.h);

		// applying again writes nothing
		int writes = universe.writes + text[3].writes;
		menu.apply(ge);
		assertEquals(writes, universe.writes + text[3].writes);

		// another selection: Grand Exchange goes back (place and size) and hides, Feldip Hills comes out
		TreeMenu.Row feldip = row(menu, "FELDIP_HILLS");
		menu.apply(feldip);
		assertTrue(text[3].hidden);
		assertArrayEquals(new int[]{0, 0, 0, 60}, text[3].position());
		int[] entry = {WidgetSizeMode.ABSOLUTE, WidgetSizeMode.ABSOLUTE, 161, 20};
		assertArrayEquals(entry, text[3].size());
		assertArrayEquals(entry, graphics[3].size());
		assertFalse(text[4].hidden);
		assertEquals(cell, text[4].canvas());
		assertEquals(cell, graphics[4].canvas());

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
		assertEquals(200, text[9].w);
		assertEquals(32, text[9].h);
		menu.restore();
		// the old rows' records were dropped: the old Travel row is left as it was, 200x32
		for (int i = 0; i < old.length; i++)
		{
			assertEquals("old row " + i, oldWrites[i], old[i].writes);
		}
		assertEquals(32, old[3].h);
		FakeMenu.W[] graphics = f.get(InterfaceID.MenuNew.GRAPHICS).children;
		for (int i = 0; i < text.length; i++)
		{
			assertFalse(text[i].hidden);
			assertArrayEquals(new int[]{WidgetSizeMode.ABSOLUTE, WidgetSizeMode.ABSOLUTE, 161, 20}, text[i].size());
			assertArrayEquals(new int[]{WidgetSizeMode.ABSOLUTE, WidgetSizeMode.ABSOLUTE, 161, 20}, graphics[i].size());
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
		assertEquals(184, universe.x);
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
		// only the parchment model is hidden: LJ_LAYER2 holds the key listeners, and its title is
		// what Better Teleport Menu checks before it redraws its own parchment
		assertFalse(f.get(InterfaceID.Menu.LJ_LAYER2).hidden);
		assertTrue(f.parchmentModel().hidden);
		assertFalse(f.title().hidden);
		assertTrue(f.get(InterfaceID.Menu.LJ_SCROLL_BAR).hidden);
		assertNull(f.hotkeysBlocked());
		FakeMenu.W list = f.get(InterfaceID.Menu.LJ_LAYER1);
		assertArrayEquals(new int[]{WidgetPositionMode.ABSOLUTE_LEFT, WidgetPositionMode.ABSOLUTE_TOP, 211, 296}, list.position());
		assertEquals(386, list.w);
		assertEquals(232, list.h);
		FakeMenu.W[] rows = list.children;
		for (int i = 0; i < rows.length; i++)
		{
			assertEquals("row " + i, i != 3, rows[i].hidden);
		}
		// the row moved to the list's scroll position, its centred x kept, 32 tall, its width still the list's minus 0
		assertArrayEquals(new int[]{WidgetPositionMode.ABSOLUTE_CENTER, WidgetPositionMode.ABSOLUTE_TOP, 0, 8}, rows[3].position());
		assertArrayEquals(new int[]{WidgetSizeMode.MINUS, WidgetSizeMode.ABSOLUTE, 0, 32}, rows[3].size());
		assertEquals(new Rectangle(100 + 211, 200 + 296, 386, 32), rows[3].canvas());
		Rectangle cell = new Rectangle(100 + 304, 200 + 296, 200, 32);
		assertTrue(rows[3].canvas().contains(cell));
		assertEquals(cell, menu.rowCell(ge));
		assertArrayEquals(new int[]{WidgetPositionMode.ABSOLUTE_LEFT, WidgetPositionMode.ABSOLUTE_TOP, 478, 261},
			f.get(InterfaceID.Menu.ROOT_GRAPHIC3).position());
		Rectangle close = new Rectangle(100 + 478, 200 + 261, 26, 23);
		assertEquals(close, menu.closeRect());
		assertFalse(close.intersects(cell));
		assertOnlyResized(f, rows[3]);

		// another selection: Grand Exchange goes back to 16 px at its own place
		menu.apply(row(menu, "HOSIDIUS"));
		assertArrayEquals(new int[]{WidgetSizeMode.MINUS, WidgetSizeMode.ABSOLUTE, 0, 16}, rows[3].size());
		assertArrayEquals(new int[]{WidgetPositionMode.ABSOLUTE_CENTER, WidgetPositionMode.ABSOLUTE_TOP, 0, 48}, rows[3].position());
		assertArrayEquals(new int[]{WidgetSizeMode.MINUS, WidgetSizeMode.ABSOLUTE, 0, 32}, rows[9].size());
		assertEquals(cell, menu.rowCell(row(menu, "HOSIDIUS")));
		assertEquals(FakeMenu.TITLE, menu.title(TreeMenu.Style.CLASSIC));

		menu.restore();
		assertEquals(before, snapshot(f));
		assertKeyListenersUntouched(f, InterfaceID.Menu.KEYLISTENERS);
	}

	/** Hard rule 4: of every widget of the menu, only these (the Travel row's) were ever resized. */
	private static void assertOnlyResized(FakeMenu f, FakeMenu.W... travel)
	{
		List<FakeMenu.W> allowed = Arrays.asList(travel);
		for (FakeMenu.W w : f.all())
		{
			if (!allowed.contains(w))
			{
				assertEquals(w.id + "/" + w.index + " resized", 0, w.resizes);
			}
		}
	}

	/**
	 * The size is put back exactly as found, modes included, and only while the row still holds
	 * the size we wrote: a row the game or another plugin resized since keeps that size (and that
	 * size is what a later apply records as the one to go back to).
	 */
	@Test
	public void theTravelRowsSizeIsPutBackOnlyWhileItHoldsOurs()
	{
		for (TreeMenu.Style style : TreeMenu.Style.values())
		{
			boolean modern = style == TreeMenu.Style.MODERN;
			FakeMenu f = modern ? FakeMenu.modern(FakeMenu.OPTIONS) : FakeMenu.classic(FakeMenu.OPTIONS);
			TreeMenu menu = open(f, style);
			FakeMenu.W r = f.get(modern ? InterfaceID.MenuNew.TEXT : InterfaceID.Menu.LJ_LAYER1).children[3];
			int[] position = r.position();
			menu.apply(row(menu, "GRAND_EXCHANGE"));
			assertEquals(style.name(), 32, r.h);
			// someone else makes it 40 tall: our size is no longer there to put back (the whole size
			// is left as found, as a move the game redid is); the position still is ours, and goes back
			r.oh = 40;
			r.layOut();
			int[] theirs = r.size();
			int writes = r.resizes;
			menu.restore();
			assertEquals(style.name(), writes, r.resizes);
			assertArrayEquals(style.name(), theirs, r.size());
			assertArrayEquals(style.name(), position, r.position());
			// applied again, their size is what goes back; a second apply writes nothing
			menu.apply(row(menu, "GRAND_EXCHANGE"));
			assertEquals(style.name(), 32, r.h);
			writes = r.writes;
			menu.apply(row(menu, "GRAND_EXCHANGE"));
			assertEquals(style.name(), writes, r.writes);
			menu.restore();
			assertArrayEquals(style.name(), theirs, r.size());
			assertEquals(style.name(), 40, r.h);
		}
	}

	@Test
	public void classicNeverShowsWhatItDidNotHide()
	{
		FakeMenu f = FakeMenu.classic(FakeMenu.OPTIONS);
		// hidden by the game or another plugin before we came (Better Teleport Menu's "Expand scroll
		// menu" hides the parchment model and draws its own)
		f.get(InterfaceID.Menu.LJ_SCROLL_BAR).hidden = true;
		f.parchmentModel().hidden = true;
		TreeMenu menu = open(f, TreeMenu.Style.CLASSIC);
		menu.apply(null);
		assertEquals(0, f.parchmentModel().writes);
		menu.restore();
		assertTrue(f.get(InterfaceID.Menu.LJ_SCROLL_BAR).hidden);
		assertTrue(f.parchmentModel().hidden);
		assertEquals(0, f.parchmentModel().writes);
		assertFalse(f.get(InterfaceID.Menu.LJ_LAYER2).hidden);
	}

	@Test
	public void theParchmentIsFoundByTypeAndItsRecordDiesWithARebuild()
	{
		FakeMenu f = FakeMenu.classic(FakeMenu.OPTIONS);
		FakeMenu.W layer2 = f.get(InterfaceID.Menu.LJ_LAYER2);
		// the model at another index: found by its type, the title left alone
		FakeMenu.W title = layer2.children[1];
		layer2.children = new FakeMenu.W[]{title, layer2.children[0]};
		TreeMenu menu = open(f, TreeMenu.Style.CLASSIC);
		menu.apply(null);
		assertTrue(layer2.children[1].hidden);
		assertFalse(title.hidden);
		menu.restore();
		assertFalse(layer2.children[1].hidden);

		// proc 219 runs while the map shows: a new parchment; the old one's record is dropped
		f = FakeMenu.classic(FakeMenu.OPTIONS);
		menu = open(f, TreeMenu.Style.CLASSIC);
		menu.apply(null);
		FakeMenu.W old = f.parchmentModel();
		assertTrue(old.hidden);
		f.rebuild(FakeMenu.OPTIONS);
		int writes = old.writes;
		menu.rebuilt(TREES, GREY);
		menu.apply(null);
		assertTrue(f.parchmentModel().hidden);
		menu.restore();
		assertFalse(f.parchmentModel().hidden);
		assertEquals(writes, old.writes);
		assertEquals(FakeMenu.TITLE, menu.title(TreeMenu.Style.CLASSIC));
	}

	/**
	 * Hard rule 4: the client skips a hidden component's whole subtree, so in Map mode, with and
	 * without a selection, across a new selection and a rebuild, no layer holding either menu's
	 * key listeners is hidden and every key listener stays effectively visible.
	 */
	@Test
	public void hotkeysKeepWorkingInMapMode()
	{
		for (TreeMenu.Style style : TreeMenu.Style.values())
		{
			FakeMenu f = style == TreeMenu.Style.MODERN ? FakeMenu.modern(FakeMenu.OPTIONS) : FakeMenu.classic(FakeMenu.OPTIONS);
			int keys = style == TreeMenu.Style.MODERN ? InterfaceID.MenuNew.KEYLISTENERS : InterfaceID.Menu.KEYLISTENERS;
			TreeMenu menu = open(f, style);
			assertNull(style.name(), f.hotkeysBlocked());
			menu.apply(null);
			assertNull(style.name(), f.hotkeysBlocked());
			menu.apply(row(menu, "GRAND_EXCHANGE"));
			assertNull(style.name(), f.hotkeysBlocked());
			menu.apply(row(menu, "LAGUNA_AURORAE"));
			assertNull(style.name(), f.hotkeysBlocked());
			f.rebuild(FakeMenu.OPTIONS);
			menu.rebuilt(TREES, GREY);
			menu.apply(row(menu, "HOSIDIUS"));
			assertNull(style.name(), f.hotkeysBlocked());
			menu.restore();
			assertNull(style.name(), f.hotkeysBlocked());
			assertKeyListenersUntouched(f, keys);
		}
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
		assertEquals(296, list.y);
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
		assertFalse(f.parchmentModel().hidden);
		for (FakeMenu.W r : f.get(InterfaceID.Menu.LJ_LAYER1).children)
		{
			assertFalse(r.hidden);
			assertEquals(WidgetPositionMode.ABSOLUTE_TOP, r.yMode);
			assertEquals(16 * r.index, r.y);
			assertArrayEquals(new int[]{WidgetSizeMode.MINUS, WidgetSizeMode.ABSOLUTE, 0, 16}, r.size());
		}
		assertArrayEquals(new int[]{WidgetPositionMode.ABSOLUTE_LEFT, WidgetPositionMode.ABSOLUTE_TOP, 55, 70},
			f.get(InterfaceID.Menu.LJ_LAYER1).position());
		assertNull(menu.getStyle());
	}
}
