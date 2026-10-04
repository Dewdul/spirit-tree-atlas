/*
 * Copyright (c) 2026, Dewdul
 * All rights reserved.
 * Licensed under the BSD 2-Clause License. See LICENSE.
 */
package com.spirittreeatlas;

import com.google.gson.Gson;
import java.awt.Color;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;
import org.junit.Test;

/** Tile loading and level derivation against the fixture: one 256px tile, red west half, blue east. */
public class TileStoreTest
{
	private static final String FIXTURES = "/fixtures/";

	private final TreeRepository repo = TreeRepository.load(new Gson(), FIXTURES);

	@Test
	public void levelChoice()
	{
		TileStore t = new TileStore(repo.getIndex(), repo.getLayers(), FIXTURES, Runnable::run);
		assertEquals(2, t.levelFor(4));
		assertEquals(2, t.levelFor(16));
		assertEquals(2, t.levelFor(3));
		assertEquals(1, t.levelFor(2));
		assertEquals(0, t.levelFor(0.75));
		assertEquals(-1, t.levelFor(0.5));
		assertEquals(-3, t.levelFor(0.125));
		assertEquals(64, TileStore.span(2), 1e-9);
		assertEquals(512, TileStore.span(-1), 1e-9);
	}

	@Test
	public void decodesShippedTiles()
	{
		TileStore t = new TileStore(repo.getIndex(), repo.getLayers(), FIXTURES, Runnable::run);
		BufferedImage img = t.request(2, 40, 50);
		assertNotNull(img);
		assertEquals(BufferedImage.TYPE_INT_RGB, img.getType());
		assertEquals(256, img.getWidth());
		assertEquals(0xC80000, img.getRGB(10, 10) & 0xFFFFFF);
		assertEquals(0x0000C8, img.getRGB(200, 10) & 0xFFFFFF);
		// a solid tile from the index, and a missing one taking the layer background
		assertEquals(0x00FF00, t.request(2, 41, 50).getRGB(0, 0) & 0xFFFFFF);
		assertEquals(0x102030, t.request(2, 40, 51).getRGB(0, 0) & 0xFFFFFF);
		// outside every layer
		assertSame(TileStore.NONE, t.request(2, 2, 2));
	}

	@Test
	public void derivesCoarserLevels()
	{
		TileStore t = new TileStore(repo.getIndex(), repo.getLayers(), FIXTURES, Runnable::run);
		// z=1 tile (20,25) has children (40..41, 50..51); (40,50) is the south-west child
		BufferedImage img = t.request(1, 20, 25);
		assertNotNull(img);
		assertEquals(256, img.getWidth());
		assertEquals(0xC80000, img.getRGB(10, 200) & 0xFFFFFF);
		assertEquals(0x0000C8, img.getRGB(100, 200) & 0xFFFFFF);
		// the south-east child is the solid green tile, the north half is background
		assertEquals(0x00FF00, img.getRGB(200, 200) & 0xFFFFFF);
		assertEquals(0x102030, img.getRGB(10, 10) & 0xFFFFFF);
		// two levels down still works
		assertNotNull(t.request(0, 10, 12));
	}

	@Test
	public void asyncRequestsFillInLater()
	{
		List<Runnable> queue = new ArrayList<>();
		TileStore t = new TileStore(repo.getIndex(), repo.getLayers(), FIXTURES, queue::add);
		int gen = t.getGeneration();
		assertNull(t.request(2, 40, 50));
		assertNull(t.request(2, 40, 50));
		assertEquals(1, queue.size());
		queue.get(0).run();
		assertTrue(t.getGeneration() > gen);
		assertNotNull(t.get(2, 40, 50));
	}

	@Test
	public void requestsTheViewMovedPastAreDropped()
	{
		List<Runnable> queue = new ArrayList<>();
		TileStore t = new TileStore(repo.getIndex(), repo.getLayers(), FIXTURES, queue::add);
		assertNull(t.request(2, 40, 50));
		t.beginFrame();
		t.beginFrame();
		// not asked for by the last rebuild: dropped, and a later request queues it again
		queue.remove(0).run();
		assertNull(t.get(2, 40, 50));
		assertNull(t.request(2, 40, 50));
		assertEquals(1, queue.size());
		// asked again before the worker got to it: it is still wanted, so it loads
		t.beginFrame();
		t.beginFrame();
		assertNull(t.request(2, 40, 50));
		assertEquals(1, queue.size());
		queue.remove(0).run();
		assertNotNull(t.get(2, 40, 50));
	}

	@Test
	public void rendererFillsTheView()
	{
		TileStore t = new TileStore(repo.getIndex(), repo.getLayers(), FIXTURES, Runnable::run);
		MapView v = MapView.of(repo.surface(), new Rectangle(0, 0, 256, 256)).centerOn(2592, 3232, 4);
		BufferedImage out = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
		assertTrue(MapRenderer.render(out, v, t, Color.BLACK));
		// the view is exactly the fixture tile at 1:1
		assertEquals(0xC80000, out.getRGB(10, 128) & 0xFFFFFF);
		assertEquals(0x0000C8, out.getRGB(250, 128) & 0xFFFFFF);
	}

