/*
 * Copyright (c) 2026, Dewdul
 * All rights reserved.
 * Licensed under the BSD 2-Clause License. See LICENSE.
 */
package com.spirittreeatlas;

import com.google.gson.Gson;
import java.awt.Color;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * Times an in-game open of the map offline (DESIGN 4.9): the real {@link TileStore} on the
 * plugin's own executor, rebuilt by {@link MapRenderer} the way {@link AtlasOverlay} does (at 50
 * frames a second, whenever the tile generation changes, and every 250 ms while incomplete), on
 * the default view (around you, at the Grand Exchange tree; {@code fit} for FIT_ALL). Run with
 * {@code ./gradlew bench} ({@code -PbenchArgs="rounds prewarmMs view"}, view {@code around},
 * {@code fit} or {@code run}). Each map size is opened cold (a fresh store) and then warm (the
 * same store, a fresh base-map cache: a second open within the trim window); the first round
 * also warms the JIT and is reported apart. {@code run} opens around one tree after another on
 * one store, as on a farming run, trimming between them as the plugin does 50 ticks after a
 * close, and reports the memory held.
 */
public class TileBench
{
	private static final String BASE = SpiritTreeAtlasPlugin.RESOURCES;
	private static final long FRAME_NS = TimeUnit.MILLISECONDS.toNanos(20);
	private static final long RETRY_NS = TimeUnit.MILLISECONDS.toNanos(250);
	private static final long TIMEOUT_NS = TimeUnit.SECONDS.toNanos(60);
	/** Free space on a 2560x1440 canvas, a mid-size resizable client, and fixed mode. */
	static final Rectangle[] SIZES = {
		new Rectangle(0, 0, 1738, 905),
		new Rectangle(0, 0, 1100, 600),
		new Rectangle(0, 0, 512, 334),
	};

	/** What one open measured; times in ms from the first frame, -1 when never reached. */
	static final class Result
	{
		double ppt;
		int z;
		int tiles;
		double firstImagery = -1;
		double covered = -1;
		double complete = -1;
		int decodes;
		int derived;
		int rebuilds;
		double renderMs;
		double renderMaxMs;
		long bytes;
	}

	/** A farming run, then every other surface tree and back: one tree after another (DESIGN 4.9). */
	static final String[] RUN = {"GRAND_EXCHANGE", "PORT_SARIM", "BRIMHAVEN", "ETCETERIA", "HOSIDIUS", "FARMING_GUILD",
		"TREE_GNOME_VILLAGE", "GNOME_STRONGHOLD", "BATTLEFIELD_OF_KHAZARD", "FELDIP_HILLS", "POISON_WASTE", "LAGUNA_AURORAE",
		"GRAND_EXCHANGE", "PORT_SARIM"};

	public static void main(String[] args)
	{
		int rounds = args.length > 0 ? Integer.parseInt(args[0]) : 3;
		long prewarmMs = args.length > 1 ? Long.parseLong(args[1]) : -1;
		String kind = args.length > 2 ? args[2] : "around";
		TreeRepository repo = TreeRepository.load(new Gson(), BASE);
		repo.placeHouse(1);
		System.out.printf(Locale.ROOT, "workers=%d java=%s prewarm=%s view=%s%n", TileStore.WORKERS,
			System.getProperty("java.version"), prewarmMs < 0 ? "off" : prewarmMs + " ms", kind);
		if ("run".equals(kind))
		{
			run(repo, SIZES[0], prewarmMs);
			return;
		}
		boolean fit = "fit".equals(kind);
		System.out.println("round kind size       ppt   z tiles | first  cover complete | decodes derived rebuilds render(ms tot/max) | MB");
		for (int round = 1; round <= rounds; round++)
		{
			for (Rectangle r : SIZES)
			{
				ExecutorService ex = TileStore.newExecutor();
				try
				{
					TileStore store = new TileStore(repo.getIndex(), repo.getLayers(), BASE, ex);
					MapView v = fit ? view(repo, r) : around(repo, repo.tree("GRAND_EXCHANGE"), r);
					if (prewarmMs >= 0)
					{
						// the player clicked Travel this long before the menu opened
						MapRenderer.prefetch(v, store);
						sleepMs(prewarmMs);
					}
					print(round, r, "cold", open(repo, store, v));
					print(round, r, "warm", open(repo, store, v));
				}
				finally
				{
					ex.shutdownNow();
				}
			}
		}
	}

	static MapView view(TreeRepository repo, Rectangle r)
	{
		return SpiritTreeAtlasPlugin.fitTrees(MapView.of(repo.surface(), r), repo.surfaceMarkers(), new Insets(ChromePainter.BAR_H, 0, 0, 0));
	}

	/** The plugin's default view standing at this tree, clear of the top bar and the quick-select panel (its tab when narrow). */
	static MapView around(TreeRepository repo, Tree at, Rectangle r)
	{
		repo.locate((int) at.getX(), (int) at.getY(), at.getPlane());
		int panel = r.width >= SpiritTreeAtlasPlugin.NARROW_MAP
			? new ChromePainter(new AtlasPainter().ink()).panelWidth(repo.menuOrder(), repo.getHere(), repo.getLast(), r.width) : ChromePainter.TAB_W;
		return SpiritTreeAtlasPlugin.initialView(SpiritTreeAtlasConfig.OpenAt.AROUND_YOU, repo, at, at.getX(), at.getY(), false, null, r,
			SpiritTreeAtlasPlugin.chromeInsets(panel));
	}

