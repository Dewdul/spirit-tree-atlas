/*
 * Copyright (c) 2026, Dewdul
 * All rights reserved.
 * Licensed under the BSD 2-Clause License. See LICENSE.
 */
package com.spirittreeatlas;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.Arrays;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetPositionMode;
import net.runelite.api.widgets.WidgetSizeMode;

/**
 * "Use free space" in resizable mode (DESIGN 4.2). The game mounts the menu in its main modal
 * slot, which sits centred in the HUD area above the chatbox and left of the side panel, and the
 * map grows up and left from the slot's bottom-right corner. While the map shows, this moves the
 * slot (its position only, never its size) so that corner sits at the bottom-right of the free
 * space, and puts it back when the map closes.
 *
 * <ul>
 * <li>Only the two resizable toplevels' MAINMODAL, and only from the state their interface
 * definition gives it (centred, no offset, 512x334, the menu's root filling it: MODERN
 * {@code MenuNew.INFINITE}, CLASSIC {@code Menu.LJ_LAYER2}): never over another plugin's (Better
 * Teleport Menu resizes it for the classic menu) or the game's own change.</li>
 * <li>It is anchored to the bottom-right of its container (ABSOLUTE_RIGHT / ABSOLUTE_BOTTOM),
 * so the game keeps it in that corner across window resizes by itself, and a modal opened into a
 * slot we somehow failed to restore still opens on screen.</li>
 * <li>Restored only while it still holds exactly what we wrote; otherwise someone else has
 * changed it since and it is theirs.</li>
 * </ul>
 *
 * Client thread only.
 */
final class ModalSlot
{
	/** The slot as the toplevel interfaces define it (toplevel_resize keeps it 512x334). */
	static final int SLOT_W = 512;
	static final int SLOT_H = 334;
	/** x mode, y mode, x, y of the slot as defined: centred with no offset. */
	static final int[] PRISTINE = {WidgetPositionMode.ABSOLUTE_CENTER, WidgetPositionMode.ABSOLUTE_CENTER, 0, 0};

	enum Step
	{
		/** Leave the slot alone. */
		NOTHING,
		/** Put the slot where we want it (writing only what changed). */
		MOVE,
		/** Put back what we found and forget it. */
		RESTORE,
		/** Forget it without writing: someone else has changed it since. */
		LET_GO,
	}

	/** The slot we moved, held so it can be put back even after a toplevel switch. */
	private Widget held;
	/** Its position fields as we found them, and as we last wrote them. */
	private int[] original;
	private int[] written;

	boolean holds()
	{
		return held != null;
	}

	/**
	 * Moves the menu's slot into the free space when {@code wanted} (the map shows in resizable
	 * mode with the option on), keeps it there as the free space changes, and otherwise puts it
	 * back. A slot held from before a toplevel switch is put back first.
	 *
	 * @param rootId the menu's root component, which must fill the slot exactly; -1 for none
	 */
	void update(Client client, int rootId, boolean wanted, int maxW, int maxH)
	{
		Widget root = rootId == -1 ? null : client.getWidget(rootId);
		Widget slot = root == null ? null : root.getParent();
		Widget hud = slot == null ? null : slot.getParent();
		boolean movable = slot != null && isResizableModal(slot.getId()) && hud != null;
		if (held != null && held != slot)
		{
			restore(client);
		}
		if (!movable)
		{
			restore(client);
			return;
		}
		// layout values, not drawn bounds: when the setup script has just run the menu is laid out
		// but not yet drawn (getBounds() is at -1,-1 until the first frame)
		boolean fits = fits(slot.getWidthMode(), slot.getHeightMode(), slot.getOriginalWidth(), slot.getOriginalHeight(),
			slot.getWidth(), slot.getHeight(),
			new Rectangle(root.getRelativeX(), root.getRelativeY(), root.getWidth(), root.getHeight()));
		int[] now = position(slot);
		int[] target = null;
		if (wanted && fits)
		{
			Rectangle box = hud.getBounds();
			Rectangle canvas = MapLayout.canvas(client);
			if (!laidOut(canvas, box))
			{
				// mid-resize (the canvas has changed but the HUD area has not been laid out and
				// drawn again yet) or a toplevel not drawn yet: keep whatever is there until it has
				return;
			}
			Point corner = MapLayout.slotCorner(canvas, box, slot.getWidth(), slot.getHeight(),
				MapLayout.obstacles(client), maxW, maxH);
			target = corner == null ? null : target(box, corner);
		}
		switch (next(held != null, isOurs(now, written), fits, Arrays.equals(now, PRISTINE), target != null))
		{
			case MOVE:
				if (held == null)
				{
					held = slot;
					original = now;
				}
				if (!Arrays.equals(now, target))
				{
					write(slot, target);
					slot.revalidate();
				}
				written = target;
				break;
			case RESTORE:
				restore(client);
				break;
			case LET_GO:
				forget();
				break;
			default:
				break;
		}
	}

