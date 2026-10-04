/*
 * Copyright (c) 2026, Dewdul
 * All rights reserved.
 * Licensed under the BSD 2-Clause License. See LICENSE.
 */
package com.fairyringatlas;

import java.awt.Insets;
import java.awt.Rectangle;
import lombok.Value;

/**
 * An immutable view of one map layer: which world point sits at the centre of the screen
 * rectangle, and at how many pixels per game tile (ppt). World coordinates are continuous, so
 * tile (x, y) spans [x, x+1) and its centre is x + 0.5. Screen y grows downwards while world y
 * grows northwards.
 */
@Value
public class MapView
{
	public static final double MIN_PPT = 0.125;
	public static final double MAX_PPT = 16;

	String layer;
	/** Layer bounds in world tiles, used to clamp: [bx0, bx1) x [by0, by1). */
	int bx0;
	int by0;
	int bx1;
	int by1;
	double cx;
	double cy;
	double ppt;
	/** Screen rectangle. */
	int x;
	int y;
	int w;
	int h;

	public static MapView of(Layer layer, Rectangle r)
	{
		int[] b = layer.getBounds();
		return new MapView(layer.getId(), b[0], b[1], b[2], b[3], (b[0] + b[2]) / 2.0, (b[1] + b[3]) / 2.0,
			1, r.x, r.y, r.width, r.height).fitBounds(12);
	}

	public double screenX(double wx)
	{
		return x + w / 2.0 + (wx - cx) * ppt;
	}

	public double screenY(double wy)
	{
		return y + h / 2.0 - (wy - cy) * ppt;
	}

	public double worldX(double sx)
	{
		return cx + (sx - x - w / 2.0) / ppt;
	}

	public double worldY(double sy)
	{
		return cy - (sy - y - h / 2.0) / ppt;
	}

	/** World x at the left edge of the rectangle. */
	public double left()
	{
		return cx - w / 2.0 / ppt;
	}

	/** World y at the top (north) edge of the rectangle. */
	public double top()
	{
		return cy + h / 2.0 / ppt;
	}

	public boolean contains(int px, int py)
	{
		return px >= x && py >= y && px < x + w && py < y + h;
	}

	public Rectangle rect()
	{
		return new Rectangle(x, y, w, h);
	}

	public MapView withRect(Rectangle r)
	{
		if (r.x == x && r.y == y && r.width == w && r.height == h)
		{
			return this;
		}
		return new MapView(layer, bx0, by0, bx1, by1, cx, cy, ppt, r.x, r.y, r.width, r.height);
	}

	public MapView withLayer(Layer l)
	{
		int[] b = l.getBounds();
		return new MapView(l.getId(), b[0], b[1], b[2], b[3], cx, cy, ppt, x, y, w, h).clamp();
	}

	public MapView centerOn(double wx, double wy, double newPpt)
	{
		return new MapView(layer, bx0, by0, bx1, by1, wx, wy, newPpt, x, y, w, h).clamp();
	}

	/** Zoom by a factor while keeping the world point under (sx, sy) where it is. */
	public MapView zoomAbout(double sx, double sy, double factor)
	{
		double np = clampPpt(ppt * factor);
		double wx = worldX(sx);
		double wy = worldY(sy);
		double ncx = wx - (sx - x - w / 2.0) / np;
		double ncy = wy + (sy - y - h / 2.0) / np;
		return new MapView(layer, bx0, by0, bx1, by1, ncx, ncy, np, x, y, w, h).clamp();
	}

	/** Move the map with the mouse: a drag of (dx, dy) pixels. */
	public MapView panBy(double dx, double dy)
	{
		return new MapView(layer, bx0, by0, bx1, by1, cx - dx / ppt, cy + dy / ppt, ppt, x, y, w, h).clamp();
	}

	/** Fit a world rectangle with a margin of pad pixels. */
	public MapView fit(double x0, double y0, double x1, double y1, int pad)
	{
		double bw = Math.max(1, x1 - x0);
		double bh = Math.max(1, y1 - y0);
		double np = clampPpt(Math.min((w - 2.0 * pad) / bw, (h - 2.0 * pad) / bh));
		return new MapView(layer, bx0, by0, bx1, by1, (x0 + x1) / 2, (y0 + y1) / 2, np, x, y, w, h).clamp();
	}

	public MapView fitBounds(int pad)
	{
		return fit(bx0, by0, bx1, by1, pad);
	}

	/**
	 * Fit a world rectangle into the part of the screen left free by the chrome (insets in pixels
	 * from each edge), with a margin of pad pixels. Insets that would leave less than half the
	 * rectangle are ignored.
	 */
	public MapView fit(double x0, double y0, double x1, double y1, int pad, Insets in)
	{
		Insets use = usable(in);
		double bw = Math.max(1, x1 - x0);
		double bh = Math.max(1, y1 - y0);
		double fw = w - use.left - use.right - 2.0 * pad;
		double fh = h - use.top - use.bottom - 2.0 * pad;
		double np = clampPpt(Math.min(fw / bw, fh / bh));
		return focusOn((x0 + x1) / 2, (y0 + y1) / 2, np, use);
	}

	/** Put a world point at the centre of the area left free by the chrome. */
	public MapView focusOn(double wx, double wy, double newPpt, Insets in)
	{
		Insets use = usable(in);
		double np = clampPpt(newPpt);
		double ox = (use.left - use.right) / 2.0;
		double oy = (use.top - use.bottom) / 2.0;
		return new MapView(layer, bx0, by0, bx1, by1, wx - ox / np, wy + oy / np, np, x, y, w, h).clamp();
	}

	private Insets usable(Insets in)
	{
		if (in == null || in.left + in.right > w / 2 || in.top + in.bottom > h / 2)
		{
			return new Insets(0, 0, 0, 0);
		}
		return in;
	}

	/** Keep the centre inside the layer bounds, so the layer can never be dragged out of sight. */
	public MapView clamp()
	{
		double ncx = Math.max(bx0, Math.min(bx1, cx));
		double ncy = Math.max(by0, Math.min(by1, cy));
		double np = clampPpt(ppt);
		if (ncx == cx && ncy == cy && np == ppt)
		{
			return this;
		}
		return new MapView(layer, bx0, by0, bx1, by1, ncx, ncy, np, x, y, w, h);
	}

	public static double clampPpt(double p)
	{
		if (Double.isNaN(p))
		{
			return 1;
		}
		return Math.max(MIN_PPT, Math.min(MAX_PPT, p));
	}

	/**
	 * Blend two views of the same layer: zoom geometrically, centre linearly. t in [0, 1].
	 */
	public static MapView interpolate(MapView a, MapView b, double t)
	{
		if (t >= 1 || !a.layer.equals(b.layer))
		{
			return b;
		}
		double p = Math.exp(Math.log(a.ppt) + (Math.log(b.ppt) - Math.log(a.ppt)) * t);
		return new MapView(b.layer, b.bx0, b.by0, b.bx1, b.by1,
			a.cx + (b.cx - a.cx) * t, a.cy + (b.cy - a.cy) * t, p, b.x, b.y, b.w, b.h);
	}

	/** Cubic ease-out. */
	public static double easeOut(double t)
	{
		double u = 1 - Math.max(0, Math.min(1, t));
		return 1 - u * u * u;
	}
}