	/**
	 * One tree after another on one store: the Travel click (prewarmMs ahead, when given), the
	 * open, and the trim 50 ticks after the close, which keeps the open view's level and coarser.
	 */
	static void run(TreeRepository repo, Rectangle r, long prewarmMs)
	{
		System.out.println("tree                   ppt   z | first  cover complete | decodes derived | MB open / kept");
		ExecutorService ex = TileStore.newExecutor();
		try
		{
			TileStore store = new TileStore(repo.getIndex(), repo.getLayers(), BASE, ex);
			for (String id : RUN)
			{
				MapView v = around(repo, repo.tree(id), r);
				if (prewarmMs >= 0)
				{
					MapRenderer.prefetch(v, store);
					sleepMs(prewarmMs);
				}
				Result x = open(repo, store, v);
				store.trimFinerThan(Math.max(0, store.levelFor(v.getPpt())));
				System.out.printf(Locale.ROOT, "%-22s %5.3f %2d | %5s %6s %8s | %7d %7d | %5.1f / %5.1f%n", id, x.ppt, x.z,
					ms(x.firstImagery), ms(x.covered), ms(x.complete), x.decodes, x.derived, x.bytes / 1048576.0, store.cachedBytes() / 1048576.0);
			}
		}
		finally
		{
			ex.shutdownNow();
		}
	}

	/** One open: rebuilds as the overlay does until the frame is complete at the wanted level. */
	static Result open(TreeRepository repo, TileStore store, MapView v)
	{
		Result res = new Result();
		res.ppt = v.getPpt();
		res.z = store.levelFor(v.getPpt());
		int d0 = store.decodes.get();
		int v0 = store.derived.get();
		Layer layer = repo.layer(v.getLayer());
		Color bg = layer == null ? Color.BLACK : layer.getBackgroundColor();
		BufferedImage cache = new BufferedImage(v.getW(), v.getH(), BufferedImage.TYPE_INT_RGB);
		int[] px = ((DataBufferInt) cache.getRaster().getDataBuffer()).getData();
		int bgRgb = bg.getRGB() & 0xFFFFFF;
		res.tiles = tilesInView(v, store, null);
		long t0 = System.nanoTime();
		long next = t0;
		long builtAt = Long.MIN_VALUE / 2;
		int gen = Integer.MIN_VALUE;
		boolean complete = false;
		while (!complete && System.nanoTime() - t0 < TIMEOUT_NS)
		{
			long now = System.nanoTime();
			int g = store.getGeneration();
			if (g != gen || now - builtAt > RETRY_NS)
			{
				gen = g;
				builtAt = now;
				long s = System.nanoTime();
				complete = MapRenderer.render(cache, v, store, bg);
				long e = System.nanoTime();
				double ms = (e - s) / 1e6;
				res.renderMs += ms;
				res.renderMaxMs = Math.max(res.renderMaxMs, ms);
				res.rebuilds++;
				double at = (e - t0) / 1e6;
				if (res.firstImagery < 0 && anyImagery(px, bgRgb))
				{
					res.firstImagery = at;
				}
				if (res.covered < 0 && tilesInView(v, store, Boolean.TRUE) == res.tiles)
				{
					res.covered = at;
				}
				if (complete)
				{
					res.complete = at;
				}
			}
			next += FRAME_NS;
			long wait = next - System.nanoTime();
			if (wait > 0)
			{
				LockSupport.parkNanos(wait);
			}
		}
		res.decodes = store.decodes.get() - d0;
		res.derived = store.derived.get() - v0;
		res.bytes = store.cachedBytes();
		return res;
	}

	private static boolean anyImagery(int[] px, int bg)
	{
		for (int p : px)
		{
			if ((p & 0xFFFFFF) != bg)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * The tiles of the view at its level (the renderer's range); with {@code covered}, only those
	 * drawn from the level itself or a cached coarser one.
	 */
	static int tilesInView(MapView v, TileStore store, Boolean covered)
	{
		int z = store.levelFor(v.getPpt());
		int[] r = MapRenderer.range(v, TileStore.span(z));
		int n = 0;
		for (int ty = r[2]; ty <= r[3]; ty++)
		{
			for (int tx = r[0]; tx <= r[1]; tx++)
			{
				if (covered == null)
				{
					n++;
					continue;
				}
				for (int pz = z; pz >= TileStore.MIN_LEVEL; pz--)
				{
					int f = 1 << (z - pz);
					if (store.get(pz, Math.floorDiv(tx, f), Math.floorDiv(ty, f)) != null)
					{
						n++;
						break;
					}
				}
			}
		}
		return n;
	}

	private static void print(int round, Rectangle r, String kind, Result x)
	{
		System.out.printf(Locale.ROOT, "%d %-4s %4dx%-4d %5.3f %2d %4d | %5s %6s %8s | %7d %7d %8d %10.0f/%-6.1f | %5.1f%n",
			round, kind, r.width, r.height, x.ppt, x.z, x.tiles, ms(x.firstImagery), ms(x.covered), ms(x.complete),
			x.decodes, x.derived, x.rebuilds, x.renderMs, x.renderMaxMs, x.bytes / 1048576.0);
	}

	private static String ms(double v)
	{
		return v < 0 ? "never" : String.format(Locale.ROOT, "%.0f", v);
	}

	private static void sleepMs(long ms)
	{
		try
		{
			Thread.sleep(ms);
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
		}
	}

}