	@Test
	public void zoomingOutDrawsCachedFinerTilesWhileTheLevelLoads()
	{
		List<Runnable> queue = new ArrayList<>();
		TileStore t = new TileStore(repo.getIndex(), repo.getLayers(), FIXTURES, queue::add);
		t.request(2, 40, 50);
		queue.remove(0).run();
		// at 2 ppt the wanted level is z=1, still queued; its z=2 child is cached
		MapView v = MapView.of(repo.surface(), new Rectangle(0, 0, 256, 256)).centerOn(2592, 3232, 2);
		BufferedImage out = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
		assertTrue(!MapRenderer.render(out, v, t, Color.BLACK));
		assertEquals(0xC80000, out.getRGB(80, 128) & 0xFFFFFF);
		assertEquals(0x0000C8, out.getRGB(170, 128) & 0xFFFFFF);
	}

	@Test
	public void theNewestRequestIsServedFirst()
	{
		List<Runnable> queue = new ArrayList<>();
		TileStore t = new TileStore(repo.getIndex(), repo.getLayers(), FIXTURES, queue::add);
		t.request(2, 40, 50);
		t.request(2, 41, 50);
		t.request(2, 40, 51);
		assertEquals(3, queue.size());
		// whichever run comes first takes the newest tile, whatever order the executor keeps
		queue.remove(0).run();
		assertNotNull(t.get(2, 40, 51));
		assertNull(t.get(2, 41, 50));
		assertNull(t.get(2, 40, 50));
		queue.remove(0).run();
		assertNotNull(t.get(2, 41, 50));
		assertNull(t.get(2, 40, 50));
		queue.remove(0).run();
		assertNotNull(t.get(2, 40, 50));
		assertTrue(t.idle());
	}

	@Test
	public void staleRequestsAreDroppedBehindNewerOnes()
	{
		List<Runnable> queue = new ArrayList<>();
		TileStore t = new TileStore(repo.getIndex(), repo.getLayers(), FIXTURES, queue::add);
		t.request(2, 40, 50);
		t.beginFrame();
		t.beginFrame();
		t.request(2, 41, 50);
		queue.remove(0).run();
		queue.remove(0).run();
		// the newer request loaded; the older one, not asked for again by the last rebuild, did not
		assertNotNull(t.get(2, 41, 50));
		assertNull(t.get(2, 40, 50));
		assertEquals(0, t.decodes.get());
		assertTrue(t.idle());
		// asking again queues it afresh
		assertNull(t.request(2, 40, 50));
		queue.remove(0).run();
		assertNotNull(t.get(2, 40, 50));
	}

	@Test
	public void aTileIsNeverDecodedTwiceAtOnce() throws Exception
	{
		AtomicInteger inside = new AtomicInteger();
		AtomicInteger most = new AtomicInteger();
		TileStore t = new TileStore(repo.getIndex(), repo.getLayers(), FIXTURES, Runnable::run)
		{
			@Override
			BufferedImage decode(int z, String name)
			{
				most.accumulateAndGet(inside.incrementAndGet(), Math::max);
				try
				{
					Thread.sleep(150);
					return super.decode(z, name);
				}
				catch (InterruptedException e)
				{
					throw new IllegalStateException(e);
				}
				finally
				{
					inside.decrementAndGet();
				}
			}
		};
		// the shipped tile itself, and three coarser tiles derived from it, all asked for at once
		int[][] asks = {{2, 40, 50}, {1, 20, 25}, {0, 10, 12}, {-1, 5, 6}};
		ExecutorService pool = Executors.newFixedThreadPool(12);
		CountDownLatch go = new CountDownLatch(1);
		List<Future<BufferedImage>> got = new ArrayList<>();
		try
		{
			for (int i = 0; i < 12; i++)
			{
				int[] a = asks[i % asks.length];
				got.add(pool.submit(() ->
				{
					go.await();
					return t.load(a[0], a[1], a[2], true);
				}));
			}
			go.countDown();
			for (Future<BufferedImage> f : got)
			{
				assertNotNull(f.get(10, TimeUnit.SECONDS));
			}
		}
		finally
		{
			pool.shutdownNow();
		}
		assertEquals(1, most.get());
		assertEquals(1, t.decodes.get());
		for (int i = 0; i < got.size(); i += asks.length)
		{
			assertSame(t.get(2, 40, 50), got.get(i).get());
		}
		assertEquals(0x0000C8, t.get(1, 20, 25).getRGB(100, 200) & 0xFFFFFF);
		assertTrue(t.idle());
		assertTrue(t.consistent());
	}

