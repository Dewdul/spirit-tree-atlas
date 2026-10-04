/*
 * Copyright (c) 2026, Dewdul
 * All rights reserved.
 * Licensed under the BSD 2-Clause License. See LICENSE.
 */
package com.fairyringatlas;

import com.google.gson.Gson;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import net.runelite.api.QuestState;
import net.runelite.api.gameval.VarbitID;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/** The unlock check against a fake set of vars and quest states, and what the map makes of it. */
public class UnlockCheckTest
{
	/** Vars and quests as a test sets them; anything unset reads 0 / not started. */
	static final class FakeVars implements UnlockCheck.Vars
	{
		final Map<Integer, Integer> varbits = new HashMap<>();
		final Map<Integer, Integer> varps = new HashMap<>();
		final Map<String, QuestState> quests = new HashMap<>();

		FakeVars varbit(int id, int v)
		{
			varbits.put(id, v);
			return this;
		}

		FakeVars varp(int id, int v)
		{
			varps.put(id, v);
			return this;
		}

		FakeVars quest(String name, QuestState s)
		{
			quests.put(name, s);
			return this;
		}

		@Override
		public int varbit(int id)
		{
			return varbits.getOrDefault(id, 0);
		}

		@Override
		public int varp(int id)
		{
			return varps.getOrDefault(id, 0);
		}

		@Override
		public QuestState quest(String name)
		{
			// like the client adapter: a name that is not a Quest constant has no state
			try
			{
				net.runelite.api.Quest.valueOf(name);
			}
			catch (IllegalArgumentException e)
			{
				return null;
			}
			return quests.getOrDefault(name, QuestState.NOT_STARTED);
		}
	}

	private static Ring.Condition quest(String name, String state, String label)
	{
		return new Ring.Condition("quest", name, state, 0, null, 0, label);
	}

	private static Ring.Condition varbit(int id, String op, int value, String label)
	{
		return new Ring.Condition("varbit", null, null, id, op, value, label);
	}

	private static Ring.Condition varp(int id, String op, int value, String label)
	{
		return new Ring.Condition("varp", null, null, id, op, value, label);
	}

	private static Ring.Condition unknown(String label)
	{
		return new Ring.Condition("unknown", null, null, 0, null, 0, label);
	}

	private static UnlockCheck.Result eval(FakeVars v, Ring.Condition... cs)
	{
		return UnlockCheck.evaluate(Arrays.asList(cs), v);
	}

	@Test
	public void noConditionsMeansAVisitUnlocksIt()
	{
		UnlockCheck.Result r = UnlockCheck.evaluate(Collections.emptyList(), new FakeVars());
		assertEquals(UnlockCheck.Status.MET, r.getStatus());
		assertNull(r.getLabel());
		assertEquals(UnlockCheck.Status.MET, UnlockCheck.unchecked(Collections.emptyList()).getStatus());
	}

	@Test
	public void questStates()
	{
		Ring.Condition done = quest("REGICIDE", "FINISHED", "Regicide");
		Ring.Condition started = quest("REGICIDE", "IN_PROGRESS", "Regicide started");
		FakeVars v = new FakeVars();
		assertEquals(new UnlockCheck.Result(UnlockCheck.Status.NOT_MET, "Regicide"), eval(v, done));
		assertEquals(UnlockCheck.Status.NOT_MET, eval(v, started).getStatus());
		v.quest("REGICIDE", QuestState.IN_PROGRESS);
		assertEquals(UnlockCheck.Status.NOT_MET, eval(v, done).getStatus());
		assertEquals(UnlockCheck.Status.MET, eval(v, started).getStatus());
		v.quest("REGICIDE", QuestState.FINISHED);
		assertEquals(UnlockCheck.Status.MET, eval(v, done).getStatus());
	}

	@Test
	public void anUnreadableQuestIsAHintNotALock()
	{
		UnlockCheck.Result r = eval(new FakeVars(), quest("NO_SUCH_QUEST", "FINISHED", "Some quest"));
		assertEquals(new UnlockCheck.Result(UnlockCheck.Status.UNKNOWN, "Some quest"), r);
		assertEquals(UnlockCheck.Status.UNKNOWN, eval(new FakeVars().quest("REGICIDE", QuestState.FINISHED),
			quest("REGICIDE", "STARTED?", "odd state")).getStatus());
	}

	@Test
	public void varComparisons()
	{
		FakeVars v = new FakeVars().varbit(13841, 12).varp(5, 9);
		assertEquals(UnlockCheck.Status.NOT_MET, eval(v, varbit(13841, ">=", 14, "BCS")).getStatus());
		assertEquals(UnlockCheck.Status.MET, eval(v, varbit(13841, ">=", 12, "BCS")).getStatus());
		assertEquals(UnlockCheck.Status.NOT_MET, eval(v, varbit(13841, ">", 12, "BCS")).getStatus());
		assertEquals(UnlockCheck.Status.MET, eval(v, varbit(13841, "==", 12, "BCS")).getStatus());
		assertEquals(UnlockCheck.Status.MET, eval(v, varp(5, ">=", 9, "Grail")).getStatus());
		assertEquals(UnlockCheck.Status.NOT_MET, eval(v, varp(5, ">=", 10, "Grail")).getStatus());
		// a varp condition reads the varp, not a varbit with the same id
		assertEquals(UnlockCheck.Status.NOT_MET, eval(v, varbit(5, ">=", 9, "Grail")).getStatus());
		assertEquals(UnlockCheck.Status.UNKNOWN, eval(v, varbit(13841, "<", 99, "odd op")).getStatus());
	}

