/*
 * Copyright (c) 2026, Dewdul
 * All rights reserved.
 * Licensed under the BSD 2-Clause License. See LICENSE.
 */
package com.spirittreeatlas;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
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
	static final int WORKERS = 2;

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
	/** Each queued tile and the rebuild (frame) that last asked for it. */
	private final Map<Long, Long> pending = new ConcurrentHashMap<>();
	/** The queued tiles, newest first; each was handed one run, which takes the newest. */
	private final Deque<Long> queue = new ConcurrentLinkedDeque<>();
	/** Tiles being loaded or derived right now: anyone else who needs one waits for it. */
	private final Map<Long, CompletableFuture<BufferedImage>> loading = new ConcurrentHashMap<>();
	private final AtomicInteger generation = new AtomicInteger();
	private final AtomicLong frame = new AtomicLong();
	private volatile boolean closed;

	/** Test hooks: PNGs decoded and tiles derived since the store was made. */
	final AtomicInteger decodes = new AtomicInteger();
	final AtomicInteger derived = new AtomicInteger();

	/**
	 * The decoding workers: {@link #WORKERS} daemon threads just below normal priority, which end
	 * after a while idle. A fixed count: the Plugin Hub does not allow java.lang.Runtime, so the
	 * cores are not counted. Which tile each one takes is the store's choice (the newest request),
	 * not the executor's.
	 */
	public static ExecutorService newExecutor()
	{
		ThreadPoolExecutor ex = new ThreadPoolExecutor(WORKERS, WORKERS, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r ->
		{
			Thread t = new Thread(r, "spirit-tree-atlas-tiles");
			t.setDaemon(true);
			t.setPriority(Thread.NORM_PRIORITY - 1);
			return t;
		});
		ex.allowCoreThreadTimeOut(true);
		return ex;
	}

	/**
	 * @param base     resource path prefix ending in "/", e.g. "/com/spirittreeatlas/"
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
			queue.offerFirst(k);
			try
			{
				executor.execute(this::runNewest);
			}
			catch (RejectedExecutionException e)
			{
				queue.removeFirstOccurrence(k);
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
		// and the queued ones: loaded after the trim (a logout mid-load), they would stay until the
		// menu is next opened and closed; at most the tiles being loaded right now still come in
		queue.clear();
		pending.clear();
		synchronized (lru)
		{
			lru.clear();
			used.clear();
			bytes = 0;
		}
	}

	/** Drops the decoded tiles of the levels finer than z, keeping the overview at and below it. */
	public void trimFinerThan(int z)
	{
		synchronized (lru)
		{
			Iterator<Map.Entry<Long, BufferedImage>> it = lru.entrySet().iterator();
			while (it.hasNext())
			{
				Map.Entry<Long, BufferedImage> e = it.next();
				if (level(e.getKey()) > z)
				{
					bytes -= weight(e.getValue());
					used.remove(e.getKey());
					it.remove();
				}
			}
		}
	}

	/** One worker run: the newest queued tile, so the current view comes before older views. */
	private void runNewest()
	{
		Long k = queue.pollFirst();
		if (k != null)
		{
			run(k, level(k), (int) (k >> 24) << 8 >> 8, (int) (long) k << 8 >> 8);
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
	 * derive a coarser level are not kept, so deriving cannot evict the tiles being derived. A tile
	 * another worker is already loading is waited for, never loaded twice at once; the wait always
	 * points to a finer level or another tile, so it cannot come back round.
	 */
	BufferedImage load(int z, int tx, int ty, boolean keep)
	{
		BufferedImage img = get(z, tx, ty);
		if (img != null)
		{
			return img;
		}
		long k = key(z, tx, ty);
		CompletableFuture<BufferedImage> mine = new CompletableFuture<>();
		CompletableFuture<BufferedImage> other = loading.putIfAbsent(k, mine);
		if (other != null)
		{
			img = other.join();
			if (keep)
			{
				put(k, img);
			}
			return img;
		}
		try
		{
			img = get(z, tx, ty);
			if (img == null)
			{
				img = loadNow(z, tx, ty, keep);
			}
			mine.complete(img);
			return img;
		}
		catch (RuntimeException | Error e)
		{
			mine.completeExceptionally(e);
			throw e;
		}
		finally
		{
			loading.remove(k, mine);
		}
	}

	private BufferedImage loadNow(int z, int tx, int ty, boolean keep)
	{
		BufferedImage img;
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
		}
		else if (z < maxLevel)
		{
			img = derive(z, tx, ty);
		}
		else
		{
			img = background(z, tx, ty);
		}
		// a source read only to derive a coarser tile is not kept (a solid one costs nothing): a
		// z=0 overview would otherwise fill the cache with four times its size of z=1 tiles that
		// are never drawn
		if (keep || img.getWidth() == 1)
		{
			put(key(z, tx, ty), img);
		}
		return img;
	}

	private BufferedImage derive(int z, int tx, int ty)
	{
		derived.incrementAndGet();
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
		BufferedImage img = new BufferedImage(TILE, TILE, BufferedImage.TYPE_INT_RGB);
		int[] out = pixels(img);
		for (int i = 0; i < 4; i++)
		{
			// child (2tx+dx, 2ty+dy): dx=1 is the east half; dy=1 is north, which is the top half
			int ox = (i & 1) * half;
			int oy = (i >> 1) == 1 ? 0 : half;
			BufferedImage kid = kids[i];
			if (kid.getWidth() == 1)
			{
				int c = (kid == NONE ? fallback : kid.getRGB(0, 0)) & 0xFFFFFF;
				for (int y = 0; y < half; y++)
				{
					Arrays.fill(out, (oy + y) * TILE + ox, (oy + y) * TILE + ox + half, c);
				}
				continue;
			}
			int[] src = pixels(kid);
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
		return img;
	}

	/**
	 * The RGB pixels of a 256x256 tile, row by row: the image's own array when it is a plain
	 * INT_RGB one (as every decoded and derived tile is), else a copy.
	 */
	private static int[] pixels(BufferedImage img)
	{
		if (img.getType() == BufferedImage.TYPE_INT_RGB && img.getRaster().getDataBuffer() instanceof DataBufferInt
			&& img.getRaster().getSampleModelTranslateX() == 0 && img.getRaster().getSampleModelTranslateY() == 0)
		{
			int[] data = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
			if (data.length == TILE * TILE)
			{
				return data;
			}
		}
		return img.getRGB(0, 0, TILE, TILE, null, 0, TILE);
	}

	/** Decodes one bundled tile (overridden by tests to watch the decoding). */
	BufferedImage decode(int z, String name)
	{
		decodes.incrementAndGet();
		String path = base + "map/" + z + "/" + name + ".png";
		try (InputStream in = TileStore.class.getResourceAsStream(path))
		{
			if (in == null)
			{
				return null;
			}
			// only the reader lookup shares ImageIO's registry (under the lock the client uses for
			// it); each worker decodes with its own reader, in memory: ImageIO's default stream
			// cache writes a temp file per image
			ImageReader reader;
			synchronized (ImageIO.class)
			{
				Iterator<ImageReader> it = ImageIO.getImageReadersByFormatName("png");
				reader = it.hasNext() ? it.next() : null;
			}
			if (reader == null)
			{
				return null;
			}
			BufferedImage raw;
			try (ImageInputStream iis = new MemoryCacheImageInputStream(in))
			{
				reader.setInput(iis, true, true);
				raw = reader.read(0);
			}
			finally
			{
				reader.dispose();
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

	static int level(long key)
	{
		return (int) (key >>> 48) - 16;
	}

	/** Test hook: the bytes the decoded tiles take, as kept up to date. */
	long cachedBytes()
	{
		synchronized (lru)
		{
			return bytes;
		}
	}

	/** Test hook: whether the byte count matches the tiles, and every drawn mark has its tile. */
	boolean consistent()
	{
		synchronized (lru)
		{
			long n = 0;
			for (BufferedImage img : lru.values())
			{
				n += weight(img);
			}
			return n == bytes && lru.keySet().containsAll(used.keySet());
		}
	}

	/** Test hook: cached tiles that are whole images (not 1x1 solid ones). */
	int fullTiles()
	{
		synchronized (lru)
		{
			int n = 0;
			for (BufferedImage img : lru.values())
			{
				n += img.getWidth() > 1 ? 1 : 0;
			}
			return n;
		}
	}

	/** Test hook: nothing queued, asked for or being loaded. */
	boolean idle()
	{
		return pending.isEmpty() && queue.isEmpty() && loading.isEmpty();
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
