/*
 * Copyright (c) 2026, Dewdul
 * All rights reserved.
 * Licensed under the BSD 2-Clause License. See LICENSE.
 */
package com.spirittreeatlas;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The player's own order for the side panel's Favourites list, set by dragging rows. It only
 * changes how the map lists favourites; the game's favourites are left as they are.
 */
final class FavouriteOrder
{
	private FavouriteOrder()
	{
	}

	/** Favourites in the saved order; ones the saved order does not know follow in game order. */
	static List<Ring> sort(List<Ring> gameOrder, List<String> saved)
	{
		List<Ring> out = new ArrayList<>(gameOrder.size());
		for (String code : saved)
		{
			for (Ring r : gameOrder)
			{
				if (code.equals(r.getCode()) && !out.contains(r))
				{
					out.add(r);
				}
			}
		}
		for (Ring r : gameOrder)
		{
			if (!out.contains(r))
			{
				out.add(r);
			}
		}
		return out;
	}

	/** The codes of the shown list after moving one of them to just before another (null, or one not there: to the end). */
	static List<String> move(List<Ring> shown, String code, String before)
	{
		List<String> codes = new ArrayList<>(shown.size());
		for (Ring r : shown)
		{
			codes.add(r.getCode());
		}
		if (!codes.remove(code))
		{
			return codes;
		}
		int at = codes.indexOf(before);
		codes.add(at < 0 ? codes.size() : at, code);
		return codes;
	}

	static List<String> parse(String csv)
	{
		if (csv == null || csv.trim().isEmpty())
		{
			return Collections.emptyList();
		}
		List<String> out = new ArrayList<>();
		for (String c : Arrays.asList(csv.split(",")))
		{
			String code = DialMath.normalize(c);
			if (code != null && !out.contains(code))
			{
				out.add(code);
			}
		}
		return out;
	}

	static String format(List<String> codes)
	{
		return String.join(",", codes);
	}
}
