/*
 * Copyright (c) 2026, Dewdul
 * All rights reserved.
 * Licensed under the BSD 2-Clause License. See LICENSE.
 */
package com.fairyringatlas;

import com.google.gson.Gson;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class RingGroupsTest
{
	private static final Gson GSON = new Gson();

	/** The test fixture: Slayer AJR, CKS, DKS and Farming CIR, AIQ (with a bad and a repeated code). */
	private static List<RingGroups.Def> defaults()
	{
		try (Reader r = new InputStreamReader(RingGroupsTest.class.getResourceAsStream("/fixtures/groups.json"), StandardCharsets.UTF_8))
		{
			return RingGroups.defaults(GSON.fromJson(r, RingGroups.DefFile.class));
		}
		catch (java.io.IOException e)
		{
			throw new AssertionError(e);
		}
	}

	private static RingGroups fresh()
	{
		return RingGroups.parse(GSON, defaults(), null);
	}

	private static RingGroups.Group group(RingGroups g, String id)
	{
		for (RingGroups.Group x : g.view())
		{
			if (x.getId().equals(id))
			{
				return x;
			}
		}
		return null;
	}

	/** Saved and read back, as the plugin does between sessions. */
	private static RingGroups roundTrip(RingGroups g)
	{
		return RingGroups.parse(GSON, defaults(), g.format(GSON));
	}

	@Test
	public void defaultsAreCleanedAndSeeded()
	{
		RingGroups g = fresh();
		assertEquals(2, g.view().size());
		RingGroups.Group slayer = group(g, "slayer");
		assertEquals("Slayer", slayer.getName());
		assertEquals(Arrays.asList("AJR", "CKS", "DKS"), slayer.getCodes());
		assertTrue(slayer.isPrebuilt());
		assertFalse(slayer.isModified());
		assertEquals(Arrays.asList("CIR", "AIQ"), group(g, "farming").getCodes());
		assertEquals("Fremennik Slayer Dungeon", g.note("slayer", "AJR"));
		assertNull(g.note("slayer", "DKS"));
		assertEquals("Monsters: kurasks", g.details("slayer", "AJR"));
		assertNull(g.details("slayer", "DKS"));
		assertNull(g.details("slayer", null));
		assertNull(g.details("nope", "AJR"));
		// nothing bundled or saved: no groups, and no crash
		assertTrue(RingGroups.parse(GSON, RingGroups.defaults(null), null).view().isEmpty());
	}

	@Test
	public void editAPrebuiltGroupAndResetIt()
	{
		RingGroups g = fresh();
		assertTrue(g.add("slayer", "cip"));
		assertFalse(g.add("slayer", "CIP"));
		assertTrue(g.remove("slayer", "AJR"));
		assertTrue(g.move("slayer", "CIP", "AJR"));
		assertTrue(g.move("slayer", "CIP", "CKS"));
		assertTrue(g.rename("slayer", "  Tasks <col=ff0000> "));
		g = roundTrip(g);
		RingGroups.Group s = group(g, "slayer");
		assertEquals("Tasks col=ff0000", s.getName());
		assertEquals(Arrays.asList("CIP", "CKS", "DKS"), s.getCodes());
		assertTrue(s.isModified());
		// the bundled hints stay with the codes
		assertEquals("Kalphites", g.note("slayer", "CKS"));
		assertTrue(g.reset("slayer"));
		assertEquals(Arrays.asList("AJR", "CKS", "DKS"), group(g, "slayer").getCodes());
		assertEquals("Slayer", group(g, "slayer").getName());
		assertFalse(group(g, "slayer").isModified());
	}

	@Test
	public void undoingAnEditFollowsTheDefaultAgain()
	{
		RingGroups g = fresh();
		g.remove("farming", "AIQ");
		g.add("farming", "AIQ");
		assertFalse(group(g, "farming").isModified());
		g.rename("farming", "Farming");
		assertFalse(group(roundTrip(g), "farming").isModified());
	}

	@Test
	public void ownGroups()
	{
		RingGroups g = fresh();
		String id = g.create("Bossing", "akq");
		assertNotNull(id);
		assertNull(g.create("   ", "AKQ"));
		g.add(id, "DJR");
		g = roundTrip(g);
		RingGroups.Group own = group(g, id);
		assertEquals("Bossing", own.getName());
		assertEquals(Arrays.asList("AKQ", "DJR"), own.getCodes());
		assertFalse(own.isPrebuilt());
		assertFalse(g.reset(id));
		assertEquals(3, g.view().size());
		// ids are never reused while the group exists
		assertFalse(id.equals(g.create("Other", null)));
		assertTrue(g.contains(id, "DJR"));
		assertFalse(g.contains(id, "CIR"));
	}

	@Test
	public void deletedPrebuiltGroupsStayDeletedUntilRestored()
	{
		RingGroups g = fresh();
		assertTrue(g.delete("slayer"));
		g = roundTrip(g);
		assertNull(group(g, "slayer"));
		assertEquals(1, g.deleted().size());
		assertTrue(g.restore("slayer"));
		assertEquals(Arrays.asList("farming", "slayer"), Arrays.asList(g.view().get(0).getId(), g.view().get(1).getId()));
		assertTrue(g.deleted().isEmpty());
	}

	@Test
	public void aPrebuiltGroupAddedByAnUpdateJoinsAtTheEnd()
	{
		// saved before "farming" was bundled
		String old = "{\"groups\":[{\"id\":\"slayer\"},{\"id\":\"u1\",\"name\":\"Mine\",\"codes\":[\"BKS\",\"bad\",\"BKS\"]}],\"seen\":[\"slayer\"]}";
		RingGroups g = RingGroups.parse(GSON, defaults(), old);
		List<RingGroups.Group> v = g.view();
		assertEquals(Arrays.asList("slayer", "u1", "farming"), Arrays.asList(v.get(0).getId(), v.get(1).getId(), v.get(2).getId()));
		assertEquals(Collections.singletonList("BKS"), v.get(1).getCodes());
	}

	@Test
	public void movesAnchorOnTheSavedCodes()
	{
		RingGroups g = fresh();
		// a code the map does not know stays in the list, and the moves go around it
		String id = g.create("Mine", "AKQ");
		g.add(id, "ZZZ");
		g.add(id, "DJR");
		assertTrue(g.move(id, "DJR", "ZZZ"));
		assertEquals(Arrays.asList("AKQ", "DJR"), group(g, id).getCodes().subList(0, 2));
		assertTrue(g.move(id, "AKQ", null));
		assertEquals("AKQ", group(g, id).getCodes().get(group(g, id).getCodes().size() - 1));
		assertFalse(g.move(id, "CIR", null));
	}

	@Test
	public void theCapHoldsForNewAndRestoredGroups()
	{
		RingGroups g = fresh();
		g.delete("slayer");
		g.delete("farming");
		for (int i = 0; i < RingGroups.GROUPS_MAX; i++)
		{
			assertNotNull(g.create("G" + i, null));
		}
		assertTrue(g.isFull());
		assertNull(g.create("One more", null));
		assertFalse(g.restore("slayer"));
		g = roundTrip(g);
		assertEquals(RingGroups.GROUPS_MAX, g.view().size());
		// room again: the deleted prebuilt group comes back, and stays after a reload
		g.delete(g.view().get(0).getId());
		assertTrue(g.restore("slayer"));
		assertNotNull(group(roundTrip(g), "slayer"));
	}

	@Test
	public void aPrebuiltGroupNewInAnUpdateWaitsForRoom()
	{
		StringBuilder sb = new StringBuilder("{\"groups\":[{\"id\":\"slayer\"}");
		for (int i = 1; i < RingGroups.GROUPS_MAX; i++)
		{
			sb.append(",{\"id\":\"u").append(i).append("\",\"name\":\"G\",\"codes\":[]}");
		}
		String full = sb.append("],\"seen\":[\"slayer\"]}").toString();
		RingGroups g = RingGroups.parse(GSON, defaults(), full);
		assertNull(group(g, "farming"));
		g.delete("u1");
		assertNotNull(group(RingGroups.parse(GSON, defaults(), g.format(GSON)), "farming"));
	}

	@Test
	public void aBadSeenListStillSaves()
	{
		RingGroups g = RingGroups.parse(GSON, defaults(), "{\"groups\":[{\"id\":\"slayer\"}],\"seen\":[null,\"\",\"slayer\"]}");
		assertTrue(g.add("slayer", "CIP"));
		// farming was never seen, so it joins; nothing null is written back
		assertEquals(2, roundTrip(g).view().size());
		assertFalse(g.format(GSON).contains("null"));
	}

	@Test
	public void collapsedPersists()
	{
		RingGroups g = fresh();
		g.toggleCollapsed("farming");
		assertTrue(group(roundTrip(g), "farming").isCollapsed());
		g.toggleCollapsed("farming");
		assertFalse(group(roundTrip(g), "farming").isCollapsed());
	}

	@Test
	public void prebuiltRowsShowTheirBundledLabels()
	{
		RingGroups.Group s = group(fresh(), "slayer");
		assertEquals("Slayer cave", s.getLabels().get("AJR"));
		// cleaned like a name
		assertEquals("Kalphite Lair", s.getLabels().get("CKS"));
		// none bundled: the row shows the ring's name
		assertNull(s.getLabels().get("DKS"));
		assertTrue(s.getRenamed().isEmpty());
		assertEquals("Farming Guild", group(fresh(), "farming").getLabels().get("CIR"));
	}

	@Test
	public void renameAPrebuiltRowAndResetIt()
	{
		RingGroups g = fresh();
		assertTrue(g.renameRow("slayer", "AJR", "  Kurasks <col> ", "Fremennik Slayer Dungeon"));
		assertFalse(g.renameRow("slayer", "AJR", "Kurasks col", "Fremennik Slayer Dungeon"));
		// not in the group, or nothing left of the name
		assertFalse(g.renameRow("slayer", "CIR", "Konar", "Mount Karuulm"));
		assertFalse(g.renameRow("slayer", "CKS", " <> ", "Canifis"));
		g = roundTrip(g);
		RingGroups.Group s = group(g, "slayer");
		assertEquals("Kurasks col", s.getLabels().get("AJR"));
		assertEquals(Collections.singleton("AJR"), s.getRenamed());
		assertTrue(s.isModified());
		// only the difference is saved: the other rows still follow the bundled labels
		assertFalse(g.format(GSON).contains("Kalphite"));
		assertTrue(g.resetRow("slayer", "AJR"));
		assertFalse(g.resetRow("slayer", "AJR"));
		s = group(g, "slayer");
		assertEquals("Slayer cave", s.getLabels().get("AJR"));
		assertFalse(s.isModified());
		// a name typed as bundled is the default again, as is Reset to default
		g.renameRow("slayer", "CKS", "Kalphite Lair", "Canifis");
		assertFalse(group(g, "slayer").isModified());
		g.renameRow("slayer", "DKS", "Brine rats", "Mudskipper Point");
		g.renameRow("slayer", "AJR", "Kurasks", "Fremennik Slayer Dungeon");
		assertTrue(g.reset("slayer"));
		assertTrue(group(g, "slayer").getRenamed().isEmpty());
		assertNull(group(g, "slayer").getLabels().get("DKS"));
	}

	@Test
	public void ownRowsStartWithTheRingName()
	{
		RingGroups g = fresh();
		String id = g.create("Bossing", "AKQ");
		assertTrue(group(g, id).getLabels().isEmpty());
		assertTrue(g.renameRow(id, "AKQ", "Kraken", "Kraken Cove"));
		g = roundTrip(g);
		assertEquals("Kraken", group(g, id).getLabels().get("AKQ"));
		// typed back to the ring's name: nothing to reset
		assertTrue(g.renameRow(id, "AKQ", "Kraken Cove", "Kraken Cove"));
		assertTrue(group(g, id).getRenamed().isEmpty());
		assertFalse(g.format(GSON).contains("labels"));
	}

	@Test
	public void aRemovedRowForgetsItsName()
	{
		RingGroups g = fresh();
		g.renameRow("slayer", "DKS", "Brine rats", "Mudskipper Point");
		g.move("slayer", "DKS", "AJR");
		assertEquals("Brine rats", group(g, "slayer").getLabels().get("DKS"));
		assertTrue(g.remove("slayer", "DKS"));
		assertTrue(g.add("slayer", "DKS"));
		assertNull(group(roundTrip(g), "slayer").getLabels().get("DKS"));
		// saved names for codes no longer in the group, or not codes at all, are dropped on load
		String saved = "{\"groups\":[{\"id\":\"slayer\",\"labels\":{\"cks\":\"Kalphites!\",\"CIR\":\"Konar\",\"bad\":\"x\",\"AJR\":\"Slayer cave\"}}],"
			+ "\"seen\":[\"slayer\",\"farming\"]}";
		RingGroups.Group s = group(RingGroups.parse(GSON, defaults(), saved), "slayer");
		assertEquals(Collections.singleton("CKS"), s.getRenamed());
		assertEquals("Kalphites!", s.getLabels().get("CKS"));
	}

	@Test
	public void groupsSavedBeforeRowLabelsGetTheBundledOnes()
	{
		// the saved form from before row labels: a reordered prebuilt group and one of the player's own
		String saved = "{\"groups\":[{\"id\":\"slayer\",\"codes\":[\"CKS\",\"AJR\",\"DKS\"]},"
			+ "{\"id\":\"u1\",\"name\":\"Bossing\",\"codes\":[\"AKQ\"]}],\"seen\":[\"farming\",\"slayer\"]}";
		RingGroups g = RingGroups.parse(GSON, defaults(), saved);
		RingGroups.Group s = group(g, "slayer");
		assertEquals(Arrays.asList("CKS", "AJR", "DKS"), s.getCodes());
		assertEquals("Slayer cave", s.getLabels().get("AJR"));
		assertEquals("Kalphite Lair", s.getLabels().get("CKS"));
		assertTrue(s.getRenamed().isEmpty());
		assertTrue(s.isModified());
		assertTrue(group(g, "u1").getLabels().isEmpty());
		// the farming group, deleted then, stays deleted
		assertNull(group(g, "farming"));
		// and saving it again adds nothing for the labels
		assertFalse(g.format(GSON).contains("labels"));
		assertTrue(g.reset("slayer"));
		assertFalse(group(g, "slayer").isModified());
	}

	@Test
	public void unreadableSavedStateFallsBackToDefaults()
	{
		assertEquals(2, RingGroups.parse(GSON, defaults(), "{not json").view().size());
		assertEquals(2, RingGroups.parse(GSON, defaults(), "").view().size());
	}

	@Test
	public void bundledGroupsAreDialableRings()
	{
		RingGroups.DefFile file = RingRepository.read(GSON, FairyRingAtlasPlugin.RESOURCES + "groups.json", RingGroups.DefFile.class);
		assertNotNull("groups.json is bundled", file);
		RingRepository repo = RingRepository.load(GSON, FairyRingAtlasPlugin.RESOURCES);
		int codes = 0;
		for (RingGroups.Def d : file.groups)
		{
			assertNotNull(d.id, RingGroups.cleanName(d.name));
			for (RingGroups.Code c : d.codes)
			{
				Ring r = repo.ring(c.code);
				assertTrue(d.id + " " + c.code, r != null && r.isDialable() && c.code.equals(DialMath.normalize(c.code)));
				assertTrue(d.id + " " + c.code + " note", c.note == null || (c.note.length() <= 40 && c.note.matches("[ -~]*")));
				// what is there, in at most two card lines: printable ASCII, and nothing every patch has
				assertTrue(d.id + " " + c.code + " details", c.details == null || (c.details.length() <= 90
					&& c.details.matches("[ -~]*") && c.details.equals(c.details.trim())));
				if (c.details != null)
				{
					String lower = c.details.toLowerCase();
					assertFalse(d.id + " " + c.code + " details", lower.contains("compost") || lower.contains("leprechaun"));
					assertTrue(d.id + " " + c.code + " details", "farming".equals(d.id) ? c.details.startsWith("Patches: ")
						: c.details.startsWith("Monsters: ") || c.details.startsWith("Master"));
				}
				// every prebuilt row says what it is for, in a name the loader keeps as it is
				assertNotNull(d.id + " " + c.code + " label", c.label);
				assertTrue(d.id + " " + c.code + " label", c.label.length() <= RingGroups.NAME_MAX && c.label.matches("[ -~]*")
					&& c.label.equals(RingGroups.cleanName(c.label)));
				codes++;
			}
			// nothing the loader would drop
			assertEquals(d.codes.size(), RingGroups.defaults(file).get(file.groups.indexOf(d)).codes.size());
		}
		assertTrue(codes > 0);
	}

	@Test
	public void namesAreCleaned()
	{
		assertEquals("abcdefghijklmnopqrstuvwx", RingGroups.cleanName("abcdefghijklmnopqrstuvwxyz"));
		assertEquals("a b", RingGroups.cleanName(" a   b "));
		assertNull(RingGroups.cleanName("<>"));
	}
}
