/*
 * Copyright (c) 2026, Dewdul
 * All rights reserved.
 * Licensed under the BSD 2-Clause License. See LICENSE.
 */
package com.spirittreeatlas;

import com.google.gson.Gson;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.geom.Area;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * The painters against the fixtures, the way the overlay drives them: they draw every state
 * without a client, register the hits the plugin's menus and input rely on, and leave the holes
 * untouched so the real Travel row and close button show through.
 */
public class PainterTest
{
	private static final Rectangle MAP = new Rectangle(20, 20, 900, 600);

	private static TreeRepository repo()
	{
		TreeRepository r = TreeRepository.load(new Gson(), "/fixtures/");
		r.placeHouse(9);
		r.setLast(4);
		List<TreeMenu.Row> rows = new ArrayList<>();
		for (Tree t : r.getTrees())
		{
			int i = t.getPreviousValue() - 1;
			rows.add(new TreeMenu.Row(i, String.valueOf(FakeMenu.KEYS.charAt(i)), t.getMenuLabel(), "POISON_WASTE".equals(t.getId()), t.getId()));
		}
		r.applyRows(rows);
		return r;
	}

	/** Paints a scene as the overlay does and returns it with its hits. */
	private static Scene paint(TreeRepository r, MapView v, String selected, boolean shown, BufferedImage img)
	{
		return paint(r, v, selected, shown, img, s -> { });
	}

	/** Paints a scene as the overlay does, after {@code setup} changed its inputs. */
	private static Scene paint(TreeRepository r, MapView v, String selected, boolean shown, BufferedImage img, Consumer<Scene> setup)
	{
		Scene s = new Scene();
		s.fromRepository(r);
		s.view = v;
		s.selected = selected;
		s.now = 1000;
		Rectangle map = v.rect();
		Rectangle slot = new Rectangle(map.x + map.width - 512, map.y + map.height - 334, 512, 334);
		TreeMenu.Geometry g = TreeMenu.modern(512, 334, 8, 52, 322, 161, 20, 0, 0);
		s.rowCell = new Rectangle(slot.x + g.getCell().x, slot.y + g.getCell().y, g.getCell().width, g.getCell().height);
		s.closeRect = new Rectangle(slot.x + 468, slot.y + 273, 26, 23);
		s.holes.add(s.closeRect);
		s.rowShown = shown;
		if (shown)
		{
			s.holes.add(s.rowCell);
		}
		s.standIn = shown ? null : Scene.standInText(r.tree(selected), r.row(selected), r.getHere());
		setup.accept(s);
		AtlasPainter painter = new AtlasPainter();
		ChromePainter chrome = new ChromePainter(painter.ink());
		Graphics2D gr = img.createGraphics();
		chrome.layout(s);
		chrome.paintShadow(gr, s);
		Shape clip = gr.getClip();
		Area a = new Area(map);
		for (Rectangle h : s.holes)
		{
			a.subtract(new Area(h));
		}
		gr.clip(a);
		painter.paintMap(gr, s);
		chrome.paint(gr, s);
		gr.setClip(clip);
		chrome.paintHoles(gr, s);
		gr.dispose();
		lastPainter = painter;
		return s;
	}

	/** The map painter of the last {@link #paint}, for its placed labels. */
	private static AtlasPainter lastPainter;

	/** Every surface marker fitted; on the whole world's bounds, as the fixture's surface is a single tile. */
	private static MapView surface(TreeRepository r)
	{
		Layer world = new Layer(Layer.SURFACE, "Gielinor", 1016, 2104, 3976, 4168, "#4a5d89");
		return SpiritTreeAtlasPlugin.fitTrees(MapView.of(world, MAP), r.surfaceMarkers(), insets(r, MAP, true));
	}

	/** The plugin's chrome insets: the quick-select panel open (as on wide maps), else its tab (narrow maps). */
	private static Insets insets(TreeRepository r, Rectangle map, boolean open)
	{
		return SpiritTreeAtlasPlugin.chromeInsets(open ? new ChromePainter(Ink.create()).panelWidth(r.menuOrder(), r.getHere(), r.getLast(), map.width) : ChromePainter.TAB_W);
	}