	/** Puts the held slot back as we found it, if it still holds what we wrote; then forgets it. */
	void restore(Client client)
	{
		Widget w = held;
		int[] o = original;
		int[] mine = written;
		forget();
		if (w == null || !isOurs(position(w), mine))
		{
			return;
		}
		write(w, o);
		// a slot left behind by a toplevel switch or a logout is no longer laid out by the game
		if (client.getWidget(w.getId()) == w)
		{
			w.revalidate();
		}
	}

	private void forget()
	{
		held = null;
		original = null;
		written = null;
	}

	// ------------------------------------------------------------------ pure decisions

	/**
	 * What to do with the menu's slot.
	 *
	 * @param held we moved it and hold its record
	 * @param ours its position is still exactly what we wrote
	 * @param fits it is 512x334 with the menu's root filling it
	 * @param pristine its position is the interface definition's own
	 * @param move there is a worthwhile place for it and the map wants it
	 */
	static Step next(boolean held, boolean ours, boolean fits, boolean pristine, boolean move)
	{
		if (held)
		{
			if (!ours)
			{
				return Step.LET_GO;
			}
			return move && fits ? Step.MOVE : Step.RESTORE;
		}
		return move && fits && pristine ? Step.MOVE : Step.NOTHING;
	}

	/**
	 * Whether the slot is the size the toplevel gives it, with the menu's root filling it exactly.
	 *
	 * @param width the slot's original (defined) width; {@code slotW} its laid-out width
	 * @param root the root's laid-out position relative to the slot, and size
	 */
	static boolean fits(int widthMode, int heightMode, int width, int height, int slotW, int slotH, Rectangle root)
	{
		return widthMode == WidgetSizeMode.ABSOLUTE && heightMode == WidgetSizeMode.ABSOLUTE
			&& width == SLOT_W && height == SLOT_H && slotW == SLOT_W && slotH == SLOT_H
			&& new Rectangle(0, 0, SLOT_W, SLOT_H).equals(root);
	}

	/**
	 * Whether the container's bounds are usable for placing the slot: drawn, and inside the
	 * canvas. CanvasSizeChanged is posted before the game lays the HUD area out again, so a
	 * shrinking window briefly reports the old, larger HUD area, and offsets measured from it
	 * would put the menu off screen.
	 */
	static boolean laidOut(Rectangle canvas, Rectangle hud)
	{
		return hud != null && hud.width > 0 && hud.height > 0 && canvas.contains(hud);
	}

	/** Whether the slot's position fields are still the ones we wrote. */
	static boolean isOurs(int[] now, int[] written)
	{
		return written != null && Arrays.equals(now, written);
	}

	/** The position fields that put the slot's bottom-right corner at {@code corner} inside {@code hud}. */
	static int[] target(Rectangle hud, Point corner)
	{
		return new int[]{
			WidgetPositionMode.ABSOLUTE_RIGHT,
			WidgetPositionMode.ABSOLUTE_BOTTOM,
			hud.x + hud.width - corner.x,
			hud.y + hud.height - corner.y,
		};
	}

	static boolean isResizableModal(int id)
	{
		return id == InterfaceID.ToplevelOsrsStretch.MAINMODAL || id == InterfaceID.ToplevelPreEoc.MAINMODAL;
	}

	private static int[] position(Widget w)
	{
		return new int[]{w.getXPositionMode(), w.getYPositionMode(), w.getOriginalX(), w.getOriginalY()};
	}

	private static void write(Widget w, int[] p)
	{
		w.setXPositionMode(p[0]);
		w.setYPositionMode(p[1]);
		w.setOriginalX(p[2]);
		w.setOriginalY(p[3]);
	}
}
