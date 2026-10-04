/*
 * Copyright (c) 2026, Dewdul
 * All rights reserved.
 * Licensed under the BSD 2-Clause License. See LICENSE.
 */
package com.spirittreeatlas;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Draws the tiles of a view into an opaque image the size of the view's rectangle. Picks the level
 * whose resolution is at or just above the view's, scales down with bilinear filtering and up
 * (past the finest level) with nearest neighbour so pixel art stays crisp. Tiles still decoding are
 * covered by the best coarser tile already cached, so the map fills in progressively.
 */
public final class MapRenderer
{
	private MapRenderer()
	{
	}

	/**
	 * @return true when every visible tile was drawn at the wanted level
	 */
	public static boolean render(BufferedImage target, MapView v, TileStore store, Color background)
	{
		Graphics2D g = target.createGraphics();
		try
		{
			g.setColor(background);
			g.fillRect(0, 0, target.getWidth(), target.getHeight());
			g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			store.beginFrame();

			double ppt = v.getPpt();
			int z = store.levelFor(ppt);
			double span = TileStore.span(z);
			double left = v.left();
			double top = v.top();
			// draw only inside the layer's bounds: other layers share the world coordinates (and the
			// tiles) beyond them, and outside is the layer's background
			int[] r = range(v, span);
			int tx0 = r[0];
			int tx1 = r[1];
			int ty0 = r[2];
			int ty1 = r[3];
			int cx0 = (int) Math.round((v.getBx0() - left) * ppt);
			int cx1 = (int) Math.round((v.getBx1() - left) * ppt);
			int cy0 = (int) Math.round((top - v.getBy1()) * ppt);
			int cy1 = (int) Math.round((top - v.getBy0()) * ppt);
			g.clipRect(cx0, cy0, cx1 - cx0, cy1 - cy0);
			boolean up = ppt / Math.pow(2, z) > 1.0001;
			Object fine = up ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR : RenderingHints.VALUE_INTERPOLATION_BILINEAR;
			boolean complete = true;
			List<int[]> missing = new ArrayList<>();

			for (int ty = ty1; ty >= ty0; ty--)
			{
				for (int tx = tx0; tx <= tx1; tx++)
				{
					int x0 = (int) Math.round((tx * span - left) * ppt);
					int x1 = (int) Math.round(((tx + 1) * span - left) * ppt);
					int y0 = (int) Math.round((top - (ty + 1) * span) * ppt);
					int y1 = (int) Math.round((top - ty * span) * ppt);
					BufferedImage img = store.request(z, tx, ty);
					if (img != null)
					{
						g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, fine);
						draw(g, img, x0, y0, x1, y1, 0, 0, TileStore.TILE);
						continue;
					}
					complete = false;
					missing.add(new int[]{tx, ty});
					// cover with the best cached ancestor (a quick coarse one is queued below)
					g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
					for (int pz = z - 1; pz >= TileStore.MIN_LEVEL; pz--)
					{
						int f = 1 << (z - pz);
						int ptx = Math.floorDiv(tx, f);
						int pty = Math.floorDiv(ty, f);
						BufferedImage parent = store.get(pz, ptx, pty);
						if (parent != null)
						{
							int size = TileStore.TILE / f;
							int sx = (tx - ptx * f) * size;
							int sy = ((pty + 1) * f - 1 - ty) * size;
							draw(g, parent, x0, y0, x1, y1, sx, sy, size);
							break;
						}
					}
					// after a zoom out the finer tiles are often still cached: draw them over the
					// coarse fallback, so the map stays sharp instead of flashing blurry
					for (int fz = Math.min(z + 2, store.getMaxLevel()); fz > z; fz--)
					{
						int n = 1 << (fz - z);
						double cs = span / n;
						for (int dy = 0; dy < n; dy++)
						{
							for (int dx = 0; dx < n; dx++)
							{
								BufferedImage child = store.get(fz, tx * n + dx, ty * n + dy);
								if (child != null)
								{
									int ax0 = (int) Math.round((tx * span + dx * cs - left) * ppt);
									int ax1 = (int) Math.round((tx * span + (dx + 1) * cs - left) * ppt);
									int ay0 = (int) Math.round((top - (ty * span + (dy + 1) * cs)) * ppt);
									int ay1 = (int) Math.round((top - (ty * span + dy * cs)) * ppt);
									draw(g, child, ax0, ay0, ax1, ay1, 0, 0, TileStore.TILE);
								}
							}
						}
					}
				}
			}
			requestCoarse(store, z, missing);
			return complete;
		}
		finally
		{
			g.dispose();
		}
	}

	/**
	 * Queues the tiles of a view that are not cached yet, without drawing: the view a menu about to
	 * open will show, so its tiles load while the player walks to the tree.
	 */
	public static void prefetch(MapView v, TileStore store)
	{
		store.beginFrame();
		int z = store.levelFor(v.getPpt());
		int[] r = range(v, TileStore.span(z));
		List<int[]> missing = new ArrayList<>();
		for (int ty = r[3]; ty >= r[2]; ty--)
		{
			for (int tx = r[0]; tx <= r[1]; tx++)
			{
				if (store.request(z, tx, ty) == null)
				{
					missing.add(new int[]{tx, ty});
				}
			}
		}
		requestCoarse(store, z, missing);
	}

	/**
	 * Queues a quick coarse tile (the coarsest bundled level: one decode) over each tile of level
	 * z still missing. Queued after the view's own tiles, they are the newest and so come first:
	 * the whole view is covered before the slow tiles come in.
	 */
	private static void requestCoarse(TileStore store, int z, List<int[]> missing)
	{
		int cz = store.coarsestBundled();
		if (cz >= z)
		{
			return;
		}
		int f = 1 << (z - cz);
		for (int[] t : missing)
		{
			store.request(cz, Math.floorDiv(t[0], f), Math.floorDiv(t[1], f));
		}
	}

	/** The view's tiles at a level of this span, clipped to the layer: {tx0, tx1, ty0, ty1}. */
	static int[] range(MapView v, double span)
	{
		double left = v.left();
		double top = v.top();
		double right = left + v.getW() / v.getPpt();
		double bottom = top - v.getH() / v.getPpt();
		return new int[]{
			(int) Math.floor(Math.max(left, v.getBx0()) / span),
			(int) Math.floor((Math.min(right, v.getBx1()) - 1e-9) / span),
			(int) Math.floor(Math.max(bottom, v.getBy0()) / span),
			(int) Math.floor((Math.min(top, v.getBy1()) - 1e-9) / span),
		};
	}

	private static void draw(Graphics2D g, BufferedImage img, int x0, int y0, int x1, int y1, int sx, int sy, int size)
	{
		if (img == TileStore.NONE)
		{
			return;
		}
		if (img.getWidth() == 1)
		{
			g.setColor(new Color(img.getRGB(0, 0)));
			g.fillRect(x0, y0, x1 - x0, y1 - y0);
		}
		else
		{
			g.drawImage(img, x0, y0, x1, y1, sx, sy, sx + size, sy + size, null);
		}
	}
}