	private static List<String> buttons(Scene s)
	{
		List<String> out = new ArrayList<>();
		for (Hit h : s.hits)
		{
			if (h.getKind() == Hit.Kind.BUTTON)
			{
				out.add(h.getId());
			}
		}
		return out;
	}

	@Test
	public void holesStayUntouched()
	{
		TreeRepository r = repo();
		BufferedImage img = new BufferedImage(960, 660, BufferedImage.TYPE_INT_ARGB);
		Scene s = paint(r, surface(r), "GRAND_EXCHANGE", true, img);
		assertTrue(s.rowShown);
		for (Rectangle h : s.holes)
		{
			for (int y = h.y; y < h.y + h.height; y++)
			{
				for (int x = h.x; x < h.x + h.width; x++)
				{
					assertEquals("drawn in a hole at " + x + "," + y, 0, img.getRGB(x, y) >>> 24);
				}
			}
		}
		// the Travel caption sits just above the cell's left end, clear of the close button
		assertNotNull(s.captionRect);
		assertTrue(s.captionRect.y + s.captionRect.height <= s.rowCell.y);
		assertFalse(s.captionRect.intersects(s.closeRect));
	}

	@Test
	public void markersStandInsAndButtons()
	{
		TreeRepository r = repo();
		Scene s = paint(r, surface(r), "GRAND_EXCHANGE", true, new BufferedImage(960, 660, BufferedImage.TYPE_INT_ARGB));
		int markers = 0;
		int standIns = 0;
		int portals = 0;
		for (Hit h : s.hits)
		{
			markers += h.getKind() == Hit.Kind.MARKER ? 1 : 0;
			standIns += h.isStandIn() ? 1 : 0;
			portals += h.getKind() == Hit.Kind.PORTAL ? 1 : 0;
			if (h.getKind() == Hit.Kind.MARKER)
			{
				assertEquals("Select", h.getOption());
				assertEquals(h.getTree().getLabel(), h.getTarget());
			}
		}
		// twelve surface trees, and Prifddinas and the house (in Prifddinas) at the portal
		assertEquals(14, markers);
		assertEquals(2, standIns);
		assertEquals(1, portals);
		assertEquals(Arrays.asList(Hit.LIST, Hit.FIT, Hit.ZOOM_IN, Hit.ZOOM_OUT, Hit.CLEAR, Hit.TOGGLE_PANEL), buttons(s));
		assertNull(s.backButton);
		assertNotNull(s.card);
		assertFalse(s.card.intersects(s.rowCell));
	}

	@Test
	public void theCoveredCellAbsorbsPresses()
	{
		TreeRepository r = repo();
		Scene s = paint(r, surface(r), "POISON_WASTE", false, new BufferedImage(960, 660, BufferedImage.TYPE_INT_ARGB));
		assertEquals("Locked: " + r.tree("POISON_WASTE").getLockedHint(), s.standIn);
		Hit h = Hit.at(s.hits, (int) s.rowCell.getCenterX(), (int) s.rowCell.getCenterY());
		assertNotNull(h);
		assertEquals(Hit.Kind.BLOCK, h.getKind());
		// nothing selected: no Clear button, and the hint card instead of a tree's
		Scene none = paint(r, surface(r), null, false, new BufferedImage(960, 660, BufferedImage.TYPE_INT_ARGB));
		assertEquals("Pick a tree on the map", none.standIn);
		assertFalse(buttons(none).contains(Hit.CLEAR));
		assertNotNull(none.card);
	}