	@Test
	public void theFirstUnmetConditionIsNamedAndBeatsAnUnknownOne()
	{
		Ring.Condition cots = quest("CHILDREN_OF_THE_SUN", "FINISHED", "Children of the Sun");
		Ring.Condition ride = varbit(9650, ">=", 1, "a Regulus Cento ride to Civitas");
		Ring.Condition daero = unknown("Daero's training");
		FakeVars v = new FakeVars();
		assertEquals("Children of the Sun", eval(v, cots, ride).getLabel());
		v.quest("CHILDREN_OF_THE_SUN", QuestState.FINISHED);
		assertEquals(new UnlockCheck.Result(UnlockCheck.Status.NOT_MET, "a Regulus Cento ride to Civitas"), eval(v, cots, ride));
		// an unmet condition after an unknown one still locks
		assertEquals(UnlockCheck.Status.NOT_MET, eval(v, daero, ride).getStatus());
		v.varbit(9650, 1);
		assertEquals(UnlockCheck.Status.MET, eval(v, cots, ride).getStatus());
		assertEquals(new UnlockCheck.Result(UnlockCheck.Status.UNKNOWN, "Daero's training"), eval(v, cots, daero, ride));
	}

	@Test
	public void uncheckedIsOnlyAHint()
	{
		List<Ring.Condition> cs = Arrays.asList(quest("REGICIDE", "FINISHED", "Regicide"), unknown("x"));
		assertEquals(new UnlockCheck.Result(UnlockCheck.Status.UNKNOWN, "Regicide"), UnlockCheck.unchecked(cs));
	}

	@Test
	public void theRepositoryChecksEveryRingAndWatchesItsVars()
	{
		RingRepository repo = RingRepository.load(new Gson(), FairyRingAtlasPlugin.RESOURCES);
		repo.setState(new HashSet<>(), new HashMap<>(), null);
		Ring akp = repo.ring("AKP");
		Ring bjr = repo.ring("BJR");
		Ring aiq = repo.ring("AIQ");
		// before a check: a hint, and the dials are still offered
		assertEquals(UnlockCheck.Status.UNKNOWN, repo.unlock(akp).getStatus());
		assertEquals(UnlockCheck.Status.MET, repo.unlock(aiq).getStatus());
		assertFalse(repo.watchesUnlock(13841, 0));

		FakeVars v = new FakeVars();
		int hash = repo.getStateHash();
		repo.checkUnlocks(v);
		assertTrue("a check changes the cache key", hash != repo.getStateHash());
		assertEquals(UnlockCheck.Status.NOT_MET, repo.unlock(akp).getStatus());
		assertEquals(UnlockCheck.Status.NOT_MET, repo.unlock(bjr).getStatus());
		assertEquals(UnlockCheck.Status.MET, repo.unlock(aiq).getStatus());
		assertTrue(repo.watchesUnlock(13841, 1234));
		assertTrue("Holy Grail is a whole varp", repo.watchesUnlock(-1, 5));
		assertFalse(repo.watchesUnlock(-1, 13841));
		assertFalse(repo.watchesUnlock(VarbitID.FAIRYRING_1, 0));

		repo.checkUnlocks(v.varbit(13841, 14).varp(5, 9));
		assertEquals(UnlockCheck.Status.MET, repo.unlock(akp).getStatus());
		assertEquals(UnlockCheck.Status.MET, repo.unlock(bjr).getStatus());
		// the house ring: Fairytale II done, but only the house shows whether its ring is built
		v.quest("FAIRYTALE_II__CURE_A_QUEEN", QuestState.FINISHED);
		repo.checkUnlocks(v);
		assertEquals(new UnlockCheck.Result(UnlockCheck.Status.UNKNOWN, "a fairy ring in your house"), repo.unlock(repo.ring("DIQ")));
	}

	@Test
	public void aRingThatNeedsSomethingFirstIsNotOfferedForDialling()
	{
		Scene s = new Scene();
		s.repo = RingRepository.load(new Gson(), FairyRingAtlasPlugin.RESOURCES);
		s.repo.setState(new HashSet<>(Arrays.asList("AIQ")), new HashMap<>(), null);
		s.dials = DialMath.values("AIQ");
		s.selected = "AKP";
		FakeVars v = new FakeVars().varbit(13841, 5);
		s.repo.checkUnlocks(v);
		assertFalse(s.needsDialing());
		assertTrue(s.unlockBlocked());
		assertArrayEquals(new String[]{"AKP is locked", "Needs Beneath Cursed Sands progress"}, s.teleportStandIn());

		// once it is met, a first visit unlocks it: dial it by hand
		s.repo.checkUnlocks(v.varbit(13841, 14));
		assertTrue(s.needsDialing());
		assertFalse(s.unlockBlocked());
		assertArrayEquals(new String[]{"Teleport to AKP", "Locked: dial it by hand"}, s.teleportStandIn());

		// a need the client cannot check never hides the dials
		s.selected = "CLR";
		s.repo.checkUnlocks(v.quest("MONKEY_MADNESS_I", QuestState.FINISHED));
		assertEquals(UnlockCheck.Status.UNKNOWN, s.repo.unlock(s.repo.ring("CLR")).getStatus());
		assertTrue(s.needsDialing());

		// dialled anyway: Teleport is the next step, not the lock
		s.selected = "AKP";
		s.repo.checkUnlocks(v.varbit(13841, 0));
		s.dials = DialMath.values("AKP");
		assertFalse(s.unlockBlocked());
		assertTrue(s.teleportShown());
	}
}
