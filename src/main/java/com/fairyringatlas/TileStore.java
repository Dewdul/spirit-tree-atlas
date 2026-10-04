/*
 * Copyright (c) 2026, Dewdul
 * All rights reserved.
 * Licensed under the BSD 2-Clause License. See LICENSE.
 */
package com.fairyringatlas;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import lombok.extern.slf4j.Slf4j;

/**
 * Map tiles: bundled PNGs decoded off the client thread into an LRU of opaque INT_RGB images, and
 * the levels between bundled ones derived by averaging 2x2 blocks. A tile that is not shipped is a
 * 1x1 image of its solid colour (or the layer background); renderers fill it instead of scaling.
 * Every finished decode bumps {@link #getGeneration()} so the viewport cache knows to rebuild.
 */
@Slf4j
public class TileStore
{
	public static final int TILE = 256;
	/** Marks a tile outside every layer: the renderer fills it with the current layer's background. */
	public static final BufferedImage NONE = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
	public static final int MIN_LEVEL = -3;
	/**
	 * Soft cap of the LRU. Tiles drawn by the last two viewport rebuilds are never evicted, so a
	 * view needing more than this (a 2000x1400 map just above a level boundary) cannot evict its
	 * own tiles and decode them again for ever; it holds what it shows until the view moves.
	 */
	private static final long MAX_BYTES = 48L << 20;
	/**
	 * Requests not repeated by the latest viewport rebuild are dropped from the queue: every
	 * rebuild asks again for each tile it still shows, so an older request is for a view the
	 * player has left.
	 */
	private static final int STALE_FRAMES = 1;

	private final String base;
	private final Executor executor;
	private final Map<Integer, Set<String>> shipped = new HashMap<>();
	private final Map<Integer, Map<String, Integer>> solid = new HashMap<>();
	private final List<Layer> layers;
	private final int maxLevel;
	private final int minBundled;
	private final LinkedHashMap<Long, BufferedImage> lru = new LinkedHashMap<>(256, 0.75f, true);
	/** The rebuild (frame) that last drew each cached tile; guarded by lru. */
	private final Map<Long, Long> used = new HashMap<>();
	private long bytes;
	private final Map<Long, Long> pending = new ConcurrentHashMap<>();
	private final AtomicInteger generation = new AtomicInteger();
	private final AtomicLong frame = new AtomicLong();
	private volatile boolean closed;

	/**
	 * @param base     resource path prefix ending in "/", e.g. "/com/fairyringatlas/"
	 * @param executor where decoding runs; a direct executor decodes synchronously (previews)
	 */
	public TileStore(MapIndex index, List<Layer> layers, String base, Executor executor)
	{
		this.base = base;
		this.executor = executor;
		this.layers = layers;
		int max = Integer.MIN_VALUE;
		int min = Integer.MAX_VALUE;
		for (Integer z : index.getLevels())
		{
			List<String> keys = index.getTiles().get(String.valueOf(z));
			shipped.put(z, keys == null ? Collections.emptySet() : new HashSet<>(keys));
			Map<String, Integer> s = new HashMap<>();
			Map<String, String> src = index.getSolid().get(String.valueOf(z));
			if (src != null)
			{
				for (Map.Entry<String, String> e : src.entrySet())
				{
					s.put(e.getKey(), Layer.parseColor(e.getValue(), Color.BLACK).getRGB());
				}
			}
			solid.put(z, s);
			max = Math.max(max, z);
			min = Math.min(min, z);
		}
		this.maxLevel = max == Integer.MIN_VALUE ? 2 : max;
		this.minBundled = min == Integer.MAX_VALUE ? 2 : min;
	}

	public boolean hasImagery()
	{
		for (Set<String> s : shipped.values())
		{
			if (!s.isEmpty())
			{
				return true;
			}
		}
		return false;
	}

	public int getGeneration()
	{
		return generation.get();
	}

	public int getMaxLevel()
	{
		return maxLevel;
	}

	/** The level whose 2^z ppt is the smallest at or above p, capped to the finest bundled level. */
	public int levelFor(double ppt)
	{
		int z = (int) Math.ceil(Math.log(ppt) / Math.log(2) - 1e-9);
		return Math.max(MIN_LEVEL, Math.min(maxLevel, z));
	}

	/** World tiles spanned by one tile at level z. */
	public static double span(int z)
	{
		return TILE / Math.pow(2, z);
	}