	@Test
	public void otherLayersHaveTheBackButton()
	{
		TreeRepository r = repo();
		MapView v = SpiritTreeAtlasPlugin.fitLayer(r.layer(Layer.PRIFDDINAS), MAP, insets(r, MAP, true));
		Scene s = paint(r, v, "PRIFDDINAS", true, new BufferedImage(960, 660, BufferedImage.TYPE_INT_ARGB));
		assertNotNull(s.backButton);
		List<String> b = buttons(s);
		assertTrue(b.contains(Hit.BACK));
		int markers = 0;
		for (Hit h : s.hits)
		{
			markers += h.getKind() == Hit.Kind.MARKER ? 1 : 0;
			assertFalse(h.getKind() == Hit.Kind.PORTAL || h.isStandIn());
		}
		// the city's tree and the house
		assertEquals(2, markers);
	}

	/**
	 * DESIGN 4.4: the stand-in's line is never cut for the real data: every grey row's hint (with
	 * its padlock) and every fixed line fit the modern 161 px and classic 170 px cells, widened to
	 * the left by at most {@link ChromePainter#STAND_IN_GROW}.
	 */
	@Test
	public void standInLinesFitTheCell()
	{
		TreeRepository real = TreeRepository.load(new Gson(), SpiritTreeAtlasPlugin.RESOURCES);
		ChromePainter chrome = new ChromePainter(Ink.create());
		List<String> lines = new ArrayList<>(Arrays.asList("Pick a tree on the map", "You are here", "Not in this tree's list"));
		for (Tree t : real.getTrees())
		{
			assertTrue(t.getId(), chrome.standInWidth(t.getLockedHint(), true) + 6 <= 161 + ChromePainter.STAND_IN_GROW);
		}
		for (String l : lines)
		{
			assertTrue(l, chrome.standInWidth(l, false) + 6 <= 161);
		}
	}

	/** The stand-in owns its whole (possibly widened) box, and labels and the card keep clear of it. */
	@Test
	public void theStandInBoxIsAnObstacle()
	{
		TreeRepository r = repo();
		Scene s = paint(r, surface(r), "POISON_WASTE", false, new BufferedImage(960, 660, BufferedImage.TYPE_INT_ARGB));
		assertNotNull(s.standInRect);
		assertTrue(s.standInRect.contains(s.rowCell));
		assertFalse(s.card.intersects(s.standInRect));
		Hit h = Hit.at(s.hits, s.standInRect.x + 1, s.standInRect.y + 1);
		assertEquals(Hit.Kind.BLOCK, h.getKind());
		Scene shown = paint(r, surface(r), "GRAND_EXCHANGE", true, new BufferedImage(960, 660, BufferedImage.TYPE_INT_ARGB));
		assertNull(shown.standInRect);
	}

	/**
	 * DESIGN 4.6, deviation 19: in fixed mode (a 512x334 map) the fitted overview has a marker near
	 * every corner, so the card slides along an edge rather than cover one. For the real data, both
	 * menu styles, each house town and each tree selected in turn (and none), the card covers no
	 * marker centre, Laguna Aurorae's in the bottom-left corner included.
	 */
	@Test
	public void theFixedModeCardCoversNoMarker()
	{
		TreeRepository real = TreeRepository.load(new Gson(), SpiritTreeAtlasPlugin.RESOURCES);
		Rectangle fixed = new Rectangle(4, 4, 512, 334);
		for (int house : new int[]{1, 9})
		{
			real.placeHouse(house);
			real.setLast(4);
			MapView v = SpiritTreeAtlasPlugin.fitTrees(MapView.of(real.surface(), fixed), real.surfaceMarkers(), insets(real, fixed, false));
			List<String> selections = new ArrayList<>();
			selections.add(null);
			for (Tree t : real.getTrees())
			{
				selections.add(t.getId());
			}
			// the selected tree's card is taller while it is locked (its first requirement shows)
			for (boolean locked : new boolean[]{false, true})
			{
				for (boolean modern : new boolean[]{true, false})
				{
					for (String selected : selections)
					{
						List<TreeMenu.Row> rows = new ArrayList<>();
						for (Tree t : real.getTrees())
						{
							int i = t.getPreviousValue() - 1;
							rows.add(new TreeMenu.Row(i, String.valueOf(FakeMenu.KEYS.charAt(i)), t.getMenuLabel(), locked && t.getId().equals(selected), t.getId()));
						}
						real.applyRows(rows);
						Scene s = fixedScene(real, v, selected, modern);
						String at = "house " + house + (modern ? " modern " : " classic ") + (locked ? "locked " : "") + selected;
						assertNotNull(at, s.card);
						assertFalse(at, s.card.intersects(s.closeRect));
						assertFalse(at, s.card.intersects(s.rowCell));
						for (AtlasPainter.Mark m : AtlasPainter.marks(s))
						{
							assertFalse(at + " covers " + m.tree.getId() + " with " + s.card, s.card.contains(m.x, m.y));
						}
					}
				}
			}
		}
	}