	@Test
	public void manyWorkersKeepTheCacheConsistent() throws Exception
	{
		ExecutorService pool = Executors.newFixedThreadPool(8);
		try
		{
			TileStore t = new TileStore(repo.getIndex(), repo.getLayers(), FIXTURES, pool);
			int[][] asks = {{2, 40, 50}, {2, 41, 50}, {2, 40, 51}, {1, 20, 25}, {0, 10, 12}, {-1, 5, 6}, {-2, 2, 3}, {-3, 1, 1}};
			// rebuilds asking for everything while the cache is trimmed under them
			for (int round = 0; round < 300; round++)
			{
				t.beginFrame();
				for (int[] a : asks)
				{
					t.request(a[0], a[1], a[2]);
				}
				if (round % 7 == 0)
				{
					t.trimFinerThan(0);
				}
				if (round % 31 == 0)
				{
					t.trim();
				}
				assertTrue(t.consistent());
			}
			// then rebuilds without trims until everything is in
			long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
			boolean all = false;
			while (!all && System.nanoTime() < end)
			{
				t.beginFrame();
				all = true;
				for (int[] a : asks)
				{
					all &= t.request(a[0], a[1], a[2]) != null;
				}
				Thread.sleep(5);
			}
			assertTrue(all);
			pool.shutdown();
			assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
			assertTrue(t.idle());
			assertTrue(t.consistent());
		}
		finally
		{
			pool.shutdownNow();
		}
	}

	@Test
	public void derivingKeepsOnlyWhatIsDrawn()
	{
		TileStore t = new TileStore(repo.getIndex(), repo.getLayers(), FIXTURES, Runnable::run);
		assertNotNull(t.request(0, 10, 12));
		assertEquals(1, t.decodes.get());
		// the decoded source and the z=1 tile in between are not kept; solid ones cost nothing
		assertNull(t.get(2, 40, 50));
		assertNull(t.get(1, 20, 25));
		assertEquals(0x00FF00, t.get(2, 41, 50).getRGB(0, 0) & 0xFFFFFF);
		assertEquals(1, t.fullTiles());
		assertTrue(t.consistent());
	}

	@Test
	public void trimmingToTheOverviewKeepsItsLevels()
	{
		TileStore t = new TileStore(repo.getIndex(), repo.getLayers(), FIXTURES, Runnable::run);
		t.request(2, 40, 50);
		t.request(1, 20, 25);
		t.request(0, 10, 12);
		t.request(-1, 5, 6);
		t.trimFinerThan(0);
		assertNull(t.get(2, 40, 50));
		assertNull(t.get(1, 20, 25));
		assertNotNull(t.get(0, 10, 12));
		assertNotNull(t.get(-1, 5, 6));
		assertTrue(t.consistent());
		t.trim();
		assertNull(t.get(0, 10, 12));
		assertEquals(0, t.cachedBytes());
	}

	@Test
	public void theCoarseLevelCoversTheViewBeforeTheSlowTiles()
	{
		assumeTrue(TileStoreTest.class.getResource(SpiritTreeAtlasPlugin.RESOURCES + "map/index.json") != null);
		TreeRepository real = TreeRepository.load(new Gson(), SpiritTreeAtlasPlugin.RESOURCES);
		List<Runnable> queue = new ArrayList<>();
		TileStore t = new TileStore(real.getIndex(), real.getLayers(), SpiritTreeAtlasPlugin.RESOURCES, queue::add);
		MapView v = TileBench.view(real, TileBench.SIZES[0]);
		assertEquals(0, t.levelFor(v.getPpt()));
		BufferedImage out = new BufferedImage(v.getW(), v.getH(), BufferedImage.TYPE_INT_RGB);
		assertTrue(!MapRenderer.render(out, v, t, Color.BLACK));
		int tiles = TileBench.tilesInView(v, t, null);
		int runs = 0;
		while (TileBench.tilesInView(v, t, Boolean.TRUE) < tiles)
		{
			queue.remove(0).run();
			runs++;
		}
		// covered by z=-1 alone (a decode each), before any z=0 tile (16 decodes each)
		assertTrue(runs > 0);
		assertEquals(runs, t.decodes.get());
		assertEquals(0, t.derived.get());
	}

	@Test
	public void prefetchLoadsTheViewWithoutDrawing()
	{
		assumeTrue(TileStoreTest.class.getResource(SpiritTreeAtlasPlugin.RESOURCES + "map/index.json") != null);
		TreeRepository real = TreeRepository.load(new Gson(), SpiritTreeAtlasPlugin.RESOURCES);
		List<Runnable> queue = new ArrayList<>();
		TileStore t = new TileStore(real.getIndex(), real.getLayers(), SpiritTreeAtlasPlugin.RESOURCES, queue::add);
		MapView v = TileBench.view(real, TileBench.SIZES[2]);
		MapRenderer.prefetch(v, t);
		assertTrue(queue.size() > 0);
		while (!queue.isEmpty())
		{
			queue.remove(0).run();
		}
		// the menu then opens complete on its first frame
		BufferedImage out = new BufferedImage(v.getW(), v.getH(), BufferedImage.TYPE_INT_RGB);
		assertTrue(MapRenderer.render(out, v, t, Color.BLACK));
		assertTrue(queue.isEmpty());
	}
}
