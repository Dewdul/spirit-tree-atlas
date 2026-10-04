/*
 * Copyright (c) 2026, Dewdul
 * All rights reserved.
 * Licensed under the BSD 2-Clause License. See LICENSE.
 */
package com.fairyringatlas;

import java.awt.Rectangle;
import java.util.List;
import lombok.Value;

/**
 * Something on the map the mouse can point at, published by the painter each frame. Hits later in
 * the list are on top.
 */
@Value
public class Hit
{
	public enum Kind
	{
		/** A ring marker; ring is set. */
		MARKER,
		/** A ring chip in an Elsewhere card, a Favourites row (id FAVE) or a group's row (id {@link #groupRow}); ring is set. */
		CHIP,
		/** An Elsewhere card; id is the layer id; ring is the ring a click selects, if any. */
		CARD,
		/** A surface entrance to a layer; id is the layer id. */
		PORTAL,
		/** A toolbar or card button; id names it. */
		BUTTON,
		/** Solid chrome with no action: absorbs presses, never starts a drag. */
		BLOCK,
		/** The Elsewhere panel background: like BLOCK, but the wheel scrolls it. */
		PANEL,
		/** A group's header in the Groups panel; id is the group id. */
		GROUP,
	}

	public static final String ZOOM_IN = "zoomIn";
	public static final String ZOOM_OUT = "zoomOut";
	public static final String FIT = "fit";
	public static final String DIALS = "dials";
	public static final String CLEAR = "clear";
	public static final String BACK = "back";
	public static final String TOGGLE_PANEL = "panel";
	public static final String SHOW_MAP = "map";
	/** Id of a ring row in the panel's Favourites list. */
	public static final String FAVE = "fave";
	/** Id of the house card in the side panel (a CHIP hit). */
	public static final String HOUSE = "house";
	public static final String TOGGLE_GROUPS = "groups";
	public static final String NEW_GROUP = "newGroup";
	private static final String GROUP_ROW = "group:";

	Kind kind;
	Rectangle area;
	Ring ring;
	String id;
	/** Menu option and target shown for the hit; null for BLOCK/PANEL. */
	String option;
	String target;

	public boolean isActionable()
	{
		return kind != Kind.BLOCK && kind != Kind.PANEL;
	}

	/** Id of a ring row in a group of the Groups panel. */
	public static String groupRow(String groupId)
	{
		return GROUP_ROW + groupId;
	}

	/** The group of a group-row id, or null for any other id. */
	public static String groupOf(String id)
	{
		return id != null && id.startsWith(GROUP_ROW) ? id.substring(GROUP_ROW.length()) : null;
	}

	/** Whether a row can be dragged to reorder its list: a favourite or a group's ring. */
	public boolean isDraggableRow()
	{
		return kind == Kind.CHIP && ring != null && (FAVE.equals(id) || groupOf(id) != null);
	}

	/** The topmost hit containing the point, or null. */
	public static Hit at(List<Hit> hits, int x, int y)
	{
		if (hits == null)
		{
			return null;
		}
		for (int i = hits.size() - 1; i >= 0; i--)
		{
			Hit h = hits.get(i);
			if (h.area.contains(x, y))
			{
				return h;
			}
		}
		return null;
	}
}