	/** A fixed-mode scene laid out as the overlay does it: a first frame off screen, then the real one. */
	private static Scene fixedScene(TreeRepository r, MapView v, String selected, boolean modern)
	{
		// a narrow map: the quick-select panel starts as its tab
		return fixedScene(r, v, selected, modern, false);
	}

	private static Scene fixedScene(TreeRepository r, MapView v, String selected, boolean modern, boolean panelOpen)
	{
		Scene s = new Scene();
		s.fromRepository(r);
		s.view = v;
		s.selected = selected;
		s.now = 1000;
		s.panelOpen = panelOpen;
		Rectangle slot = v.rect();
		TreeMenu.Geometry g = modern ? TreeMenu.modern(512, 334, 8, 52, 322, 161, 20, 0, 0) : TreeMenu.classic(512, 334, 386, 16, 0);
		s.rowCell = new Rectangle(slot.x + g.getCell().x, slot.y + g.getCell().y, g.getCell().width, g.getCell().height);
		s.closeRect = modern ? new Rectangle(slot.x + 468, slot.y + 273, 26, 23)
			: new Rectangle(slot.x + g.getClose().x, slot.y + g.getClose().y, 26, 23);
		s.holes.add(s.closeRect);
		TreeMenu.Row row = r.row(selected);
		s.rowShown = Scene.rowShown(selected, row, r.getHere());
		if (s.rowShown)
		{
			s.holes.add(s.rowCell);
		}
		s.standIn = s.rowShown ? null : Scene.standInText(r.tree(selected), row, r.getHere());
		AtlasPainter painter = new AtlasPainter();
		ChromePainter chrome = new ChromePainter(painter.ink());
		for (int frame = 0; frame < 2; frame++)
		{
			s.hits.clear();
			Graphics2D gr = new BufferedImage(v.getX() + v.getW() + 8, v.getY() + v.getH() + 8, BufferedImage.TYPE_INT_ARGB).createGraphics();
			chrome.layout(s);
			painter.paintMap(gr, s);
			chrome.paint(gr, s);
			gr.dispose();
		}
		return s;
	}

