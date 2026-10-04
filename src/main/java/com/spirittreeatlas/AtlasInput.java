/*
 * Copyright (c) 2026, Dewdul
 * All rights reserved.
 * Licensed under the BSD 2-Clause License. See LICENSE.
 */
package com.spirittreeatlas;

import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.input.MouseWheelListener;

/**
 * Mouse input over the map, on the AWT thread (DESIGN 4.8). It touches only the plugin's volatile
 * and synchronised view state. Wheel zooms, or scrolls the quick-select panel while the pointer is
 * over it and its rows overflow; a left press on empty map pans; a left press on a
 * marker or portal is held until release, so a click selects it but a drag starting on it pans
 * (both use {@link #CLICK_SLOP}); a left press on a button or a quick-select row is let through so
 * the game runs our RUNELITE menu entry (swallowed instead when the game's menu was still built
 * for something else, so no stale game entry runs); a press on the panel's body is absorbed;
 * right presses always go through to open the menu we built.
 * No press is consumed inside the holes, while a menu is open or outside the map; the mouse
 * wheel is kept from the game anywhere on the map (the classic list would scroll). In List mode
 * only a left press on the Map button is checked, the same way as a button press on the map.
 */
@Singleton
public class AtlasInput extends MouseAdapter implements MouseWheelListener
{
	private static final double WHEEL_STEP = 1.25;
	/** How far (px) a press may wander and still count as a click rather than a drag. */
	static final int CLICK_SLOP = 4;

	private final SpiritTreeAtlasPlugin plugin;
	private volatile boolean dragging;
	private volatile boolean swallow;
	/** The marker or portal under the press: clicked on release unless the press became a drag. */
	private volatile Hit pending;
	private volatile boolean moved;
	private int pressX;
	private int pressY;
	private int lastX;
	private int lastY;

	@Inject
	AtlasInput(SpiritTreeAtlasPlugin plugin)
	{
		this.plugin = plugin;
	}

	@Override
	public MouseWheelEvent mouseWheelMoved(MouseWheelEvent e)
	{
		if (plugin.isMapInput(e.getX(), e.getY()))
		{
			Hit hit = plugin.hitAt(e.getX(), e.getY());
			if (hit != null && (hit.getKind() == Hit.Kind.PANEL || hit.getKind() == Hit.Kind.ROW) && plugin.canScrollPanel())
			{
				plugin.scrollPanel((int) Math.round(e.getPreciseWheelRotation() * SpiritTreeAtlasPlugin.PANEL_WHEEL_STEP));
			}
			else
			{
				plugin.zoomAt(e.getX(), e.getY(), Math.pow(WHEEL_STEP, -e.getPreciseWheelRotation()));
			}
			e.consume();
		}
		else if (plugin.isOverMap(e.getX(), e.getY()))
		{
			// a hole or an open menu: kept from the game, which would scroll the classic list (and
			// the Travel row out of its hole)
			e.consume();
		}
		return e;
	}

	@Override
	public MouseEvent mousePressed(MouseEvent e)
	{
		swallow = false;
		dragging = false;
		if (!SwingUtilities.isLeftMouseButton(e))
		{
			return e;
		}
		if (plugin.isMapButton(e.getX(), e.getY()))
		{
			// List mode's Map button: the game runs its top menu entry, which must be our "Show Map"
			if (!plugin.isMenuForMapButton())
			{
				swallow = true;
				e.consume();
			}
			return e;
		}
		if (!plugin.isMapInput(e.getX(), e.getY()))
		{
			return e;
		}
		Hit hit = plugin.hitAt(e.getX(), e.getY());
		if (hit != null && (hit.getKind() == Hit.Kind.MARKER || hit.getKind() == Hit.Kind.PORTAL))
		{
			// a click selects it, a drag pans the map; which one is only known on release
			pending = hit;
			moved = false;
			dragging = true;
			pressX = e.getX();
			pressY = e.getY();
			lastX = e.getX();
			lastY = e.getY();
			swallow = true;
			e.consume();
			return e;
		}
		if (hit != null && hit.isActionable() && plugin.isMenuFor(hit))
		{
			// the game runs the top menu entry, which is ours for this very hit (a button's, or a
			// quick-select row's Select)
			return e;
		}
		if (hit == null)
		{
			dragging = true;
			lastX = e.getX();
			lastY = e.getY();
		}
		swallow = true;
		e.consume();
		return e;
	}

	@Override
	public MouseEvent mouseDragged(MouseEvent e)
	{
		if (dragging && pending != null && !moved)
		{
			if (Math.abs(e.getX() - pressX) + Math.abs(e.getY() - pressY) <= CLICK_SLOP)
			{
				// still a click: a small wobble must not pan
				return e;
			}
			moved = true;
		}
		if (dragging)
		{
			plugin.panBy(e.getX() - lastX, e.getY() - lastY);
			lastX = e.getX();
			lastY = e.getY();
			// not consumed: the game took no press, so to it this is a plain move, and its mouse
			// position (hover, the info card, the next menu) keeps up with the pointer
		}
		return e;
	}

	@Override
	public MouseEvent mouseReleased(MouseEvent e)
	{
		Hit hit = pending;
		if (hit != null)
		{
			pending = null;
			if (!moved)
			{
				plugin.clickHit(hit);
			}
		}
		if (dragging || swallow)
		{
			dragging = false;
			e.consume();
		}
		return e;
	}

	@Override
	public MouseEvent mouseClicked(MouseEvent e)
	{
		if (swallow)
		{
			swallow = false;
			e.consume();
		}
		return e;
	}

	void reset()
	{
		dragging = false;
		swallow = false;
		pending = null;
		moved = false;
	}
}
