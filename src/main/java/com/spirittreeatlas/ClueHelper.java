/*
 * Copyright (c) 2026, Dewdul
 * All rights reserved.
 * Licensed under the BSD 2-Clause License. See LICENSE.
 */
package com.spirittreeatlas;

import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.cluescrolls.ClueScrollService;
import net.runelite.client.plugins.cluescrolls.clues.ClueScroll;
import net.runelite.client.plugins.cluescrolls.clues.FairyRingClue;
import net.runelite.client.plugins.cluescrolls.clues.LocationClueScroll;
import net.runelite.client.plugins.cluescrolls.clues.LocationsClueScroll;

/**
 * Optional integration with the core Clue Scroll plugin: where the active clue step is, and the
 * code of a fairy ring clue. Everything is defensive, because the clue API is internal and may
 * change; any failure simply means no clue marker.
 */
@Slf4j
@Singleton
public class ClueHelper
{
	private final ClueScrollService service;

	@Inject
	ClueHelper(ClueScrollService service)
	{
		this.service = service;
	}

	/**
	 * The active clue's world location, or null. A clue with a single answer gives it through
	 * {@code getLocation} (a hot-cold clue only once it is solved). A clue with only a list of
	 * locations is marked only when exactly one is left: the list of a hot-cold or three-step clue
	 * holds candidates or unsolved steps, and marking an arbitrary one would point at the wrong ring.
	 */
	public WorldPoint location()
	{
		try
		{
			ClueScroll clue = service.getClue();
			if (clue instanceof LocationClueScroll)
			{
				return ((LocationClueScroll) clue).getLocation(null);
			}
			if (clue instanceof LocationsClueScroll)
			{
				WorldPoint[] points = ((LocationsClueScroll) clue).getLocations(null);
				WorldPoint only = null;
				int n = 0;
				for (WorldPoint p : points == null ? new WorldPoint[0] : points)
				{
					if (p != null)
					{
						only = p;
						n++;
					}
				}
				return n == 1 ? only : null;
			}
		}
		catch (RuntimeException | LinkageError e)
		{
			log.debug("clue location unavailable", e);
		}
		return null;
	}

	/** The code of an active fairy ring clue ("A I R 2 3 3 1" gives "AIR"), or null. */
	public String fairyRingCode()
	{
		try
		{
			ClueScroll clue = service.getClue();
			if (clue instanceof FairyRingClue)
			{
				String text = ((FairyRingClue) clue).getText();
				return text == null || text.length() < 5 ? null : DialMath.normalize(text.substring(0, 5));
			}
		}
		catch (RuntimeException | LinkageError e)
		{
			log.debug("fairy ring clue unavailable", e);
		}
		return null;
	}
}
