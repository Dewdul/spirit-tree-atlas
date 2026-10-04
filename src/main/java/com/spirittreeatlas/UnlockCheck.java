/*
 * Copyright (c) 2026, Dewdul
 * All rights reserved.
 * Licensed under the BSD 2-Clause License. See LICENSE.
 */
package com.spirittreeatlas;

import java.util.List;
import lombok.Value;
import net.runelite.api.QuestState;

/**
 * Whether a locked ring can be unlocked yet: its rings.json "unlock" conditions checked against
 * quest states and vars. Pure, so tests run it with a fake {@link Vars}; the plugin backs that
 * with the client while the dials are open.
 */
public final class UnlockCheck
{
	public enum Status
	{
		/** Everything known holds: a first visit unlocks the ring. */
		MET,
		/** What can be checked holds, but something the client cannot see may still be missing. */
		UNKNOWN,
		/** A condition does not hold yet: a visit cannot unlock the ring. */
		NOT_MET,
	}

	/** Var and quest reads; the client-backed one is used on the client thread only. */
	public interface Vars
	{
		int varbit(int id);

		int varp(int id);

		/** The quest's state, or null when the name is not a known quest. */
		QuestState quest(String name);
	}

	@Value
	public static class Result
	{
		Status status;
		/** NOT_MET: the first unmet condition; UNKNOWN: the first unchecked one; MET: null. */
		String label;
	}

	static final Result MET = new Result(Status.MET, null);

	private UnlockCheck()
	{
	}

	/** The first unmet condition wins; otherwise the first one that could not be checked. */
	public static Result evaluate(List<Ring.Condition> conditions, Vars vars)
	{
		String unknown = null;
		for (Ring.Condition c : conditions)
		{
			Boolean ok = holds(c, vars);
			if (ok == null)
			{
				if (unknown == null)
				{
					unknown = c.getLabel();
				}
			}
			else if (!ok)
			{
				return new Result(Status.NOT_MET, c.getLabel());
			}
		}
		return unknown == null ? MET : new Result(Status.UNKNOWN, unknown);
	}

	/** Before the vars have been read: only a hint, never a lock. */
	public static Result unchecked(List<Ring.Condition> conditions)
	{
		return conditions.isEmpty() ? MET : new Result(Status.UNKNOWN, conditions.get(0).getLabel());
	}

	/** True or false, or null when it cannot be told (an "unknown" need, an unknown quest or op). */
	static Boolean holds(Ring.Condition c, Vars vars)
	{
		String type = c.getType() == null ? "" : c.getType();
		switch (type)
		{
			case "quest":
				QuestState q = c.getQuest() == null ? null : vars.quest(c.getQuest());
				if (q == null)
				{
					return null;
				}
				if ("FINISHED".equals(c.getState()))
				{
					return q == QuestState.FINISHED;
				}
				return "IN_PROGRESS".equals(c.getState()) ? q != QuestState.NOT_STARTED : null;
			case "varbit":
				return compare(vars.varbit(c.getId()), c.getOp(), c.getValue());
			case "varp":
				return compare(vars.varp(c.getId()), c.getOp(), c.getValue());
			default:
				return null;
		}
	}

	static Boolean compare(int v, String op, int value)
	{
		if (op == null)
		{
			return null;
		}
		switch (op)
		{
			case ">=":
				return v >= value;
			case ">":
				return v > value;
			case "==":
				return v == value;
			default:
				return null;
		}
	}
}
