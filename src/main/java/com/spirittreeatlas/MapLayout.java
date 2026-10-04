/*
 * Copyright (c) 2026, Dewdul
 * All rights reserved.
 * Licensed under the BSD 2-Clause License. See LICENSE.
 */
package com.spirittreeatlas;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;

/**
 * Where the map goes (DESIGN 4.2). Fixed mode: exactly over the menu's slot (the 512x334 "menu
 * rect"). Resizable: as large as the config allows, growing up and left from the menu rect's
 * bottom-right corner (where the Travel row and the close button are moved), clear of the
 * chatbox and the side panel, and never smaller than the menu rect. With "Use free space",
 * {@link #slotCorner} says where the slot itself goes so that this growth fills the free part of
 * the screen ({@link ModalSlot}).
 */
public final class MapLayout
{
	private static final int[] OBSTACLES = {
		InterfaceID.ToplevelOsrsStretch.CHAT_CONTAINER,
		InterfaceID.ToplevelOsrsStretch.SIDE_MENU,
		InterfaceID.ToplevelPreEoc.CHAT_CONTAINER,
		InterfaceID.ToplevelPreEoc.SIDE_CONTAINER,
		InterfaceID.ToplevelPreEoc.SIDE_STATIC_LAYER,
		InterfaceID.ToplevelPreEoc.SIDE_MOVABLE_LAYER,
	};

	/** Gap between the map and the top of the canvas. */
	static final int TOP_MARGIN = 6;
	/** The slot is moved into the free space only when that moves its corner at least this far. */
	static final int MIN_SLOT_GAIN = 8;

	private MapLayout()
	{
	}

	/** The map rectangle for the live client around the menu rect (the slot's bounds), or null without one. */
	static Rectangle fromClient(Client client, Rectangle menu, int maxW, int maxH)
	{
		if (menu == null)
		{
			return null;
		}
		if (!client.isResized())
		{
			return menu;
		}
		return compute(canvas(client), menu, obstacles(client), maxW, maxH);
	}

	static Rectangle canvas(Client client)
	{
		return new Rectangle(0, 0, client.getCanvasWidth(), client.getCanvasHeight());
	}

	/**
	 * The live bounds of everything the map keeps clear of: the chatbox (even when collapsed) and
	 * the side panel with its tab bars. A widget not drawn yet has no place on screen yet and is
	 * left out rather than taken as sitting at the top-left.
	 */
	static List<Rectangle> obstacles(Client client)
	{
		List<Rectangle> obstacles = new ArrayList<>();
		for (int id : OBSTACLES)
		{
			Rectangle r = bounds(client, id);
			if (r != null && !(r.x == -1 && r.y == -1))
			{
				obstacles.add(r);
			}
		}
		return obstacles;
	}

	static Rectangle bounds(Client client, int component)
	{
		Widget w = client.getWidget(component);
		if (w == null || w.isHidden())
		{
			return null;
		}
		Rectangle r = w.getBounds();
		return r == null || r.width <= 0 || r.height <= 0 ? null : r;
	}

	/**
	 * Resizable-mode layout. Each obstacle overlapping the usable area cuts it from the side
	 * (bottom, right, left or top) that clears it and keeps the most room. A cut never goes past
	 * the menu rect's own edge, so on a small canvas, where the menu sits within a few pixels of
	 * the edge, the map still stops at the menu instead of covering the chatbox and side panel.
	 */
	public static Rectangle compute(Rectangle canvas, Rectangle menu, List<Rectangle> obstacles, int maxW, int maxH)
	{
		int menuRight = menu.x + menu.width;
		int menuBottom = menu.y + menu.height;
		// the inset canvas, grown to hold the menu when it sits closer than the inset to an edge
		int[] a = cut(new int[]{
			Math.min(canvas.x + 6, menu.x),
			// the game's mouse-over text at the top-left is turned off while the map shows
			Math.min(canvas.y + TOP_MARGIN, menu.y),
			Math.max(canvas.x + canvas.width - 6, menuRight),
			Math.max(canvas.y + canvas.height - 6, menuBottom),
		}, obstacles, menu);

		// the map grows up and left from the menu's bottom-right corner: the Travel row and the
		// close button, which the game shows only inside the slot, are moved into that corner
		int w = Math.max(menu.width, Math.min(maxW, menuRight - a[0]));
		int h = Math.max(menu.height, Math.min(maxH, menuBottom - a[1]));
		return new Rectangle(menuRight - w, menuBottom - h, w, h);
	}

