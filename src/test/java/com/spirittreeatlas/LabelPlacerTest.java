/*
 * Copyright (c) 2026, Dewdul
 * All rights reserved.
 * Licensed under the BSD 2-Clause License. See LICENSE.
 */
package com.spirittreeatlas;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class LabelPlacerTest
{
	private final Rectangle bounds = new Rectangle(0, 0, 400, 300);

	@Test
	public void prefersTheRightSide()
	{
		LabelPlacer p = new LabelPlacer(bounds);
		Rectangle r = p.place(100, 100, 30, 12, 6, 3);
		assertNotNull(r);
		assertEquals(109, r.x);
		assertEquals(94, r.y);
	}

	@Test
	public void movesAroundObstacles()
	{
		LabelPlacer p = new LabelPlacer(bounds);
		p.addObstacle(new Rectangle(105, 80, 60, 40));
		Rectangle r = p.place(100, 100, 30, 12, 6, 3);
		assertNotNull(r);
		assertTrue(r.x + r.width <= 100);
	}

	@Test
	public void staysInsideTheBounds()
	{
		LabelPlacer p = new LabelPlacer(bounds);
		Rectangle r = p.place(395, 100, 30, 12, 6, 3);
		assertNotNull(r);
		assertTrue(bounds.contains(r));
		assertTrue(r.x < 395);
	}

	@Test
	public void givesUpWhenEverythingCollides()
	{
		LabelPlacer p = new LabelPlacer(bounds);
		p.addObstacle(new Rectangle(50, 50, 100, 100));
		assertNull(p.place(100, 100, 30, 12, 6, 3));
	}

	@Test
	public void placedLabelsNeverOverlap()
	{
		LabelPlacer p = new LabelPlacer(bounds);
		List<Rectangle> placed = new ArrayList<>();
		for (int i = 0; i < 60; i++)
		{
			Rectangle r = p.place(50 + (i * 37) % 300, 40 + (i * 53) % 220, 26, 12, 6, 2);
			if (r != null)
			{
				for (Rectangle o : placed)
				{
					assertFalse(o.intersects(r));
				}
				placed.add(r);
			}
		}
		assertTrue(placed.size() > 20);
	}

	@Test
	public void exactPlacement()
	{
		LabelPlacer p = new LabelPlacer(bounds);
		assertTrue(p.placeExact(new Rectangle(10, 10, 50, 20)));
		assertFalse(p.placeExact(new Rectangle(40, 20, 50, 20)));
		assertFalse(p.placeExact(new Rectangle(380, 10, 50, 20)));
		assertEquals(1, p.size());
	}
}
