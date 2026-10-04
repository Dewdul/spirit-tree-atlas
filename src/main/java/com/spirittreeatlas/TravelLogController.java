/*
 * Copyright (c) 2026, Dewdul
 * All rights reserved.
 * Licensed under the BSD 2-Clause License. See LICENSE.
 */
package com.spirittreeatlas;

import java.awt.Rectangle;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.util.Text;

/**
 * Narrows the travel log (interface 381) to the selected ring: every other row, star and code label
 * is hidden, the favourites block collapses, and the chosen row with its star and red code label
 * moves to the top in the selection colour. This is the technique Fairy Ring Map uses: rows are
 * only hidden, moved and recoloured within their own interface, and every change is recorded so
 * it can be put back exactly. The player still clicks the real row.
 * <p>
 * The game re-lays-out the log (proc 8080) on open, sort and search; its rebuild overwrites our
 * edits, so the records are then dropped (only the text colours, which it never resets, are put
 * back) and the filter is applied again.
 */
@Slf4j
public class TravelLogController
{
	static final int[] FAVE_ROWS = {
		InterfaceID.FairyringsLog.FAVE_1, InterfaceID.FairyringsLog.FAVE_2, InterfaceID.FairyringsLog.FAVE_3,
		InterfaceID.FairyringsLog.FAVE_4, InterfaceID.FairyringsLog.FAVE_5, InterfaceID.FairyringsLog.FAVE_6,
		InterfaceID.FairyringsLog.FAVE_7, InterfaceID.FairyringsLog.FAVE_8, InterfaceID.FairyringsLog.FAVE_9,
		InterfaceID.FairyringsLog.FAVE_10,
	};
	static final int[] FAVE_CODES = {
		InterfaceID.FairyringsLog.FAVE_CODE_1, InterfaceID.FairyringsLog.FAVE_CODE_2, InterfaceID.FairyringsLog.FAVE_CODE_3,
		InterfaceID.FairyringsLog.FAVE_CODE_4, InterfaceID.FairyringsLog.FAVE_CODE_5, InterfaceID.FairyringsLog.FAVE_CODE_6,
		InterfaceID.FairyringsLog.FAVE_CODE_7, InterfaceID.FairyringsLog.FAVE_CODE_8, InterfaceID.FairyringsLog.FAVE_CODE_9,
		InterfaceID.FairyringsLog.FAVE_CODE_10,
	};
	static final int[] FAVE_ICONS = {
		InterfaceID.FairyringsLog.FAVE_ICON_1, InterfaceID.FairyringsLog.FAVE_ICON_2, InterfaceID.FairyringsLog.FAVE_ICON_3,
		InterfaceID.FairyringsLog.FAVE_ICON_4, InterfaceID.FairyringsLog.FAVE_ICON_5, InterfaceID.FairyringsLog.FAVE_ICON_6,
		InterfaceID.FairyringsLog.FAVE_ICON_7, InterfaceID.FairyringsLog.FAVE_ICON_8, InterfaceID.FairyringsLog.FAVE_ICON_9,
		InterfaceID.FairyringsLog.FAVE_ICON_10,
	};
	private static final int ROW_GAP = 3;

	private final Client client;
	private final Map<Widget, RowState> edits = new LinkedHashMap<>();
	/** The code the log is currently filtered to, or null. */
	private String filtered;

	public TravelLogController(Client client)
	{
		this.client = client;
	}

	public boolean isFiltered()
	{
		return filtered != null;
	}

	/**
	 * The row the player should click for a code: its favourites-block row when favourited,
	 * otherwise its ordinary row. Null when the game is not showing it.
	 */
	Widget row(Ring ring, int faveSlot)
	{
		Widget w = faveSlot >= 0 ? client.getWidget(FAVE_ROWS[faveSlot])
			: ring.getRowComponent() > 0 ? client.getWidget(ring.getRowComponent()) : null;
		if (w == null || w.isHidden() || w.getText() == null || w.getText().isEmpty())
		{
			return null;
		}
		return w;
	}

	/** On-screen bounds of the row, clipped to the scroll viewport; null when not visible. */
	Rectangle rowBounds(Ring ring, int faveSlot)
	{
		Widget w = row(ring, faveSlot);
		Widget contents = client.getWidget(InterfaceID.FairyringsLog.CONTENTS);
		if (w == null || contents == null || contents.isHidden())
		{
			return null;
		}
		Rectangle r = w.getBounds();
		Rectangle view = contents.getBounds();
		if (r == null || view == null)
		{
			return null;
		}
		// the row widget starts after the star; include the star column on the left
		Rectangle full = new Rectangle(r.x - 18, r.y, r.width + 18, r.height);
		Rectangle clipped = full.intersection(view);
		return clipped.isEmpty() ? null : clipped;
	}

