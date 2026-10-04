/*
 * Copyright (c) 2026, Dewdul
 * All rights reserved.
 * Licensed under the BSD 2-Clause License. See LICENSE.
 */
package com.spirittreeatlas;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

/**
 * Greedy collision-free label placement. Callers add obstacles (markers, chrome), then place labels
 * in priority order; each label tries eight positions around its anchor and takes the first that
 * is inside the bounds and clear of everything placed so far. A label with no free position is
 * not placed.
 */
public final class LabelPlacer
{
	/** Candidate offsets as (dx, dy) multipliers: right, left, above, below, then the diagonals. */
	private static final int[][] CANDIDATES = {
		{1, 0}, {-1, 0}, {0, -1}, {0, 1}, {1, -1}, {1, 1}, {-1, -1}, {-1, 1},
	};

	private final Rectangle bounds;
	private final List<Rectangle> taken = new ArrayList<>();

	public LabelPlacer(Rectangle bounds)
	{
		this.bounds = bounds;
	}

	public void addObstacle(Rectangle r)
	{
		if (r != null)
		{
			taken.add(r);
		}
	}

	public boolean isFree(Rectangle r)
	{
		if (!bounds.contains(r))
		{
			return false;
		}
		for (Rectangle t : taken)
		{
			if (t.intersects(r))
			{
				return false;
			}
		}
		return true;
	}

	/** Place a rectangle exactly where given if it is free. */
	public boolean placeExact(Rectangle r)
	{
		if (isFree(r))
		{
			taken.add(r);
			return true;
		}
		return false;
	}

	/**
	 * Place a w x h label beside a marker of the given radius centred on (ax, ay).
	 *
	 * @return the placed rectangle, or null when every candidate collides
	 */
	public Rectangle place(double ax, double ay, int w, int h, int radius, int gap)
	{
		int off = radius + gap;
		for (int[] c : CANDIDATES)
		{
			int lx;
			int ly;
			if (c[0] > 0)
			{
				lx = (int) Math.round(ax + (c[1] == 0 ? off : off * 0.7));
			}
			else if (c[0] < 0)
			{
				lx = (int) Math.round(ax - (c[1] == 0 ? off : off * 0.7)) - w;
			}
			else
			{
				lx = (int) Math.round(ax - w / 2.0);
			}
			if (c[1] > 0)
			{
				ly = (int) Math.round(ay + (c[0] == 0 ? off : off * 0.7));
			}
			else if (c[1] < 0)
			{
				ly = (int) Math.round(ay - (c[0] == 0 ? off : off * 0.7)) - h;
			}
			else
			{
				ly = (int) Math.round(ay - h / 2.0);
			}
			Rectangle r = new Rectangle(lx, ly, w, h);
			if (placeExact(r))
			{
				return r;
			}
		}
		return null;
	}

	int size()
	{
		return taken.size();
	}
}