	/**
	 * Resizable mode, "Use free space": where the slot's bottom-right corner should go so that the
	 * map, which grows up and left from it, fills the free part of the screen. The free part is
	 * the slot's container (the game's HUD area, which clips it) inset by 6 px and cut clear of
	 * the obstacles as in {@link #compute}. The corner goes in its bottom-right corner or, when the
	 * size caps leave the map smaller than the free part, where the map comes out centred in it.
	 *
	 * @param hud the slot's container, which it must stay inside
	 * @return the corner in canvas coordinates, or null when the slot does not fit or the move
	 *     would gain less than {@link #MIN_SLOT_GAIN} px over the game's own centred place
	 */
	public static Point slotCorner(Rectangle canvas, Rectangle hud, int slotW, int slotH, List<Rectangle> obstacles, int maxW, int maxH)
	{
		int[] a = cut(new int[]{
			Math.max(canvas.x + 6, hud.x + 6),
			Math.max(canvas.y + TOP_MARGIN, hud.y + 6),
			Math.min(canvas.x + canvas.width, hud.x + hud.width) - 6,
			Math.min(canvas.y + canvas.height, hud.y + hud.height) - 6,
		}, obstacles, null);
		int freeW = a[2] - a[0];
		int freeH = a[3] - a[1];
		if (freeW < slotW || freeH < slotH)
		{
			return null;
		}
		// a capped map is centred in the free part; the slot stays inside it either way
		int mapW = Math.max(slotW, maxW);
		int mapH = Math.max(slotH, maxH);
		int x = mapW >= freeW ? a[2] : a[0] + (freeW + mapW) / 2;
		int y = mapH >= freeH ? a[3] : a[1] + (freeH + mapH) / 2;
		// where the game itself puts it: centred in the container
		int homeX = hud.x + (hud.width - slotW) / 2 + slotW;
		int homeY = hud.y + (hud.height - slotH) / 2 + slotH;
		if (Math.abs(x - homeX) < MIN_SLOT_GAIN && Math.abs(y - homeY) < MIN_SLOT_GAIN)
		{
			return null;
		}
		return new Point(x, y);
	}

	/**
	 * Cuts the area {left, top, right, bottom} clear of each obstacle that overlaps it, from the
	 * side that clears it and keeps the most room. With {@code keep} (the menu rect), a cut never goes
	 * past its edge and an obstacle over it is ignored; on a canvas too small for any cut to clear
	 * an obstacle, the least overlapping cut is taken.
	 */
	private static int[] cut(int[] area, List<Rectangle> obstacles, Rectangle keep)
	{
		int keepLeft = keep == null ? Integer.MAX_VALUE : keep.x;
		int keepTop = keep == null ? Integer.MAX_VALUE : keep.y;
		int keepRight = keep == null ? Integer.MIN_VALUE : keep.x + keep.width;
		int keepBottom = keep == null ? Integer.MIN_VALUE : keep.y + keep.height;
		int[] a = area;
		for (Rectangle o : obstacles)
		{
			if (o.x >= a[2] || o.x + o.width <= a[0] || o.y >= a[3] || o.y + o.height <= a[1] || (keep != null && o.intersects(keep)))
			{
				continue;
			}
			int[][] options = {
				{a[0], a[1], a[2], Math.max(o.y - 6, keepBottom)},
				{a[0], a[1], Math.max(o.x - 8, keepRight), a[3]},
				{Math.min(o.x + o.width + 8, keepLeft), a[1], a[2], a[3]},
				{a[0], Math.min(o.y + o.height + 6, keepTop), a[2], a[3]},
			};
			// least overlap with the obstacle first (none, when a cut clears it), then most room
			int[] best = null;
			long bestOverlap = Long.MAX_VALUE;
			long bestArea = -1;
			for (int[] c : options)
			{
				Rectangle r = new Rectangle(c[0], c[1], Math.max(0, c[2] - c[0]), Math.max(0, c[3] - c[1]));
				Rectangle in = r.intersection(o);
				long overlap = in.isEmpty() ? 0 : (long) in.width * in.height;
				long room = (long) r.width * r.height;
				if (overlap < bestOverlap || (overlap == bestOverlap && room > bestArea))
				{
					bestOverlap = overlap;
					bestArea = room;
					best = c;
				}
			}
			a = best;
		}
		return a;
	}
}
