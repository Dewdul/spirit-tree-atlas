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
import java.util.Iterator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * The bundled data (rings.json, map/index.json, the tiles and the hub's image and size limits) and
 * the repository against small fixtures.
 */
public class RingDataTest
{
	private static final String REAL = SpiritTreeAtlasPlugin.RESOURCES;
	private static final String FIXTURES = "/fixtures/";
	private static final Set<String> LAYER_IDS = new HashSet<>(Arrays.asList("surface", "zanaris", "abyss",
		"dorgesh_south", "fisher_realm", "enchanted_valley", "mor_ul_rek", "cosmic_plane", "gorak_plane", "yubiusk",
		"grimstone", "hollows", "poh"));

	/** Hub limits: the bot rejects an image whose decoded size (w*h*4) is about 1 MiB or more. */
	private static final long MAX_DECODED = 950_000;
	private static final long MAX_RESOURCES = 7_969_177; // 7.6 MiB, DESIGN section 1
	private static final File RESOURCE_DIR = new File("src/main/resources");
	/** Bytes of Java and JSON text in src/main (about 312 KB today); DESIGN Deviations, "Size". */
	private static final long MAX_SOURCE_TEXT = 300_000;

	private static RingRepository real()
	{
		assertNotNull("rings.json missing", RingDataTest.class.getResource(REAL + "rings.json"));
		assertNotNull("map/index.json missing", RingDataTest.class.getResource(REAL + "map/index.json"));
		RingRepository repo = RingRepository.load(new Gson(), REAL);
		assertTrue(repo.isRingsLoaded());
		assertTrue(repo.isIndexLoaded());
		return repo;
	}

	@Test
	public void realCodesAreUniqueAndValid()
	{
		RingRepository repo = real();
		Set<String> codes = new HashSet<>();
		for (Ring r : repo.getRings())
		{
			assertTrue(r.getKind(), Arrays.asList("destination", "sequence", "exit").contains(r.getKind()));
			if (Ring.KIND_DESTINATION.equals(r.getKind()))
			{
				assertTrue("bad code " + r.getCode(), DialMath.isCode(r.getCode()));
				assertTrue("duplicate " + r.getCode(), codes.add(r.getCode()));
				assertNotNull(r.getName());
			}
		}
		assertTrue("expected the full ring list, got " + codes.size(), codes.size() >= 50);
		assertNotNull(repo.ring("AIQ"));
		assertNotNull(repo.ring("DIQ"));
	}

	@Test
	public void realRingsLieInTheirLayers()
	{
		RingRepository repo = real();
		for (Ring r : repo.getRings())
		{
			assertTrue(r.getCode() + " layer " + r.getLayer(), LAYER_IDS.contains(r.getLayer()));
			if (Layer.POH.equals(r.getLayer()))
			{
				assertFalse(r.isMapped());
				continue;
			}
			Layer l = null;
			for (Layer il : repo.getIndex().getLayers())
			{
				l = r.getLayer().equals(il.getId()) ? il : l;
			}
			assertNotNull("index.json has no layer " + r.getLayer(), l);
			assertTrue(r.getCode() + " " + r.getName() + " outside " + l.getId(), l.contains(r.getX() + 0.5, r.getY() + 0.5));
		}
		for (Portal p : repo.getPortals())
		{
			assertNotNull("portal to unknown layer " + p.getLayer(), repo.layer(p.getLayer()));
			assertTrue("portal not on the surface", repo.surface().contains(p.getX(), p.getY()));
		}
	}

	@Test
	public void realIndexIsConsistent()
	{
		RingRepository repo = real();
		MapIndex index = repo.getIndex();
		assertEquals(256, index.getTileSize());
		assertTrue(index.getLevels().contains(2));
		for (Layer l : index.getLayers())
		{
			assertTrue("unknown layer id " + l.getId(), LAYER_IDS.contains(l.getId()));
			assertTrue(l.isValid());
		}
		assertNotNull(repo.layer(Layer.SURFACE));
		int count = 0;
		for (Map.Entry<String, List<String>> e : index.getTiles().entrySet())
		{
			assertTrue("tiles for a level not listed: " + e.getKey(), index.getLevels().contains(Integer.valueOf(e.getKey())));
			for (String t : e.getValue())
			{
				String path = REAL + "map/" + e.getKey() + "/" + t + ".png";
				assertNotNull("missing " + path, RingDataTest.class.getResource(path));
				count++;
			}
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
	public void realSearchFindsTheBossesBesideRings()
	{
		RingRepository repo = real();
		assertTrue(repo.ring("DJR").matches("yama"));
		assertTrue(repo.ring("AIQ").matches("royal titans"));
		assertTrue(repo.ring("ALQ").matches("araxxor"));
		assertTrue(repo.ring("CKS").matches("grotesque"));
	}

	/**
	 * The hub's review bot tokenizes src/main/java with comments stripped and needs it under
	 * 200k tokens; this byte budget (comments included) warns long before that.
	 */
	@Test
	public void javaSourceFitsTheReviewBudget()
	{
		long total = textSize(new File("src/main/java"));
		assertTrue("src/main/java is " + total + " bytes, budget " + MAX_SOURCE_TEXT, total <= MAX_SOURCE_TEXT);
	}

	private static long textSize(File f)
	{
		String n = f.getName();
		if (f.isFile())
		{
			return n.endsWith(".java") ? f.length() : 0;
		}
		long total = 0;
		File[] kids = f.listFiles();
		if (kids != null)
		{
			for (File k : kids)
			{
				total += textSize(k);
			}
		}
		return total;
	}

	@Test
	public void realTilesMatchTheIndexExactly()
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
		long total = 0;
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
			total += f.length();
		}
		assertTrue("bundled images total " + total, total > 0);
	}

	@Test
	public void resourcesFitTheBudget()
	{
		long total = size(RESOURCE_DIR);
		assertTrue("src/main/resources is " + total + " bytes, budget " + MAX_RESOURCES, total <= MAX_RESOURCES);
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

	@Test
	public void fixtureLoads()
	{
		RingRepository repo = RingRepository.load(new Gson(), FIXTURES);
		assertTrue(repo.isRingsLoaded());
		assertTrue(repo.isIndexLoaded());
		assertEquals(4, repo.getRings().size());
		assertEquals(3, repo.dialable().size());
		assertEquals("Test Land", repo.surface().getName());
		// layers missing from the index fall back to the defaults
		assertNotNull(repo.layer("zanaris"));
		assertEquals("surface", repo.layerAt(2600, 3230).getId());
		assertEquals("hollows", repo.layerAt(2600, 9630).getId());
		assertNull(repo.layerAt(10, 10));
		assertEquals(1, repo.getPortals().size());
		assertEquals(Arrays.asList("Members only"), repo.getGlobalRequirements());
		assertEquals(2, repo.mappedIn("surface").size());
		assertEquals(1, repo.ringsIn("surface").size());
		assertFalse(repo.ring("DIQ").isMapped());
		assertNull(repo.ring("AIP"));
		assertEquals(2, repo.getIndex().getLabels().get(1).getLines().length);
		assertEquals(1, repo.getIndex().getIcons().size());
		assertEquals(1448, repo.getIndex().getIcons().get(0).getSprite());
		assertEquals(2601, repo.getIndex().getIcons().get(0).getX());
	}

	@Test
	public void missingResourcesStillWork()
	{
		RingRepository repo = RingRepository.load(new Gson(), "/nowhere/");
		assertFalse(repo.isRingsLoaded());
		assertFalse(repo.isIndexLoaded());
		assertTrue(repo.getRings().isEmpty());
		assertNotNull(repo.surface());
		assertEquals(12, repo.getLayers().size());
		TileStore tiles = new TileStore(repo.getIndex(), repo.getLayers(), "/nowhere/", Runnable::run);
		assertFalse(tiles.hasImagery());
	}

	@Test
	public void searchMatching()
	{
		RingRepository repo = RingRepository.load(new Gson(), FIXTURES);
		Ring aiq = repo.ring("AIQ");
		assertTrue(aiq.matches("aiq"));
		assertTrue(aiq.matches("a i q"));
		assertTrue(aiq.matches("MUDSKIP"));
		assertTrue(aiq.matches("sarim"));
		assertTrue(aiq.matches("skippy"));
		assertTrue(aiq.matches("asgarnia"));
		assertFalse(aiq.matches("zanaris"));
		assertEquals(1, repo.matching("myreque").size());
		assertTrue(repo.matching("").isEmpty());
	}

	@Test
	public void codePrefixesMatchOnlyCodes()
	{
		RingRepository repo = real();
		// up to three letters are a code: only CL* codes, nothing that merely contains "cl"
		List<String> cl = new ArrayList<>();
		for (Ring r : repo.matching("cl"))
		{
			cl.add(r.getCode());
		}
		java.util.Collections.sort(cl);
		assertEquals(Arrays.asList("CLP", "CLR", "CLS"), cl);
		assertTrue(repo.ring("CLR").matches("c l r"));
		assertFalse(repo.ring("BKS").matches("cl"));
		// short words are codes too; longer searches match words from their start
		assertFalse(repo.ring("BJS").matches("zul"));
		assertTrue(repo.ring("BJS").matches("zulrah"));
		assertFalse(repo.ring("BJS").matches("ulrah"));
		assertTrue(repo.ring("AIQ").matches("ice dung"));
	}

	@Test
	public void groupRowLabelsSearchLikeTheRingsWords()
	{
		RingRepository repo = real();
		Ring cks = repo.ring("CKS");
		// a row label's words count as the ring's words, from their start, only past three letters
		assertFalse(cks.matches("towr"));
		assertTrue(cks.matches("towr", "Towr Slayer"));
		assertTrue(cks.matches("slay tow", "Slayer Tower"));
		assertFalse(cks.matches("ower", "Slayer Tower"));
		assertFalse(cks.matches("sla", "Slayer Tower"));
		assertTrue(cks.matches("ck", "Slayer Tower"));
		assertFalse(cks.matches("zanaris dung", "Slayer Tower"));
		assertTrue(cks.matches("", "Slayer Tower"));
		assertEquals(cks.matches("canifis"), cks.matches("canifis", null));
	}

	@Test
	public void state()
	{
		RingRepository repo = RingRepository.load(new Gson(), FIXTURES);
		Map<String, Integer> faves = new java.util.HashMap<>();
		faves.put("DLS", 4);
		repo.setState(new HashSet<>(Arrays.asList("AIQ")), faves, "AIQ");
		assertTrue(repo.isVisited("AIQ"));
		assertFalse(repo.isVisited("DLS"));
		assertTrue(repo.hasLogRow("DLS"));
		assertEquals(4, repo.favouriteSlot("DLS"));
		assertEquals(-1, repo.favouriteSlot("AIQ"));
		assertEquals("AIQ", repo.getLastCode());
	}

	@Test
	public void teleportUncoveredOnlyWhenTheDialsAreWorthUsing()
	{
		Scene s = new Scene();
		s.repo = real();
		s.dials = new int[]{0, 0, 0};
		// AIP is an unused code: the game shows "Invalid location"
		assertFalse(s.teleportShown());
		s.dials = DialMath.values("CKS");
		assertTrue(s.teleportShown());
		// a selection hides Teleport until the dials show it, so the old code is not used by mistake
		s.selected = "AIQ";
		assertFalse(s.teleportShown());
		s.dials = DialMath.values("AIQ");
		assertTrue(s.teleportShown());
		s.dials = null;
		assertFalse(s.teleportShown());
	}

	@Test
	public void coveredTeleportSaysWhatAMapClickChanged()
	{
		Scene s = new Scene();
		s.repo = real();
		s.repo.setState(new HashSet<>(Arrays.asList("AIQ")), new java.util.HashMap<>(), null);
		s.dials = DialMath.values("AIP");
		assertArrayEquals(new String[]{"Teleport", "Pick a ring on the map"}, s.teleportStandIn());
		// a map click selects; the stand-in names the ring and the step that sets the dials
		s.selected = "AIQ";
		s.rowVisible = true;
		assertArrayEquals(new String[]{"Teleport to AIQ", "Click it in the travel log"}, s.teleportStandIn());
		s.rowVisible = false;
		assertArrayEquals(new String[]{"Teleport to AIQ", "Use it in the travel log first"}, s.teleportStandIn());
		s.query = "zul";
		assertArrayEquals(new String[]{"Teleport to AIQ", "Clear the log search first"}, s.teleportStandIn());
		s.selected = "CKS";
		assertArrayEquals(new String[]{"Teleport to CKS", "Locked: dial it by hand"}, s.teleportStandIn());
	}

	@Test
	public void openingAnAreaPicksItsFirstUsableRing()
	{
		RingRepository repo = real();
		repo.setState(new HashSet<>(), new java.util.HashMap<>(), null);
		assertEquals("BKS", repo.defaultRing("zanaris").getCode());
		// The Abyss has ALR and DIP: the first, unless only a later one is in the travel log
		assertEquals("ALR", repo.defaultRing("abyss").getCode());
		repo.setState(new HashSet<>(Arrays.asList("DIP")), new java.util.HashMap<>(), null);
		assertEquals("DIP", repo.defaultRing("abyss").getCode());
		assertNull(repo.defaultRing("no-such-layer"));
	}

	@Test
	public void houseRingSitsAtThePortal()
	{
		RingRepository repo = real();
		Ring diq = repo.ring("DIQ");
		repo.placeHouse(1);
		assertEquals("Rimmington", repo.getHouseTown());
		assertTrue(diq.isMapped());
		assertEquals(Layer.SURFACE, diq.getLayer());
		assertEquals(2953, diq.getX());
		assertEquals("Player-owned house (Rimmington)", repo.displayName(diq));
		// Prifddinas is not on the bundled map: the house card only
		repo.placeHouse(9);
		assertEquals("Prifddinas", repo.getHouseTown());
		assertFalse(diq.isMapped());
		assertEquals(Layer.POH, diq.getLayer());
		repo.placeHouse(0);
		assertNull(repo.getHouseTown());
		assertFalse(diq.isMapped());
	}

	@Test
	public void ringMenuCodes()
	{
		assertEquals("CIR", RingMenuNames.codeIn("CIR"));
		assertEquals("AJR", RingMenuNames.codeIn("Last-destination (AJR)"));
		assertEquals("AJR", RingMenuNames.codeIn("Ring-last-destination (AJR)"));
		assertEquals("CKS", RingMenuNames.codeIn("<col=ffffff>CKS</col>"));
		// renamed already (e.g. by Fairy Ring Favourites), or not a code
		assertNull(RingMenuNames.codeIn("Mount Karuulm"));
		assertNull(RingMenuNames.codeIn("Last: Mount Karuulm"));
		assertNull(RingMenuNames.codeIn("Configure"));
		assertNull(RingMenuNames.codeIn("AIZ"));
		assertTrue(RingMenuNames.isLast("Last-destination (AJR)"));
		assertFalse(RingMenuNames.isLast("AJR"));
		// once shortened to "Last (AJR)" it is not renamed again
		assertNull(RingMenuNames.codeIn("Last (AJR)"));
		assertTrue(RingMenuNames.isRing("<col=00ffff>Fairy ring</col>"));
		assertTrue(RingMenuNames.isRing("Spiritual Fairy Tree"));
		assertFalse(RingMenuNames.isRing("<col=00ffff>Mount Karuulm</col>"));
	}

	@Test
	public void favouritesListInBlockOrder()
	{
		RingRepository repo = real();
		Map<String, Integer> faves = new java.util.HashMap<>();
		faves.put("DLS", 2);
		faves.put("AIQ", 0);
		faves.put("BKS", 1);
		faves.put("ZZZ", 3);
		repo.setState(new HashSet<>(), faves, null);
		List<String> codes = new java.util.ArrayList<>();
		for (Ring r : repo.favouriteRings())
		{
			codes.add(r.getCode());
		}
		// unknown codes are left out
		assertEquals(Arrays.asList("AIQ", "BKS", "DLS"), codes);
	}

	@Test
	public void unlockConditionsNameRealQuestsAndVars() throws Exception
	{
		RingRepository repo = real();
		int rings = 0;
		for (Ring r : repo.dialable())
		{
			if (r.getUnlock().isEmpty())
			{
				continue;
			}
			rings++;
			assertFalse(r.getCode() + ": unlock conditions need requirement lines", r.getRequirements().isEmpty());
			for (Ring.Condition c : r.getUnlock())
			{
				String where = r.getCode() + " " + c.getLabel();
				assertNotNull(where, c.getLabel());
				assertTrue(where, Arrays.asList("quest", "varbit", "varp", "unknown").contains(c.getType()));
				if ("quest".equals(c.getType()))
				{
					// throws for a name that is not a Quest constant: the plugin would only show a hint
					net.runelite.api.Quest.valueOf(c.getQuest());
					assertTrue(where, Arrays.asList("FINISHED", "IN_PROGRESS").contains(c.getState()));
				}
				else if (!"unknown".equals(c.getType()))
				{
					assertTrue(where, Arrays.asList(">=", ">", "==").contains(c.getOp()));
				}
			}
		}
		assertTrue("unlock conditions missing from rings.json", rings >= 15);
		assertEquals(UnlockCheck.Status.UNKNOWN, repo.unlock(repo.ring("DIQ")).getStatus());

		// tools/data/unlock.json names the gameval constant of each id; check they agree
		File src = new File("tools/data/unlock.json");
		assertTrue("run from the project directory", src.isFile());
		Map<?, ?> doc = new Gson().fromJson(new String(java.nio.file.Files.readAllBytes(src.toPath()),
			java.nio.charset.StandardCharsets.UTF_8), Map.class);
		int checked = 0;
		for (Object entry : ((Map<?, ?>) doc.get("rings")).values())
		{
			for (Object o : (List<?>) ((Map<?, ?>) entry).get("all"))
			{
				Map<?, ?> c = (Map<?, ?>) o;
				Object name = c.get("name");
				if (name == null)
				{
					continue;
				}
				String[] parts = name.toString().split("[.]");
				Class<?> owner = "VarbitID".equals(parts[0]) ? net.runelite.api.gameval.VarbitID.class
					: "VarPlayerID".equals(parts[0]) ? net.runelite.api.gameval.VarPlayerID.class : null;
				assertNotNull(name.toString(), owner);
				assertEquals(name.toString(), ((Number) c.get("id")).intValue(), owner.getField(parts[1]).getInt(null));
				checked++;
			}
		}
		assertTrue(checked >= 10);
	}
}