	/** Marks the start of a viewport rebuild; requests made before are aged by one frame. */
	public void beginFrame()
	{
		frame.incrementAndGet();
	}

	/** A cached tile, or null. */
	public BufferedImage get(int z, int tx, int ty)
	{
		synchronized (lru)
		{
			return lru.get(key(z, tx, ty));
		}
	}

	/** A cached tile (marked as drawn by this rebuild), or null after queueing it for decoding. */
	public BufferedImage request(int z, int tx, int ty)
	{
		long k = key(z, tx, ty);
		BufferedImage img;
		synchronized (lru)
		{
			img = lru.get(k);
			if (img != null)
			{
				used.put(k, frame.get());
			}
		}
		if (img != null || closed)
		{
			return img;
		}
		if (pending.put(k, frame.get()) == null)
		{
			try
			{
				executor.execute(() -> run(k, z, tx, ty));
			}
			catch (RejectedExecutionException e)
			{
				pending.remove(k);
			}
		}
		return get(z, tx, ty);
	}

	public void close()
	{
		closed = true;
		trim();
	}

	/** Drops every decoded tile (the interface has been closed a while); the store stays usable. */
	public void trim()
	{
		synchronized (lru)
		{
			lru.clear();
			used.clear();
			bytes = 0;
		}
	}

	private void run(long k, int z, int tx, int ty)
	{
		// a stale request is dropped, unless the client thread asked for it again meanwhile
		Long asked = pending.get(k);
		while (asked != null && frame.get() - asked > STALE_FRAMES)
		{
			if (pending.remove(k, asked))
			{
				return;
			}
			asked = pending.get(k);
		}
		if (asked == null)
		{
			return;
		}
		try
		{
			if (closed)
			{
				return;
			}
			load(z, tx, ty, true);
			generation.incrementAndGet();
		}
		catch (RuntimeException e)
		{
			log.warn("tile {} {}_{} failed", z, tx, ty, e);
		}
		finally
		{
			pending.remove(k);
		}
	}

	/**
	 * Loads or derives a tile on the calling (worker) thread. Decoded source tiles read only to
	 * derive a coarser level are not kept, so deriving cannot evict the tiles being derived.
	 */
	BufferedImage load(int z, int tx, int ty, boolean keep)
	{
		BufferedImage img = get(z, tx, ty);
		if (img != null)
		{
			return img;
		}
		Set<String> keys = shipped.get(z);
		if (keys != null)
		{
			String name = tx + "_" + ty;
			img = keys.contains(name) ? decode(z, name) : null;
			if (img == null)
			{
				Integer c = solid.get(z).get(name);
				img = c != null ? solidTile(c) : background(z, tx, ty);
			}
			if (!keep && img.getWidth() > 1)
			{
				return img;
			}
		}
		else if (z < maxLevel)
		{
			img = derive(z, tx, ty);
		}
		else
		{
			img = background(z, tx, ty);
		}
		put(key(z, tx, ty), img);
		return img;
	}

	private BufferedImage derive(int z, int tx, int ty)
	{
		BufferedImage[] kids = new BufferedImage[4];
		boolean allSolid = true;
		for (int i = 0; i < 4; i++)
		{
			kids[i] = load(z + 1, tx * 2 + (i & 1), ty * 2 + (i >> 1), false);
			allSolid &= kids[i].getWidth() == 1 && (kids[i] == NONE) == (kids[0] == NONE)
				&& kids[i].getRGB(0, 0) == kids[0].getRGB(0, 0);
		}
		if (allSolid)
		{
			return kids[0] == NONE ? NONE : solidTile(kids[0].getRGB(0, 0));
		}
		BufferedImage bg = background(z, tx, ty);
		int fallback = bg == NONE ? 0 : bg.getRGB(0, 0);
		int half = TILE / 2;
		int[] out = new int[TILE * TILE];
		int[] src = new int[TILE * TILE];
		for (int i = 0; i < 4; i++)
		{
			// child (2tx+dx, 2ty+dy): dx=1 is the east half; dy=1 is north, which is the top half
			int ox = (i & 1) * half;
			int oy = (i >> 1) == 1 ? 0 : half;
			BufferedImage kid = kids[i];
			if (kid.getWidth() == 1)
			{
				int c = kid == NONE ? fallback : kid.getRGB(0, 0);
				for (int y = 0; y < half; y++)
				{
					java.util.Arrays.fill(out, (oy + y) * TILE + ox, (oy + y) * TILE + ox + half, c);
				}
				continue;
			}
			kid.getRGB(0, 0, TILE, TILE, src, 0, TILE);
			for (int y = 0; y < half; y++)
			{
				int r0 = y * 2 * TILE;
				int r1 = r0 + TILE;
				for (int x = 0; x < half; x++)
				{
					int a = src[r0 + x * 2];
					int b = src[r0 + x * 2 + 1];
					int c = src[r1 + x * 2];
					int d = src[r1 + x * 2 + 1];
					int r = (((a >> 16) & 255) + ((b >> 16) & 255) + ((c >> 16) & 255) + ((d >> 16) & 255) + 2) >> 2;
					int g = (((a >> 8) & 255) + ((b >> 8) & 255) + ((c >> 8) & 255) + ((d >> 8) & 255) + 2) >> 2;
					int bl = ((a & 255) + (b & 255) + (c & 255) + (d & 255) + 2) >> 2;
					out[(oy + y) * TILE + ox + x] = (r << 16) | (g << 8) | bl;
				}
			}
		}
		BufferedImage img = new BufferedImage(TILE, TILE, BufferedImage.TYPE_INT_RGB);
		img.setRGB(0, 0, TILE, TILE, out, 0, TILE);
		return img;
	}

