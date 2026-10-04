/*
 * Copyright (c) 2026, Dewdul
 * All rights reserved.
 * Licensed under the BSD 2-Clause License. See LICENSE.
 */
package com.fairyringatlas;

import java.awt.Color;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.api.Client;
import net.runelite.api.Menu;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.PostMenuSort;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.Text;

/**
 * Puts destination names in a fairy ring's own right-click menu: "Last-destination (AJR) Fairy
 * ring" reads "Last (AJR) Fremennik Slayer Dungeon", and the codes in its Favourites submenu get
 * the ring's name as their target, so "CIR Fairy ring" reads "CIR Mount Karuulm". It only
 * relabels entries the game made; it never adds, removes or reorders one. An entry another plugin
 * has already renamed is left alone.
 *
 * <p>It is its own event subscriber because RuneLite's event bus allows one handler per event
 * per object, named after the event, and the plugin already handles PostMenuSort for the map.
 */
public final class RingMenuNames
{
	private static final Pattern CODE = Pattern.compile("^([A-D][I-L][P-S])$");
	private static final Pattern LAST = Pattern.compile("^(?:Ring-)?Last-destination \\(([A-D][I-L][P-S])\\)$", Pattern.CASE_INSENSITIVE);
	/** The game's colour for object names in menus. */
	private static final Color TARGET = new Color(0x00FFFF);

	private final Client client;
	private final FairyRingAtlasConfig config;
	private final Supplier<RingRepository> repo;

	RingMenuNames(Client client, FairyRingAtlasConfig config, Supplier<RingRepository> repo)
	{
		this.client = client;
		this.config = config;
		this.repo = repo;
	}

	/** After Menu Entry Swapper and Fairy Ring Favourites, which may rename the same entries. */
	@Subscribe(priority = -2)
	public void onPostMenuSort(PostMenuSort e)
	{
		if (!client.isMenuOpen() && config.menuNames())
		{
			relabel(client.getMenu(), repo.get());
		}
	}

	@Subscribe(priority = -2)
	public void onMenuOpened(MenuOpened e)
	{
		if (config.menuNames())
		{
			relabel(client.getMenu(), repo.get());
		}
	}

	/** The code an entry's option names ("CIR" or "Last-destination (AJR)"), or null. */
	static String codeIn(String option)
	{
		if (option == null)
		{
			return null;
		}
		String o = Text.removeTags(option).trim();
		Matcher m = CODE.matcher(o);
		if (m.matches())
		{
			return m.group(1);
		}
		m = LAST.matcher(o);
		return m.matches() ? m.group(1).toUpperCase(Locale.ROOT) : null;
	}

	/** Whether a menu target is a fairy ring (a ring, or the house's Spiritual Fairy Tree). */
	static boolean isRing(String target)
	{
		String t = target == null ? "" : Text.removeTags(target).toLowerCase(Locale.ROOT);
		return t.contains("fairy ring") || t.contains("fairy tree");
	}

	static void relabel(Menu root, RingRepository repo)
	{
		for (MenuEntry e : root.getMenuEntries())
		{
			if (!isRing(e.getTarget()))
			{
				continue;
			}
			name(e, repo, false);
			Menu sub = e.getSubMenu();
			if (sub != null)
			{
				for (MenuEntry s : sub.getMenuEntries())
				{
					name(s, repo, true);
				}
			}
		}
	}

	/**
	 * Names one entry. Its target must still be the ring's own ("Fairy ring"), or empty in a
	 * submenu, so a renamed entry (ours from the last frame, or another plugin's) is skipped.
	 */
	private static void name(MenuEntry e, RingRepository repo, boolean inSubMenu)
	{
		String target = e.getTarget();
		boolean blank = target == null || Text.removeTags(target).trim().isEmpty();
		if (!isRing(target) && !(inSubMenu && blank))
		{
			return;
		}
		String option = e.getOption();
		String code = codeIn(option);
		Ring r = code == null ? null : repo.ring(code);
		if (r != null)
		{
			if (isLast(option))
			{
				// the name makes "destination" redundant
				e.setOption("Last (" + code + ")");
			}
			e.setTarget(ColorUtil.wrapWithColorTag(repo.displayName(r), TARGET));
		}
	}

	/** Whether an option is the ring's "Last-destination (CODE)". */
	static boolean isLast(String option)
	{
		return option != null && LAST.matcher(Text.removeTags(option).trim()).matches();
	}
}
