/*
 * Copyright (c) 2026, Dewdul
 * All rights reserved.
 * Licensed under the BSD 2-Clause License. See LICENSE.
 */
package com.spirittreeatlas;

/**
 * Fairy ring dial arithmetic. The dial varbits count in the order the letters pass the pointer,
 * which is not alphabetical: dial 1 is A,D,C,B; dial 2 is I,L,K,J; dial 3 is P,S,R,Q. A clockwise
 * click adds one (mod 4) and an anticlockwise click adds three.
 */
public final class DialMath
{
	static final String[] DIALS = {"ADCB", "ILKJ", "PSRQ"};

	private DialMath()
	{
	}

	public static boolean isCode(String code)
	{
		return code != null && code.length() == 3
			&& DIALS[0].indexOf(code.charAt(0)) >= 0
			&& DIALS[1].indexOf(code.charAt(1)) >= 0
			&& DIALS[2].indexOf(code.charAt(2)) >= 0;
	}

	/** Normalises "a i q", "A I Q" or "aiq" to "AIQ"; null if it is not a code. */
	public static String normalize(String text)
	{
		if (text == null)
		{
			return null;
		}
		StringBuilder sb = new StringBuilder(3);
		for (int i = 0; i < text.length(); i++)
		{
			char c = text.charAt(i);
			if (c != ' ')
			{
				sb.append(Character.toUpperCase(c));
			}
		}
		String code = sb.toString();
		return isCode(code) ? code : null;
	}

	/** "AIQ" to "A I Q", the way the game writes codes. */
	public static String spaced(String code)
	{
		return code.charAt(0) + " " + code.charAt(1) + " " + code.charAt(2);
	}

	/** The dial value (0..3) that shows a letter on a dial (0..2), or -1. */
	public static int value(int dial, char letter)
	{
		return DIALS[dial].indexOf(Character.toUpperCase(letter));
	}

	public static char letter(int dial, int value)
	{
		return DIALS[dial].charAt(value & 3);
	}

	/** The three dial values of a code. */
	public static int[] values(String code)
	{
		return new int[]{value(0, code.charAt(0)), value(1, code.charAt(1)), value(2, code.charAt(2))};
	}

	public static String code(int d1, int d2, int d3)
	{
		return "" + letter(0, d1) + letter(1, d2) + letter(2, d3);
	}

	/** The multiloc index 16*d1 + 4*d2 + d3 used by FAIRYRING_LASTLOC and the DB table. */
	public static int index(String code)
	{
		int[] v = values(code);
		return v[0] * 16 + v[1] * 4 + v[2];
	}

	public static String fromIndex(int index)
	{
		return code((index >> 4) & 3, (index >> 2) & 3, index & 3);
	}

	/**
	 * Decodes a FAIRYRING_FAVE_n varbit: set bit 6, then d1, d2, d3 two bits each. Returns null
	 * when the slot is empty.
	 */
	public static String fromFavourite(int varbit)
	{
		return (varbit & 64) == 0 ? null : fromIndex(varbit & 63);
	}

	/**
	 * Clockwise steps from the current dial value to the target, 0..3. 0 means the dial is
	 * right; 1 is one clockwise click; 2 is two clicks either way (shown as clockwise); 3 is one
	 * anticlockwise click.
	 */
	public static int steps(int current, int target)
	{
		return ((target - current) % 4 + 4) % 4;
	}

	/** Number of clicks for a step count: 0, 1, 2 or 1. */
	public static int clicks(int steps)
	{
		return steps == 3 ? 1 : steps;
	}

	/** Whether the clicks for a step count go clockwise (false for anticlockwise). */
	public static boolean clockwise(int steps)
	{
		return steps != 3;
	}

	/** Steps per dial to turn the current dials into a code. */
	public static int[] plan(int[] current, String target)
	{
		int[] t = values(target);
		return new int[]{steps(current[0], t[0]), steps(current[1], t[1]), steps(current[2], t[2])};
	}

	/** Total clicks of a plan. */
	public static int totalClicks(int[] plan)
	{
		return clicks(plan[0]) + clicks(plan[1]) + clicks(plan[2]);
	}
}
