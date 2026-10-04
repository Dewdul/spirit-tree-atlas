/*
 * Copyright (c) 2026, Dewdul
 * All rights reserved.
 * Licensed under the BSD 2-Clause License. See LICENSE.
 */
package com.spirittreeatlas;

import com.google.gson.Gson;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * The real bundled data (DESIGN 3 and 5): trees.json, map/index.json, the tiles, the hub's image
 * and size limits, and the source budget. These tests never skip.
 */
public class TreeDataTest
{
	private static final String REAL = SpiritTreeAtlasPlugin.RESOURCES;
	/** DESIGN 3.2: the map's layers; the house's "poh" is card-only and never in index.json. */
	private static final Set<String> INDEX_LAYERS = new HashSet<>(Arrays.asList(Layer.SURFACE, Layer.PRIFDDINAS));
	private static final Set<String> TREE_LAYERS = new HashSet<>(Arrays.asList(Layer.SURFACE, Layer.PRIFDDINAS, Layer.POH));
	private static final List<Integer> HOUSE_VALUES = Arrays.asList(1, 2, 3, 4, 5, 6, 8, 9, 13);
	private static final List<String> REQUIREMENT_PREFIXES = Arrays.asList("Quest:", "Skill:", "Unlock:", "Item:", "Diary:");

	/** Hub limits: the bot rejects an image whose decoded size (w*h*4) is about 1 MiB or more. */
	private static final long MAX_DECODED = 950_000;
	private static final long MAX_RESOURCES = 7_969_177; // 7.6 MiB, DESIGN rule 6
	private static final File RESOURCE_DIR = new File("src/main/resources");
	/** DESIGN rule 7: raw bytes of src/main/java, an early warning far below the bot's budget (as Fairy Ring Atlas). */
	private static final long MAX_SOURCE = 300_000;

	private static TreeRepository real()
	{
		assertNotNull("trees.json missing", TreeDataTest.class.getResource(REAL + "trees.json"));
		assertNotNull("map/index.json missing", TreeDataTest.class.getResource(REAL + "map/index.json"));
		TreeRepository repo = TreeRepository.load(new Gson(), REAL);
		assertTrue(repo.isTreesLoaded());
		assertTrue(repo.isIndexLoaded());
		return repo;
	}

	@Test
	public void idsAndLabelsAreUnique()
	{
		TreeRepository repo = real();
		assertEquals("Spirit Tree Locations", repo.getTitle());
		assertEquals("5f5f5f", repo.getUnavailableColour());
		assertEquals(14, repo.getTrees().size());
		Set<String> ids = new HashSet<>();
		Set<String> menuLabels = new HashSet<>();
		Set<String> labels = new HashSet<>();
		int houses = 0;
		for (Tree t : repo.getTrees())
		{
			assertTrue("id " + t.getId(), t.getId().matches("[A-Z][A-Z0-9_]*") && ids.add(t.getId()));
			assertTrue("menu label " + t.getMenuLabel(), menuLabels.add(t.getMenuLabel().toLowerCase()));
			assertTrue("label " + t.getLabel(), labels.add(t.getLabel()));
			assertTrue(t.getId() + " kind " + t.getKind(), Arrays.asList("fixed", "patch", "quest", "house").contains(t.getKind()));
			assertTrue(t.getId() + " match " + t.getMatch(), Arrays.asList("exact", "prefix").contains(t.getMatch()));
			assertEquals(t.getId() + ": only the house matches by prefix", t.isHouse(), t.isPrefix());
			houses += t.isHouse() ? 1 : 0;
		}
		assertEquals(1, houses);
	}

	@Test
	public void previousValuesAreTheMenuRows()
	{
		Set<Integer> values = new HashSet<>();
		for (Tree t : real().getTrees())
		{
			assertTrue(t.getId() + " " + t.getPreviousValue(), t.getPreviousValue() >= 1 && t.getPreviousValue() <= 14);
			assertTrue("duplicate previous value " + t.getPreviousValue(), values.add(t.getPreviousValue()));
		}
		assertEquals(14, values.size());
	}

