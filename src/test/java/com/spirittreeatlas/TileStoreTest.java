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
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/** Tile loading and level derivation against the fixture: one 256px tile, red west half, blue east. */
public class TileStoreTest
{
	private static final String FIXTURES = "/fixtures/";

	private final RingRepository repo = RingRepository.load(new Gson(), FIXTURES);

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
}