	/**
	 * Filter the log to one ring. Any earlier filter is put back first, so the target is looked up
	 * in the log as the game drew it (a previous filter hid every other row). Returns false, with
	 * the log left restored, when its row is not there.
	 */
	public boolean apply(Ring ring, int faveSlot, Map<String, int[]> logComponents, int colour)
	{
		restore();
		Widget contents = client.getWidget(InterfaceID.FairyringsLog.CONTENTS);
		Widget target = row(ring, faveSlot);
		if (contents == null || target == null)
		{
			return false;
		}
		String code = ring.getCode();
		Widget label = faveSlot >= 0 ? client.getWidget(FAVE_CODES[faveSlot]) : codeLabel(contents, target, code);
		Widget star = faveSlot >= 0 ? client.getWidget(FAVE_ICONS[faveSlot])
			: ring.getStarComponent() > 0 ? client.getWidget(ring.getStarComponent()) : null;

		for (int i = 0; i < FAVE_ROWS.length; i++)
		{
			if (i != faveSlot)
			{
				hide(client.getWidget(FAVE_ROWS[i]));
				hide(client.getWidget(FAVE_CODES[i]));
				hide(client.getWidget(FAVE_ICONS[i]));
			}
		}
		for (int[] c : logComponents.values())
		{
			Widget row = client.getWidget(c[0]);
			if (row != target)
			{
				hide(row);
			}
			Widget s = c[1] > 0 ? client.getWidget(c[1]) : null;
			if (s != star)
			{
				hide(s);
			}
		}
		hide(client.getWidget(InterfaceID.FairyringsLog.HIDEOUT));
		hide(client.getWidget(InterfaceID.FairyringsLog.DIVIDER));
		Widget[] dynamic = contents.getDynamicChildren();
		if (dynamic != null)
		{
			for (Widget d : dynamic)
			{
				if (d != label)
				{
					hide(d);
				}
			}
		}

		moveTo(target, 0);
		moveTo(label, 0);
		moveTo(star, 0);
		Widget faves = client.getWidget(InterfaceID.FairyringsLog.FAVES);
		if (faves != null)
		{
			resize(faves, faveSlot >= 0 ? target.getHeight() + ROW_GAP : 0);
		}
		remember(contents);
		contents.setScrollHeight(0);
		contents.setScrollY(0);
		contents.revalidateScroll();
		remember(target);
		target.setTextColor(colour);
		if (label != null)
		{
			remember(label);
			label.setTextColor(colour);
		}
		filtered = code;
		return true;
	}

	/**
	 * The dynamic code label the game drew for an ordinary row: matched by its text ("A I Q"),
	 * falling back to the label sharing the row's y.
	 */
	private static Widget codeLabel(Widget contents, Widget row, String code)
	{
		Widget[] dynamic = contents.getDynamicChildren();
		if (dynamic == null)
		{
			return null;
		}
		Widget byY = null;
		for (Widget d : dynamic)
		{
			if (code.equals(DialMath.normalize(Text.removeTags(d.getText()))))
			{
				return d;
			}
			if (byY == null && d.getRelativeY() == row.getRelativeY())
			{
				byY = d;
			}
		}
		return byY;
	}

	/** Put back everything we changed. */
	public void restore()
	{
		for (RowState s : edits.values())
		{
			s.restore();
		}
		Widget contents = client.getWidget(InterfaceID.FairyringsLog.CONTENTS);
		if (contents != null && !edits.isEmpty())
		{
			contents.revalidateScroll();
		}
		edits.clear();
		filtered = null;
	}

	/**
	 * The game rebuilt the log (or closed it): our records describe widgets that changed, so they
	 * are dropped. The rebuild resets positions, sizes, scroll and hidden flags but never the row
	 * text colours, so only those are put back; otherwise the next filter would record our
	 * selection colour as the original.
	 */
	public void forget()
	{
		for (RowState s : edits.values())
		{
			s.w.setTextColor(s.textColor);
		}
		edits.clear();
		filtered = null;
	}

	private void hide(Widget w)
	{
		if (w == null || w.isSelfHidden())
		{
			return;
		}
		remember(w).hid = true;
		w.setHidden(true);
	}

	private void moveTo(Widget w, int y)
	{
		if (w == null)
		{
			return;
		}
		remember(w);
		w.setOriginalY(y);
		w.revalidate();
	}

	private void resize(Widget w, int h)
	{
		remember(w);
		w.setOriginalHeight(h);
		w.revalidate();
	}

	private RowState remember(Widget w)
	{
		return edits.computeIfAbsent(w, RowState::new);
	}

	/** A widget as it was before the filter first touched it. */
	private static final class RowState
	{
		final Widget w;
		/** Whether we hid it: only then do we unhide it, never a widget the game hid. */
		boolean hid;
		final int y;
		final int height;
		final int scrollHeight;
		final int scrollY;
		final int textColor;

		RowState(Widget w)
		{
			this.w = w;
			y = w.getOriginalY();
			height = w.getOriginalHeight();
			scrollHeight = w.getScrollHeight();
			scrollY = w.getScrollY();
			textColor = w.getTextColor();
		}

		void restore()
		{
			if (hid)
			{
				w.setHidden(false);
			}
			w.setOriginalY(y);
			w.setOriginalHeight(height);
			w.setScrollHeight(scrollHeight);
			w.setScrollY(scrollY);
			w.setTextColor(textColor);
			w.revalidate();
		}
	}
}