	@Test
	public void everyTreeLiesInItsLayer()
	{
		TreeRepository repo = real();
		for (Tree t : repo.getTrees())
		{
			assertTrue(t.getId() + " layer " + t.getLayer(), TREE_LAYERS.contains(t.getLayer()));
			if (t.isHouse())
			{
				assertEquals(Layer.POH, t.getLayer());
				continue;
			}
			Layer l = indexLayer(repo, t.getLayer());
			assertNotNull("index.json has no layer " + t.getLayer() + " (for " + t.getId() + ")", l);
			assertTrue(t.getId() + " outside " + l.getId(), l.contains(t.getX() + 0.5, t.getY() + 0.5));
		}
		for (Portal p : repo.getPortals())
		{
			assertNotNull("portal to a layer index.json lacks: " + p.getLayer(), indexLayer(repo, p.getLayer()));
			assertTrue("portal not on the surface", repo.surface().contains(p.getX(), p.getY()));
		}
	}

	@Test
	public void housePortals()
	{
		TreeRepository repo = real();
		List<Integer> values = new ArrayList<>();
		for (Portal p : repo.getHousePortals())
		{
			values.add(p.getValue());
			assertNotNull(p.getTown());
			Layer l = indexLayer(repo, p.getLayer());
			assertNotNull("index.json has no layer " + p.getLayer() + " (house " + p.getTown() + ")", l);
			assertTrue(p.getTown() + " outside " + p.getLayer(), l.contains(p.getX() + 0.5, p.getY() + 0.5));
		}
		java.util.Collections.sort(values);
		assertEquals(HOUSE_VALUES, values);
		Tree house = repo.house();
		repo.placeHouse(1);
		assertEquals("Your house (Rimmington)", house.getName());
		assertTrue(house.isMapped());
		repo.placeHouse(9);
		assertEquals(Layer.PRIFDDINAS, house.getLayer());
	}

	@Test
	public void textIsAsciiAndWithinItsLimits()
	{
		TreeRepository repo = real();
		for (Tree t : repo.getTrees())
		{
			assertTrue(t.getId() + " label too long", t.getLabel().length() <= 24);
			assertTrue(t.getId() + " lockedHint too long", t.getLockedHint().length() <= 32);
			List<String> texts = new ArrayList<>(Arrays.asList(t.getMenuLabel(), t.getName(), t.getLabel(), t.getArea(), t.getLockedHint()));
			texts.addAll(t.getRequirements());
			texts.addAll(t.getPoi());
			texts.addAll(t.getNotes());
			texts.addAll(t.getDanger());
			for (String s : texts)
			{
				assertNotNull(t.getId(), s);
				assertTrue(t.getId() + ": not printable ASCII: " + s, s.matches("[\\x20-\\x7E]*"));
			}
			for (String r : t.getRequirements())
			{
				assertTrue(t.getId() + ": " + r, REQUIREMENT_PREFIXES.stream().anyMatch(r::startsWith));
			}
		}
		for (String r : repo.getGlobalRequirements())
		{
			assertTrue(r, r.matches("[\\x20-\\x7E]*") && REQUIREMENT_PREFIXES.stream().anyMatch(r::startsWith));
		}
		for (Portal p : repo.getHousePortals())
		{
			assertTrue(p.getTown(), p.getTown().matches("[\\x20-\\x7E]*"));
		}
	}

	@Test
	public void indexHasTheTwoLayers()
	{
		MapIndex index = real().getIndex();
		Set<String> ids = new HashSet<>();
		for (Layer l : index.getLayers())
		{
			assertTrue(l.getId() + " invalid", l.isValid());
			ids.add(l.getId());
		}
		assertEquals(INDEX_LAYERS, ids);
	}

	@Test
	public void indexIsConsistent()
	{
		MapIndex index = real().getIndex();
		assertEquals(256, index.getTileSize());
		// z=2 full detail and z=-1 overview only (DESIGN 3.2)
		assertEquals(new HashSet<>(Arrays.asList(-1, 2)), new HashSet<>(index.getLevels()));
		int count = 0;
		for (Map.Entry<String, List<String>> e : index.getTiles().entrySet())
		{
			assertTrue("tiles for a level not listed: " + e.getKey(), index.getLevels().contains(Integer.valueOf(e.getKey())));
			count += e.getValue().size();
		}
		assertTrue(count > 0);
		for (MapIndex.Label label : index.getLabels())
		{
			assertNotNull(label.getT());
			assertTrue(label.getS() >= 0 && label.getS() <= 2);
		}
		assertTrue(index.getIcons().size() > 1000);
		for (MapIndex.Icon icon : index.getIcons())
		{
			assertTrue(icon.getSprite() > 0 && icon.getX() > 0 && icon.getY() > 0);
		}
	}

