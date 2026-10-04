/*
 * Copyright (c) 2026, Dewdul
 * All rights reserved.
 * Licensed under the BSD 2-Clause License. See LICENSE.
 */
package com.spirittreeatlas;

import java.awt.Rectangle;
import java.util.List;
import lombok.Value;

/**
 * Something on the map the mouse can point at, published by the painters each frame. Hits later
 * in the list are on top. The plugin builds the right-click menu from the hit under the mouse
 * (option and target are its left-click entry) and {@link AtlasInput} lets a left press through
 * only for a hit the menu was built for.
 */
@Value
public class Hit
{
	public enum Kind
	{
		/** A tree marker, or its surface stand-in at a portal (id {@link #STAND_IN}); tree is set. */
		MARKER,
		/** The map-link glyph where another layer is entered; id is that layer's id. */
		PORTAL,
		/** A chrome button; id names it. */
		BUTTON,
		/** Solid chrome with no action (top bar, card, the covered Travel cell): absorbs presses, never starts a drag. */
		BLOCK,
	}

	public static final String ZOOM_IN = "zoomIn";
	public static final String ZOOM_OUT = "zoomOut";
	public static final String FIT = "fit";
	/** Top bar: switch to List mode (the plain menu). */
	public static final String LIST = "list";
	public static final String CLEAR = "clear";
	/** Back to the surface from another layer. */
	public static final String BACK = "back";
	/** List mode's floating button: back to Map mode. */
	public static final String SHOW_MAP = "map";
	/** Id of a MARKER that is a surface stand-in for a tree on another layer. */
	public static final String STAND_IN = "standIn";

	Kind kind;
	Rectangle area;
	Tree tree;
	String id;
	/** Menu option and target of the left-click entry; null for BLOCK. */
	String option;
	String target;

	public boolean isActionable()
	{
		return kind != Kind.BLOCK;
	}

	public boolean isStandIn()
	{
		return kind == Kind.MARKER && STAND_IN.equals(id);
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
