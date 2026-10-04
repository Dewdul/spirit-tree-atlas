/*
 * Copyright (c) 2026, Dewdul
 * All rights reserved.
 * Licensed under the BSD 2-Clause License. See LICENSE.
 */
package com.spirittreeatlas;

import com.google.gson.Gson;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.geom.Area;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
		Scene s = new Scene();
		s.fromRepository(r);
		s.view = v;
		s.selected = selected;
		s.now = 1000;
		Rectangle slot = new Rectangle(MAP.x + MAP.width - 512, MAP.y + MAP.height - 334, 512, 334);
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
		AtlasPainter painter = new AtlasPainter();
		ChromePainter chrome = new ChromePainter(painter.ink());
		Graphics2D gr = img.createGraphics();
		chrome.layout(s);
		chrome.paintShadow(gr, s);
		Shape clip = gr.getClip();
		Area a = new Area(MAP);
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
		return s;
	}

	/** Every surface marker fitted; on the whole world's bounds, as the fixture's surface is a single tile. */
	private static MapView surface(TreeRepository r)
	{
		Layer world = new Layer(Layer.SURFACE, "Gielinor", 1016, 2104, 3976, 4168, "#4a5d89");
		return SpiritTreeAtlasPlugin.fitTrees(MapView.of(world, MAP), r.surfaceMarkers(), SpiritTreeAtlasPlugin.chromeInsets());
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
		assertEquals(Arrays.asList(Hit.LIST, Hit.FIT, Hit.ZOOM_IN, Hit.ZOOM_OUT, Hit.CLEAR), buttons(s));
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
		MapView v = SpiritTreeAtlasPlugin.fitLayer(r.layer(Layer.PRIFDDINAS), MAP, SpiritTreeAtlasPlugin.chromeInsets());
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
