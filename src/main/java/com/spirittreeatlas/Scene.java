/*
 * Copyright (c) 2026, Dewdul
 * All rights reserved.
 * Licensed under the BSD 2-Clause License. See LICENSE.
 */
package com.spirittreeatlas;

import java.awt.Color;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

/**
 * Everything the painter needs for one frame, as plain data so the map can be drawn without a
 * game client (previews and tests). The overlay refills one instance every frame.
 */
public final class Scene
{
	public enum CodeLabels
	{
		ALL,
		FAVOURITES,
		NONE,
	}

	// --- data
	public RingRepository repo;
	public MapView view;
	public final List<Rectangle> holes = new ArrayList<>();
	/** The CONFIRM hole, for the "ready" pulse; null while Teleport is covered. */
	public Rectangle confirmHole;
	/** Where Teleport (CONFIRM) is, shown or covered; may be null. */
	public Rectangle teleportSlot;

	// --- state
	public String selected;
	/** Ring under the mouse (any entry, not only dialable ones). */
	public Ring hovered;
	/** Nearest ring when the interface opened, or null. */
	public String here;
	/** Current dial values, or null when unknown. */
	public int[] dials;
	/** Native travel-log search text, lower case; empty when none. */
	public String query = "";
	/** Whether the selected ring's log row is on screen (drives the next-step line). */
	public boolean rowVisible;
	/** Layers whose cards flash because they match the search; may be empty. */
	public long flashUntil;
	public String notice;
	public boolean panelCollapsed;
	/** The player's own order for the Favourites list (codes); may be empty. */
	public List<String> faveOrder = new ArrayList<>();
	/** The row being dragged to a new place in its list (Hit.FAVE or a group row id), or null; and the pointer's y. */
	public String dragList;
	public String dragCode;
	public int dragY;
	public int panelScroll;
	/** The Groups panel on the right: open or a slim tab, its groups and its scroll. */
	public boolean groupsOpen;
	public List<RingGroups.Group> groups = new ArrayList<>();
	public int groupsScroll;
	/** A hint for the hovered ring from the group row it is hovered in (a prebuilt group's note), or null. */
	public String hoveredNote;
	/** What is at the hovered ring's spot, from the same group row (its patches or slayer monsters), or null. */
	public String hoveredDetails;
	public double clueX = Double.NaN;
	public double clueY = Double.NaN;
	public Point mouse;
	public long now;

	// --- settings
	public CodeLabels codeLabels = CodeLabels.ALL;
	public boolean placeLabels = true;
	public boolean mapIcons = true;
	public boolean dimUnvisited;
	/** The ring card shows everything rather than a short summary. */
	public boolean fullDetails;
	public Color visitedColor = new Color(0x3FD9C8);
	public Color selectedColor = new Color(0xFF981F);
	public Color favouriteColor = new Color(0xFFD700);
	/** Sprite lookup for map icons; returns null until a sprite is loaded. */
	public IntFunction<BufferedImage> sprites = id -> null;

	// --- output
	public final List<Hit> hits = new ArrayList<>();
	public Rectangle card;
	public Rectangle panel;
	/** The Groups panel, or its tab while it is closed. */
	public Rectangle groupsPanel;
	public Rectangle topBar;
	/** The large "Back to Gielinor" button on off-surface maps; null on the surface. */
	public Rectangle backButton;

	/** The holes plus Teleport's slot when it is covered: places the card and labels keep clear of. */
	public List<Rectangle> blockers()
	{
		if (teleportSlot == null || holes.contains(teleportSlot))
		{
			return holes;
		}
		List<Rectangle> out = new ArrayList<>(holes);
		out.add(teleportSlot);
		return out;
	}

	/** Whether this code's row in this list is the one being dragged. */
	public boolean dragging(String list, String code)
	{
		return dragCode != null && dragCode.equals(code) && dragList != null && dragList.equals(list);
	}

	public boolean searching()
	{
		return query != null && !query.isEmpty();
	}

	public boolean dialsMatch(String code)
	{
		if (dials == null || code == null || !DialMath.isCode(code))
		{
			return false;
		}
		int[] v = DialMath.values(code);
		return v[0] == dials[0] && v[1] == dials[1] && v[2] == dials[2];
	}

	public String dialledCode()
	{
		return dials == null ? null : DialMath.code(dials[0], dials[1], dials[2]);
	}

	/**
	 * Whether the real Teleport button is left uncovered. Only when pressing it goes where the
	 * player expects: the dials show the selected ring, or, with nothing selected, a real
	 * destination. Otherwise the map covers it, so a stale or invalid code is not used by mistake.
	 */
	public boolean teleportShown()
	{
		if (selected != null)
		{
			return dialsMatch(selected);
		}
		Ring r = repo == null ? null : repo.ring(dialledCode());
		return r != null && r.isDialable();
	}

	/**
	 * Whether the selected ring must be dialled by hand: it is locked (not in the travel log, so
	 * there is no row to click), the dials do not show it yet, and a first visit could unlock it
	 * (no unmet unlock condition).
	 */
	public boolean needsDialing()
	{
		UnlockCheck.Result u = lockedSelection();
		return u != null && u.getStatus() != UnlockCheck.Status.NOT_MET;
	}

	/** Whether the selected ring is locked and a visit cannot unlock it yet: it needs something first. */
	public boolean unlockBlocked()
	{
		UnlockCheck.Result u = lockedSelection();
		return u != null && u.getStatus() == UnlockCheck.Status.NOT_MET;
	}

	/** The selected ring's unlock check while it is locked and not on the dials, else null. */
	private UnlockCheck.Result lockedSelection()
	{
		Ring r = repo == null ? null : repo.ring(selected);
		return r != null && r.isDialable() && !repo.hasLogRow(r.getCode()) && !dialsMatch(r.getCode()) ? repo.unlock(r) : null;
	}

	/**
	 * The two lines the covered Teleport's stand-in shows: where it would go, and what sets the
	 * dials. A map click only selects (hard rule 2), so this is how the button answers it.
	 */
	public String[] teleportStandIn()
	{
		Ring r = repo == null ? null : repo.ring(selected);
		if (r == null)
		{
			return new String[]{"Teleport", "Pick a ring on the map"};
		}
		String title = "Teleport to " + r.getCode();
		if (!r.isDialable())
		{
			return new String[]{title, "It has no code to dial"};
		}
		if (repo.hasLogRow(r.getCode()))
		{
			return new String[]{title, rowVisible ? "Click it in the travel log"
				: searching() ? "Clear the log search first" : "Use it in the travel log first"};
		}
		UnlockCheck.Result u = repo.unlock(r);
		if (u.getStatus() == UnlockCheck.Status.NOT_MET)
		{
			return new String[]{r.getCode() + " is locked", "Needs " + u.getLabel()};
		}
		return new String[]{title, "Locked: dial it by hand"};
	}
}
