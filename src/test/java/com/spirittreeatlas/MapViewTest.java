/*
 * Copyright (c) 2026, Dewdul
 * All rights reserved.
 * Licensed under the BSD 2-Clause License. See LICENSE.
 */
package com.spirittreeatlas;

import java.awt.Rectangle;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class MapViewTest
{
	private static final double EPS = 1e-6;
	private final Layer layer = new Layer("surface", "Test", 1000, 2000, 3000, 4000, "#000000");

	private MapView view()
	{
		return MapView.of(layer, new Rectangle(100, 50, 800, 600)).centerOn(2000, 3000, 2);
	}

	@Test
	public void screenAndWorldRoundTrip()
	{
		MapView v = view();
		assertEquals(500, v.screenX(2000), EPS);
		assertEquals(350, v.screenY(3000), EPS);
		// north is up: a larger world y is higher on screen
		assertTrue(v.screenY(3010) < v.screenY(3000));
		assertEquals(2000, v.worldX(500), EPS);
		assertEquals(3000, v.worldY(350), EPS);
		assertEquals(2123.25, v.worldX(v.screenX(2123.25)), EPS);
		assertEquals(2876.5, v.worldY(v.screenY(2876.5)), EPS);
		assertEquals(2000 - 200, v.left(), EPS);
		assertEquals(3000 + 150, v.top(), EPS);
	}

	@Test
	public void zoomKeepsThePointUnderTheCursor()
	{
		MapView v = view();
		double wx = v.worldX(230);
		double wy = v.worldY(120);
		MapView z = v.zoomAbout(230, 120, 1.25);
		assertEquals(2.5, z.getPpt(), EPS);
		assertEquals(wx, z.worldX(230), EPS);
		assertEquals(wy, z.worldY(120), EPS);
	}

	@Test
	public void fitAndFocusKeepClearOfTheChrome()
	{
		MapView v = view();
		java.awt.Insets in = new java.awt.Insets(22, 156, 0, 0);
		// the free area is x 256..900, y 72..650; its centre is (578, 361)
		MapView f = v.focusOn(2500, 3500, 4, in);
		assertEquals(578, f.screenX(2500), EPS);
		assertEquals(361, f.screenY(3500), EPS);
		MapView fit = v.fit(2000, 3000, 2100, 3100, 10, in);
		assertEquals(Math.min((644 - 20) / 100.0, (578 - 20) / 100.0), fit.getPpt(), EPS);
		assertTrue(fit.screenX(2000) >= 256 + 10 - EPS && fit.screenX(2100) <= 900 + EPS);
		assertTrue(fit.screenY(3100) >= 72 + 10 - EPS && fit.screenY(3000) <= 650 + EPS);
		// insets taking more than half the map are ignored
		MapView wide = v.focusOn(2500, 3500, 4, new java.awt.Insets(0, 500, 0, 0));
		assertEquals(500, wide.screenX(2500), EPS);
	}

	@Test
	public void zoomIsClamped()
	{
		MapView v = view();
		assertEquals(MapView.MAX_PPT, v.zoomAbout(500, 350, 1000).getPpt(), EPS);
		assertEquals(MapView.MIN_PPT, v.zoomAbout(500, 350, 0.0001).getPpt(), EPS);
	}

	@Test
	public void panMovesWithTheMouse()
	{
		MapView v = view();
		double wx = v.worldX(300);
		double wy = v.worldY(200);
		MapView p = v.panBy(40, -25);
		assertEquals(wx, p.worldX(340), EPS);
		assertEquals(wy, p.worldY(175), EPS);
	}

	@Test
	public void clampKeepsTheLayerInSight()
	{
		MapView v = view().panBy(100000, -100000);
		assertEquals(1000, v.getCx(), EPS);
		assertEquals(2000, v.getCy(), EPS);
		Rectangle r = v.rect();
		// the layer corner is at the centre of the screen, so it is visible
		assertTrue(r.contains((int) v.screenX(1000), (int) v.screenY(2000)));
	}

	@Test
	public void fitContainsTheBounds()
	{
		MapView v = view().fit(1500, 2500, 2100, 2900, 20);
		assertTrue(v.screenX(1500) >= 100 + 20 - EPS);
		assertTrue(v.screenX(2100) <= 900 - 20 + EPS);
		assertTrue(v.screenY(2900) >= 50 + 20 - EPS);
		assertTrue(v.screenY(2500) <= 650 - 20 + EPS);
		assertEquals(1800, v.getCx(), EPS);
		assertEquals(2700, v.getCy(), EPS);
	}

	@Test
	public void interpolation()
	{
		MapView a = view();
		MapView b = a.centerOn(2400, 3200, 8);
		assertSame(b, MapView.interpolate(a, b, 1));
		MapView mid = MapView.interpolate(a, b, 0.5);
		assertEquals(4, mid.getPpt(), EPS);
		assertEquals(2200, mid.getCx(), EPS);
		assertEquals(0, MapView.easeOut(0), EPS);
		assertEquals(1, MapView.easeOut(1), EPS);
		assertTrue(MapView.easeOut(0.5) > 0.5);
	}

	@Test
	public void withRectKeepsTheCentre()
	{
		MapView v = view();
		assertSame(v, v.withRect(new Rectangle(100, 50, 800, 600)));
		MapView w = v.withRect(new Rectangle(0, 0, 400, 300));
		assertEquals(v.getCx(), w.getCx(), EPS);
		assertEquals(200, w.screenX(2000), EPS);
	}

	@Test
	public void fixedLayoutIsTheDialRect()
	{
		Rectangle canvas = new Rectangle(0, 0, 765, 503);
		Rectangle dial = new Rectangle(4, 4, 512, 334);
		Rectangle r = MapLayout.compute(canvas, dial, Collections.emptyList(), 1100, 720);
		assertTrue(r.contains(dial));
		assertTrue(r.width >= 512 && r.height >= 334);
	}

	@Test
	public void resizableLayoutAvoidsChatAndSidePanel()
	{
		Rectangle canvas = new Rectangle(0, 0, 1600, 900);
		Rectangle chat = new Rectangle(0, 735, 519, 165);
		Rectangle side = new Rectangle(1359, 565, 241, 335);
		Rectangle log = new Rectangle(1384, 602, 190, 261);
		// dial centred in the area left of the side panel and above the chatbox
		Rectangle dial = new Rectangle((1600 - 250) / 2 - 256, (900 - 165) / 2 - 167, 512, 334);
		Rectangle r = MapLayout.compute(canvas, dial, Arrays.asList(chat, side, log), 1100, 720);
		assertTrue(r.contains(dial));
		assertTrue(!r.intersects(chat));
		assertTrue(!r.intersects(side));
		assertTrue(!r.intersects(log));
		assertTrue(r.width <= 1100 && r.height <= 720);
		assertTrue(r.x >= 6 && r.y >= 6);
		assertTrue(r.width >= 900);
		// grown from the dials' bottom-right corner: the moved Teleport and close sit in that corner
		assertEquals(dial.y + dial.height, r.y + r.height);
		assertEquals(dial.x + dial.width, r.x + r.width);
		Rectangle corner = new Rectangle(r.x + r.width - 190, r.y + r.height - 75, 190, 75);
		for (int[] m : SpiritTreeAtlasPlugin.MOVED_IN_MAP)
		{
			Rectangle moved = new Rectangle(dial.x + m[1], dial.y + m[2], m[3], m[4]);
			assertTrue(dial.contains(moved));
			if (m[0] == net.runelite.api.gameval.InterfaceID.Fairyrings.CONFIRM)
			{
				// tall enough that the game still wraps long destination names onto two lines
				assertTrue(m[4] >= SpiritTreeAtlasPlugin.CONFIRM_MIN_H);
			}
			if (m[0] != net.runelite.api.gameval.InterfaceID.Fairyrings.ROOT_MODEL25)
			{
				assertTrue(corner.contains(moved));
			}
		}
	}

	@Test
	public void tinyCanvasStillContainsTheDials()
	{
		Rectangle canvas = new Rectangle(0, 0, 600, 380);
		Rectangle dial = new Rectangle(20, 20, 512, 334);
		Rectangle r = MapLayout.compute(canvas, dial, Collections.singletonList(new Rectangle(0, 360, 600, 20)), 1100, 720);
		assertTrue(r.contains(dial));
	}

	/**
	 * Small resizable canvases (and stretched mode, which clamps one side to 765 or 503), where the
	 * dials sit within the 6 px inset of the edge. Geometry of toplevel 161: the dial is centred
	 * above the 165 px chatbox and left of the 250 px side area.
	 */
	@Test
	public void smallResizableCanvasKeepsClearOfChatSideAndLog()
	{
		int[][] sizes = {{765, 503}, {800, 503}, {862, 503}, {773, 600}, {765, 600}, {900, 510}, {1600, 900}};
		for (int[] s : sizes)
		{
			int w = s[0];
			int h = s[1];
			Rectangle canvas = new Rectangle(0, 0, w, h);
			Rectangle dial = new Rectangle((w - 250 - 512) / 2, (h - 165 - 334) / 2, 512, 334);
			Rectangle chat = new Rectangle(0, h - 165, 519, 165);
			Rectangle side = new Rectangle(w - 241, h - 335, 241, 335);
			Rectangle log = new Rectangle(w - 216, h - 298, 190, 261);
			Rectangle r = MapLayout.compute(canvas, dial, Arrays.asList(chat, side, log), 1100, 720);
			String at = w + "x" + h + " gave " + r;
			assertTrue(at, r.contains(dial));
			assertTrue(at, !r.intersects(chat));
			assertTrue(at, !r.intersects(side));
			assertTrue(at, !r.intersects(log));
			assertTrue(at, canvas.contains(r));
		}
	}

	/**
	 * Fixed mode, card at the map's bottom-left and a mid-list log row: the leader's arc crosses the
	 * close button in the map's bottom-right corner, and must pass under it rather than over it.
	 */
	@Test
	public void travelLogLeaderPassesUnderTheCornerButtons()
	{
		Rectangle card = new Rectangle(36, 172, 230, 160);
		Rectangle row = new Rectangle(550, 295, 180, 18);
		int[] c = SpiritTreeAtlasPlugin.MOVED_IN_MAP[2];
		Rectangle close = new Rectangle(4 + c[1], 4 + c[2], c[3], c[4]);
		java.awt.image.BufferedImage plain = leader(row, card, Collections.emptyList());
		java.awt.image.BufferedImage avoiding = leader(row, card, Collections.singletonList(close));
		assertTrue(inked(plain, close) > 0);
		assertEquals(0, inked(avoiding, AtlasPainter.grow(close, 3)));
		// the rest of the leader is still drawn
		assertTrue(inked(avoiding, new Rectangle(300, 200, 150, 120)) > 0);
	}

	private static java.awt.image.BufferedImage leader(Rectangle row, Rectangle card, java.util.List<Rectangle> avoid)
	{
		java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(800, 500, java.awt.image.BufferedImage.TYPE_INT_ARGB);
		java.awt.Graphics2D g = img.createGraphics();
		AtlasOverlay.paintRowHighlight(g, row, card, avoid, java.awt.Color.ORANGE, 0);
		g.dispose();
		return img;
	}

	private static int inked(java.awt.image.BufferedImage img, Rectangle r)
	{
		int n = 0;
		for (int y = r.y; y < r.y + r.height; y++)
		{
			for (int x = r.x; x < r.x + r.width; x++)
			{
				n += (img.getRGB(x, y) >>> 24) != 0 ? 1 : 0;
			}
		}
		return n;
	}
}