	private BufferedImage decode(int z, String name)
	{
		String path = base + "map/" + z + "/" + name + ".png";
		try (InputStream in = TileStore.class.getResourceAsStream(path))
		{
			if (in == null)
			{
				return null;
			}
			// decode in memory: ImageIO's default stream cache writes a temp file per image
			BufferedImage raw;
			synchronized (ImageIO.class)
			{
				raw = ImageIO.read(new MemoryCacheImageInputStream(in));
			}
			if (raw == null)
			{
				return null;
			}
			if (raw.getType() == BufferedImage.TYPE_INT_RGB)
			{
				return raw;
			}
			BufferedImage img = new BufferedImage(raw.getWidth(), raw.getHeight(), BufferedImage.TYPE_INT_RGB);
			Graphics2D g = img.createGraphics();
			g.drawImage(raw, 0, 0, null);
			g.dispose();
			return img;
		}
		catch (IOException e)
		{
			log.warn("cannot read {}", path, e);
			return null;
		}
	}

	/** The background of the layer overlapping a tile, or NONE when the tile is outside every layer. */
	private BufferedImage background(int z, int tx, int ty)
	{
		double s = span(z);
		for (Layer l : layers)
		{
			int[] b = l.getBounds();
			if (b[0] < (tx + 1) * s && b[2] > tx * s && b[1] < (ty + 1) * s && b[3] > ty * s)
			{
				return solidTile(l.getBackgroundColor().getRGB());
			}
		}
		return NONE;
	}

	private static BufferedImage solidTile(int rgb)
	{
		BufferedImage img = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
		img.setRGB(0, 0, rgb);
		return img;
	}

	private void put(long k, BufferedImage img)
	{
		synchronized (lru)
		{
			if (closed)
			{
				return;
			}
			BufferedImage old = lru.put(k, img);
			bytes += weight(img) - (old == null ? 0 : weight(old));
			used.putIfAbsent(k, frame.get());
			// evict least recently used first, but never what the last two rebuilds drew
			long keepFrom = frame.get() - 1;
			Iterator<Map.Entry<Long, BufferedImage>> it = lru.entrySet().iterator();
			while (bytes > MAX_BYTES && it.hasNext())
			{
				Map.Entry<Long, BufferedImage> e = it.next();
				Long u = used.get(e.getKey());
				if (u != null && u >= keepFrom)
				{
					continue;
				}
				bytes -= weight(e.getValue());
				used.remove(e.getKey());
				it.remove();
			}
		}
	}

	private static long weight(BufferedImage img)
	{
		return (long) img.getWidth() * img.getHeight() * 4 + 64;
	}

	static long key(int z, int tx, int ty)
	{
		return ((long) (z + 16) << 48) | ((long) (tx & 0xFFFFFF) << 24) | (ty & 0xFFFFFF);
	}

	/** The bundled level used for a quick coarse fallback. */
	int coarsestBundled()
	{
		return minBundled;
	}

	/** Test hook: shipped tile names of a bundled level. */
	List<String> shippedTiles(int z)
	{
		Set<String> s = shipped.get(z);
		return s == null ? Collections.emptyList() : new ArrayList<>(s);
	}
}