	@Test
	public void tilesOnDiskEqualTheIndex()
	{
		MapIndex index = real().getIndex();
		File map = new File(RESOURCE_DIR, REAL.substring(1) + "map");
		assertTrue("no map directory at " + map.getAbsolutePath(), map.isDirectory());
		Set<String> listed = new HashSet<>();
		for (Map.Entry<String, List<String>> e : index.getTiles().entrySet())
		{
			for (String t : e.getValue())
			{
				assertTrue("listed twice: " + e.getKey() + "/" + t, listed.add(e.getKey() + "/" + t + ".png"));
			}
		}
		Set<String> onDisk = new HashSet<>();
		File[] levels = map.listFiles(File::isDirectory);
		assertNotNull(levels);
		for (File level : levels)
		{
			File[] pngs = level.listFiles((d, n) -> n.endsWith(".png"));
			assertNotNull(pngs);
			for (File png : pngs)
			{
				onDisk.add(level.getName() + "/" + png.getName());
			}
		}
		Set<String> unlisted = new HashSet<>(onDisk);
		unlisted.removeAll(listed);
		assertTrue("tiles shipped but not in index.json: " + unlisted, unlisted.isEmpty());
		Set<String> missing = new HashSet<>(listed);
		missing.removeAll(onDisk);
		assertTrue("tiles in index.json but missing: " + missing, missing.isEmpty());
	}

	@Test
	public void everyBundledImageIsWithinHubLimits() throws IOException
	{
		List<File> images = new ArrayList<>();
		collect(RESOURCE_DIR, images);
		assertTrue(images.size() > 1000);
		for (File f : images)
		{
			try (ImageInputStream in = ImageIO.createImageInputStream(f))
			{
				Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
				assertTrue("not an image: " + f, readers.hasNext());
				ImageReader reader = readers.next();
				reader.setInput(in);
				int w = reader.getWidth(0);
				int h = reader.getHeight(0);
				reader.dispose();
				assertTrue(f + " is " + w + "x" + h, w <= 256 && h <= 256);
				assertTrue(f + " decodes to " + (long) w * h * 4 + " bytes", (long) w * h * 4 < MAX_DECODED);
			}
		}
	}

	@Test
	public void resourcesFitTheBudget()
	{
		long total = size(RESOURCE_DIR);
		assertTrue("src/main/resources is " + total + " bytes, budget " + MAX_RESOURCES, total <= MAX_RESOURCES);
	}

	/**
	 * The hub's review bot tokenizes src/main/java with comments stripped and needs it under
	 * 200k tokens; this byte budget (comments included) warns long before that. Line ends count
	 * as one byte, as committed, so a CRLF checkout (core.autocrlf) measures the same.
	 */
	@Test
	public void javaSourceFitsTheReviewBudget() throws IOException
	{
		long total = 0;
		try (java.util.stream.Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(new File("src/main/java").toPath()))
		{
			for (java.nio.file.Path p : (Iterable<java.nio.file.Path>) walk.filter(f -> f.toString().endsWith(".java"))::iterator)
			{
				for (byte b : java.nio.file.Files.readAllBytes(p))
				{
					total += b == '\r' ? 0 : 1;
				}
			}
		}
		assertTrue("src/main/java is " + total + " bytes, budget " + MAX_SOURCE, total > 0 && total <= MAX_SOURCE);
	}

	@Test
	public void generatorsStayOutOfTheBuild() throws IOException
	{
		String settings = new String(java.nio.file.Files.readAllBytes(new File("settings.gradle").toPath()), java.nio.charset.StandardCharsets.UTF_8);
		assertFalse(settings, settings.contains("include"));
	}

	private static Layer indexLayer(TreeRepository repo, String id)
	{
		for (Layer l : repo.getIndex().getLayers())
		{
			if (l.getId().equals(id))
			{
				return l;
			}
		}
		return null;
	}

	private static void collect(File dir, List<File> out)
	{
		File[] kids = dir.listFiles();
		if (kids == null)
		{
			return;
		}
		for (File f : kids)
		{
			String n = f.getName().toLowerCase();
			if (f.isDirectory())
			{
				collect(f, out);
			}
			else if (n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".gif") || n.endsWith(".bmp"))
			{
				out.add(f);
			}
		}
	}

	private static long size(File f)
	{
		if (f.isFile())
		{
			return f.length();
		}
		long n = 0;
		File[] kids = f.listFiles();
		if (kids != null)
		{
			for (File k : kids)
			{
				n += size(k);
			}
		}
		return n;
	}
}