	/**
	 * DESIGN 4.9: releasing the caches drops every label and marker sprite the painter holds, and
	 * the next frame draws exactly what it drew before.
	 */
	@Test
	public void releaseDropsThePaintersSprites() throws Exception
	{
		TreeRepository r = repo();
		AtlasPainter painter = new AtlasPainter();
		Scene s = new Scene();
		s.fromRepository(r);
		s.view = surface(r);
		s.selected = "GRAND_EXCHANGE";
		s.now = 1000;
		BufferedImage before = new BufferedImage(960, 660, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = before.createGraphics();
		painter.paintMap(g, s);
		g.dispose();
		assertFalse(retained(painter, "treeLabels").isEmpty());
		assertFalse(retained(painter, "markerSprites").isEmpty());

		painter.release();
		for (String field : new String[]{"treeLabels", "placeLabels", "markerSprites"})
		{
			assertTrue(field, retained(painter, field).isEmpty());
		}
		BufferedImage after = new BufferedImage(960, 660, BufferedImage.TYPE_INT_ARGB);
		g = after.createGraphics();
		painter.paintMap(g, s);
		g.dispose();
		assertTrue(Arrays.equals(before.getRGB(0, 0, 960, 660, null, 0, 960), after.getRGB(0, 0, 960, 660, null, 0, 960)));
	}

	/** A private list or map of the painter's, as a collection of what it holds. */
	private static java.util.Collection<?> retained(AtlasPainter painter, String field) throws Exception
	{
		java.lang.reflect.Field f = AtlasPainter.class.getDeclaredField(field);
		f.setAccessible(true);
		Object v = f.get(painter);
		return v instanceof java.util.Map ? ((java.util.Map<?, ?>) v).values() : (java.util.Collection<?>) v;
	}

	// ------------------------------------------------------------------ quick-select panel (DESIGN 4.6)

	private static List<Hit> hits(Scene s, Hit.Kind kind)
	{
		List<Hit> out = new ArrayList<>();
		for (Hit h : s.hits)
		{
			if (h.getKind() == kind)
			{
				out.add(h);
			}
		}
		return out;
	}

	private static Hit button(Scene s, String id)
	{
		for (Hit h : hits(s, Hit.Kind.BUTTON))
		{
			if (id.equals(h.getId()))
			{
				return h;
			}
		}
		return null;
	}

	/** One row per destination in the menu's order: by the live rows, the trees it does not list last. */
	@Test
	public void quickSelectRowsFollowTheMenuOrder()
	{
		TreeRepository r = repo();
		Scene s = paint(r, surface(r), "GRAND_EXCHANGE", true, new BufferedImage(960, 660, BufferedImage.TYPE_INT_ARGB));
		List<String> ids = new ArrayList<>();
		for (Hit h : hits(s, Hit.Kind.ROW))
		{
			ids.add(h.getTree().getId());
			assertEquals("Select", h.getOption());
			assertEquals(h.getTree().getLabel(), h.getTarget());
			assertTrue(s.panel.contains(h.getArea()));
		}
		List<String> data = new ArrayList<>();
		for (Tree t : r.getTrees())
		{
			data.add(t.getId());
		}
		assertEquals(data, ids);
		assertEquals(1, hits(s, Hit.Kind.PANEL).size());
		assertEquals("Hide", button(s, Hit.TOGGLE_PANEL).getOption());

		// another menu order (the live rows reversed), with the Grand Exchange not listed: it goes last
		List<TreeMenu.Row> rows = new ArrayList<>();
		List<String> expected = new ArrayList<>();
		for (int i = r.getTrees().size() - 1, row = 0; i >= 0; i--)
		{
			Tree t = r.getTrees().get(i);
			if (!"GRAND_EXCHANGE".equals(t.getId()))
			{
				rows.add(new TreeMenu.Row(row, String.valueOf(FakeMenu.KEYS.charAt(row)), t.getMenuLabel(), false, t.getId()));
				expected.add(t.getId());
				row++;
			}
		}
		expected.add("GRAND_EXCHANGE");
		r.applyRows(rows);
		ids.clear();
		for (Hit h : hits(paint(r, surface(r), null, false, new BufferedImage(960, 660, BufferedImage.TYPE_INT_ARGB)), Hit.Kind.ROW))
		{
			ids.add(h.getTree().getId());
		}
		assertEquals(expected, ids);
	}

	/** A row shows its tree's state: available, locked, not listed, selected, "You" and the last trip each draw differently. */
	@Test
	public void quickSelectRowsShowTheTreesState()
	{
		TreeRepository r = repo();
		MapView v = surface(r);
		int[] base = rowPixels(r, v, "PORT_SARIM", null, s -> { });
		List<int[]> looks = new ArrayList<>();
		looks.add(base);
		// locked
		looks.add(rowPixels(r, v, "PORT_SARIM", null, s -> s.states.put("PORT_SARIM", Tree.Status.LOCKED)));
		// not listed
		looks.add(rowPixels(r, v, "PORT_SARIM", null, s -> s.states.remove("PORT_SARIM")));
		// selected
		looks.add(rowPixels(r, v, "PORT_SARIM", "PORT_SARIM", s -> { }));
		// where the player stands
		looks.add(rowPixels(r, v, "PORT_SARIM", null, s -> s.here = "PORT_SARIM"));
		// the last trip
		looks.add(rowPixels(r, v, "PORT_SARIM", null, s -> s.last = "PORT_SARIM"));
		// no key
		looks.add(rowPixels(r, v, "PORT_SARIM", null, s -> s.keys.remove("PORT_SARIM")));
		for (int i = 0; i < looks.size(); i++)
		{
			for (int j = i + 1; j < looks.size(); j++)
			{
				assertFalse("looks " + i + " and " + j, Arrays.equals(looks.get(i), looks.get(j)));
			}
		}
	}

	/** The pixels of one tree's quick-select row, painted with the scene changed by {@code setup}. */
	private static int[] rowPixels(TreeRepository r, MapView v, String id, String selected, Consumer<Scene> setup)
	{
		BufferedImage img = new BufferedImage(960, 660, BufferedImage.TYPE_INT_ARGB);
		Scene s = paint(r, v, selected, false, img, setup);
		for (Hit h : hits(s, Hit.Kind.ROW))
		{
			if (h.getTree().getId().equals(id))
			{
				Rectangle a = h.getArea();
				return img.getRGB(a.x, a.y, a.width, a.height, null, 0, a.width);
			}
		}
		throw new AssertionError("no row for " + id);
	}

	/** Closed, the panel is a slim tab with only its Show toggle; turned off, there is neither. */
	@Test
	public void quickSelectCollapsesToATabAndTurnsOff()
	{
		TreeRepository r = repo();
		Scene closed = paint(r, surface(r), null, false, new BufferedImage(960, 660, BufferedImage.TYPE_INT_ARGB), s -> s.panelOpen = false);
		assertEquals(ChromePainter.TAB_W, closed.panel.width);
		assertTrue(hits(closed, Hit.Kind.ROW).isEmpty());
		assertTrue(hits(closed, Hit.Kind.PANEL).isEmpty());
		Hit show = button(closed, Hit.TOGGLE_PANEL);
		assertEquals("Show", show.getOption());
		assertEquals(closed.panel, show.getArea());

		Scene off = paint(r, surface(r), null, false, new BufferedImage(960, 660, BufferedImage.TYPE_INT_ARGB), s -> s.quickSelect = false);
		assertNull(off.panel);
		assertNull(button(off, Hit.TOGGLE_PANEL));
		assertTrue(hits(off, Hit.Kind.ROW).isEmpty());
	}

	/**
	 * The open panel never overlaps the close button, the Travel cell, its caption or the
	 * stand-in, on a 512x334 map (both menu styles, every selection) and on a large one; when they
	 * are in its column it stops above them.
	 */
	@Test
	public void quickSelectKeepsClearOfTheCorner()
	{
		TreeRepository real = TreeRepository.load(new Gson(), SpiritTreeAtlasPlugin.RESOURCES);
		real.placeHouse(1);
		List<String> selections = new ArrayList<>();
		selections.add(null);
		for (Tree t : real.getTrees())
		{
			selections.add(t.getId());
		}
		for (Rectangle map : new Rectangle[]{new Rectangle(4, 4, 512, 334), new Rectangle(30, 30, 1738, 905)})
		{
			MapView v = SpiritTreeAtlasPlugin.fitTrees(MapView.of(real.surface(), map), real.surfaceMarkers(), insets(real, map, true));
			for (boolean locked : new boolean[]{false, true})
			{
				for (boolean modern : new boolean[]{true, false})
				{
					for (String selected : selections)
					{
						List<TreeMenu.Row> rows = new ArrayList<>();
						for (Tree t : real.getTrees())
						{
							int i = t.getPreviousValue() - 1;
							rows.add(new TreeMenu.Row(i, String.valueOf(FakeMenu.KEYS.charAt(i)), t.getMenuLabel(), locked && t.getId().equals(selected), t.getId()));
						}
						real.applyRows(rows);
						Scene s = fixedScene(real, v, selected, modern, true);
						String at = map.width + (modern ? " modern " : " classic ") + (locked ? "locked " : "") + selected;
						assertNotNull(at, s.panel);
						assertTrue(at, v.rect().contains(s.panel));
						assertTrue(at, s.panel.width <= Math.max(ChromePainter.PANEL_W, map.width * 30 / 100));
						List<Rectangle> corner = new ArrayList<>(s.blockers());
						if (s.standInRect != null)
						{
							corner.add(s.standInRect);
						}
						for (Rectangle b : corner)
						{
							assertFalse(at + " over " + b, s.panel.intersects(b));
						}
						assertFalse(at, s.card != null && s.card.intersects(s.panel));
						// every row fits without scrolling here
						assertEquals(at, real.getTrees().size(), hits(s, Hit.Kind.ROW).size());
					}
				}
			}
		}
		// a corner in the panel's column: the panel stops above it, and the rows it cannot hold scroll
		TreeRepository r = repo();
		MapView v = surface(r);
		Scene s = paint(r, v, "POISON_WASTE", false, new BufferedImage(960, 660, BufferedImage.TYPE_INT_ARGB), sc ->
		{
			sc.rowCell = new Rectangle(MAP.x + 10, MAP.y + 200, 161, 20);
			sc.holes.clear();
		});
		assertTrue(s.panel.y + s.panel.height <= s.standInRect.y - 8);
		assertTrue(s.panel.y + s.panel.height <= s.captionRect.y - 8);
		assertTrue(hits(s, Hit.Kind.ROW).size() < r.getTrees().size());
	}

	/** The card sits beside the open panel, and no tree label is placed over it or beside a marker under it. */
	@Test
	public void theCardAndLabelsKeepClearOfThePanel() throws Exception
	{
		TreeRepository r = repo();
		Layer world = new Layer(Layer.SURFACE, "Gielinor", 1016, 2104, 3976, 4168, "#4a5d89");
		// fitted without the panel's inset, so the westernmost markers sit under the panel
		MapView v = SpiritTreeAtlasPlugin.fitTrees(MapView.of(world, MAP), r.surfaceMarkers(), SpiritTreeAtlasPlugin.chromeInsets(0));
		for (String selected : new String[]{null, "LAGUNA_AURORAE", "FARMING_GUILD", "POISON_WASTE"})
		{
			Scene s = paint(r, v, selected, false, new BufferedImage(960, 660, BufferedImage.TYPE_INT_ARGB));
			assertNotNull(s.card);
			assertFalse(selected, s.card.intersects(s.panel));
			int under = 0;
			for (AtlasPainter.Mark m : AtlasPainter.marks(s))
			{
				under += s.panel.contains(m.x, m.y) ? 1 : 0;
			}
			assertTrue("a marker under the panel", under > 0);
			for (Object p : retained(lastPainter, "treeLabels"))
			{
				Rectangle at = (Rectangle) field(p, "at");
				AtlasPainter.Mark m = (AtlasPainter.Mark) field(p, "mark");
				assertFalse(m.tree.getId() + "'s label over the panel", at.intersects(s.panel));
				assertFalse(m.tree.getId() + " is under the panel", s.panel.contains(m.x, m.y));
			}
			for (Object p : retained(lastPainter, "placeLabels"))
			{
				assertFalse("a place label over the panel", ((Rectangle) field(p, "at")).intersects(s.panel));
			}
		}
		// a marker just inside the panel's edge, with room for its label beside it on the map: still no label
		Rectangle panel = paint(r, v, null, false, new BufferedImage(960, 660, BufferedImage.TYPE_INT_ARGB)).panel;
		Tree laguna = r.tree("LAGUNA_AURORAE");
		double ppt = 2;
		double tx = panel.x + panel.width - 3;
		double ty = panel.y + panel.height / 2.0;
		MapView edge = MapView.of(world, MAP).centerOn(laguna.getX() + 0.5 - (tx - (MAP.x + MAP.width / 2.0)) / ppt,
			laguna.getY() + 0.5 + (ty - (MAP.y + MAP.height / 2.0)) / ppt, ppt);
		Scene s = paint(r, edge, null, false, new BufferedImage(960, 660, BufferedImage.TYPE_INT_ARGB));
		assertTrue(s.panel.contains(edge.screenX(laguna.getX() + 0.5), edge.screenY(laguna.getY() + 0.5)));
		for (Object p : retained(lastPainter, "treeLabels"))
		{
			assertFalse("Laguna Aurorae is under the panel", ((AtlasPainter.Mark) field(p, "mark")).tree == laguna);
		}
		s = paint(r, edge, null, false, new BufferedImage(960, 660, BufferedImage.TYPE_INT_ARGB), sc -> sc.quickSelect = false);
		boolean labelled = false;
		for (Object p : retained(lastPainter, "treeLabels"))
		{
			labelled |= ((AtlasPainter.Mark) field(p, "mark")).tree == laguna;
		}
		assertTrue("labelled without the panel", labelled);
	}

	private static Object field(Object o, String name) throws Exception
	{
		java.lang.reflect.Field f = o.getClass().getDeclaredField(name);
		f.setAccessible(true);
		return f.get(o);
	}

	/** Wheel scrolling: when the rows overflow, the painter reports how far they can scroll, and a scroll shows the later rows. */
	@Test
	public void quickSelectScrollsWhenItsRowsOverflow()
	{
		TreeRepository r = repo();
		AtlasPainter painter = new AtlasPainter();
		ChromePainter chrome = new ChromePainter(painter.ink());
		Rectangle small = new Rectangle(20, 20, 512, 250);
		Scene s = new Scene();
		s.fromRepository(r);
		s.view = SpiritTreeAtlasPlugin.fitTrees(MapView.of(r.surface(), small), r.surfaceMarkers(), insets(r, small, true));
		Graphics2D g = new BufferedImage(600, 300, BufferedImage.TYPE_INT_ARGB).createGraphics();
		chrome.layout(s);
		chrome.paint(g, s);
		assertTrue(chrome.panelScrollMax > 0);
		List<Hit> top = hits(s, Hit.Kind.ROW);
		assertEquals(r.getTrees().get(0), top.get(0).getTree());
		assertTrue(top.size() < r.getTrees().size());
		s.hits.clear();
		s.panelScroll = chrome.panelScrollMax;
		chrome.layout(s);
		chrome.paint(g, s);
		List<Hit> end = hits(s, Hit.Kind.ROW);
		assertEquals(r.getTrees().get(r.getTrees().size() - 1), end.get(end.size() - 1).getTree());
		for (Hit h : end)
		{
			assertTrue(s.panel.contains(h.getArea()));
		}
		g.dispose();
	}

	/** DESIGN 4.7: every marker state draws differently, at the base size and past 4 ppt. */
	@Test
	public void markerStatesAreDistinct()
	{
		Scene s = new Scene();
		int[] states = {AtlasPainter.AVAILABLE, AtlasPainter.LOCKED, 0, AtlasPainter.AVAILABLE | AtlasPainter.HOVER,
			AtlasPainter.AVAILABLE | AtlasPainter.SELECTED, AtlasPainter.AVAILABLE | AtlasPainter.LAST,
			AtlasPainter.LOCKED | AtlasPainter.SELECTED, AtlasPainter.LOCKED | AtlasPainter.HOVER};
		for (double ppt : new double[]{1, 16})
		{
			List<int[]> drawn = new ArrayList<>();
			for (int f : states)
			{
				BufferedImage img = new BufferedImage(48, 48, BufferedImage.TYPE_INT_ARGB);
				Graphics2D g = img.createGraphics();
				AtlasPainter.drawMarker(g, 24, 24, AtlasPainter.radius(ppt, (f & AtlasPainter.SELECTED) != 0), f, s);
				g.dispose();
				int[] px = img.getRGB(0, 0, 48, 48, null, 0, 48);
				for (int[] other : drawn)
				{
					assertFalse("state " + f + " at " + ppt + " ppt", Arrays.equals(px, other));
				}
				drawn.add(px);
			}
		}
		assertEquals(16, (int) Math.round(AtlasPainter.radius(1, false) * 2));
	}
}
