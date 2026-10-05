/*
 * Copyright (c) 2026, Dewdul
 * All rights reserved.
 * Licensed under the BSD 2-Clause License. See LICENSE.
 */
package com.spirittreeatlas;

import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.Getter;
import lombok.Value;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetPositionMode;
import net.runelite.api.widgets.WidgetSizeMode;
import net.runelite.api.widgets.WidgetType;
import net.runelite.client.util.Text;

/**
 * The spirit tree's travel menu in either of the game's two menu interfaces (DESIGN 2.1-2.3, 3.3
 * and 4.3): recognising it by its title, reading its rows, and in Map mode hiding and moving its
 * components so that only the close button and the selected tree's real row show, in the map's
 * bottom-right corner, where that row is also made button-sized (the one resize, DESIGN 1 rule 4).
 * Every change is recorded and put back exactly, only while the widget is still the one we
 * changed; nothing we did not hide is ever shown, and the key-listener layers (the game's own
 * hotkeys) and every layer that holds them are never hidden.
 *
 * <p>Client thread only. Parsing ({@link #parseRow}, {@link #match}) and geometry
 * ({@link #modern}, {@link #classic}) are pure static methods.
 */
public class TreeMenu
{
	/** Margins of the Travel cell from the slot's right and bottom edges (DESIGN 4.3). */
	static final int RIGHT = 8;
	static final int BOTTOM = 6;
	/**
	 * The Travel button's size (DESIGN 4.3): the moved row is resized to this, within its scroll
	 * area (MODERN both ways; CLASSIC the height, and the cell is the middle of its centred line).
	 */
	static final int TRAVEL_W = 200;
	static final int TRAVEL_H = 32;
	/** The close buttons' size (sprites 535/537). */
	static final int CLOSE_W = 26;
	static final int CLOSE_H = 23;
	/**
	 * The gap between the close button and the cell below it: MODERN's, fixed by the title bar
	 * (the close button's bottom at UNIVERSE y 40, CONTENT_SCROLL's top at 52), which CLASSIC copies.
	 */
	static final int CLOSE_GAP = 12;
	/** MODERN: the close button's right end, this far inside TITLE's right end (script 9144). */
	static final int MODERN_CLOSE_INSET = 12;
	/** Classic: the parchment scroll's top-left in the slot, where List mode's Map button goes. */
	static final Point CLASSIC_SCROLL = new Point(55, 37);
	/** What the game writes into a row while its teleport is under way. */
	static final String PLEASE_WAIT = "Please wait...";
	/** menu_indexed: a numbered list built in the classic menu (187) by another setup script. */
	static final int MENU_INDEXED = 378;
	private static final Pattern SPACES = Pattern.compile("\\s+");

	/** The two menu interfaces, chosen by the game's "Modern menu interface" setting. */
	public enum Style
	{
		MODERN(InterfaceID.MENU_NEW, 9142),
		CLASSIC(InterfaceID.MENU, 217);

		@Getter
		private final int group;
		/** The server-run setup script; ScriptPostFired of it means the layout is final. */
		@Getter
		private final int script;

		Style(int group, int script)
		{
			this.group = group;
			this.script = script;
		}

		/**
		 * The style whose interface this setup script builds, or null. The classic menu's other
		 * builder, menu_indexed, counts too: it may replace our menu without closing it.
		 */
		public static Style forScript(int id)
		{
			return id == MODERN.script ? MODERN : id == CLASSIC.script || id == MENU_INDEXED ? CLASSIC : null;
		}

		/** The style whose interface this is, or null. */
		public static Style forGroup(int group)
		{
			return group == MODERN.group ? MODERN : group == CLASSIC.group ? CLASSIC : null;
		}

		/** The menu's root component, the one that fills the main modal slot (DESIGN 4.2). */
		public int root()
		{
			return this == MODERN ? InterfaceID.MenuNew.INFINITE : InterfaceID.Menu.LJ_LAYER2;
		}

		/** The layer whose dynamic children are the rows' text components. */
		int rowLayer()
		{
			return this == MODERN ? InterfaceID.MenuNew.TEXT : InterfaceID.Menu.LJ_LAYER1;
		}
	}

	/**
	 * One row of the menu as read (DESIGN 3.3). {@code index} is the row's dynamic child index
	 * (its subid); {@code key} its hotkey ("4", "C") or null; {@code label} the text after the key,
	 * tags stripped; {@code grey} whether the game lists it as unavailable; {@code treeId} the tree
	 * it maps to, or null (Cancel, unknown rows).
	 */
	@Value
	public static class Row
	{
		int index;
		String key;
		String label;
		boolean grey;
		String treeId;
	}

	/**
	 * Where Map mode puts things (DESIGN 4.3), all from live layout values. Slot-relative means
	 * relative to the menu's base (MODERN: INFINITE, which fills the slot; CLASSIC: the slot).
	 */
	@Value
	public static class Geometry
	{
		/** Where the moved root goes (MODERN UNIVERSE, CLASSIC LJ_LAYER1): slot-relative, ABSOLUTE_LEFT/TOP. */
		Point root;
		/** Where the shown row goes in its own layer (MODERN: x and y; CLASSIC: y only, x is kept). */
		Point row;
		/** The shown row's size (MODERN: both, absolute; CLASSIC: the height only, its width and width mode are kept). */
		Dimension rowSize;
		/** The Travel cell, slot-relative. */
		Rectangle cell;
		/** CLASSIC: where the close button goes, slot-relative; null for MODERN (it rides on UNIVERSE). */
		Point close;
	}

	/** One widget we changed: hidden by us, moved by us, or both. */
	private static final class Change
	{
		final Widget widget;
		/** The static component's id, or a dynamic child's parent's. */
		final int id;
		/** The dynamic child's index, or -1 for a static component. */
		final int index;
		boolean hidden;
		/** Position fields {x mode, y mode, x, y} as found and as last written; null when not moved. */
		int[] original;
		int[] written;
		/** Size fields {width mode, height mode, width, height}, likewise; null when not resized (only the Travel row is). */
		int[] originalSize;
		int[] writtenSize;

		Change(Widget widget, int id, int index)
		{
			this.widget = widget;
			this.id = id;
			this.index = index;
		}
	}

	private final Client client;
	/** The menu being tracked, or null. */
	@Getter
	private Style style;
	/** The rows as last read; empty until read. */
	@Getter
	private List<Row> rows = Collections.emptyList();
	/** The geometry of the last {@link #apply}; null until then. */
	@Getter
	private Geometry geometry;
	private final Map<String, Change> changes = new LinkedHashMap<>();
	/** The row texts {@link #rows} were parsed from (null for a row not listed), or null to parse afresh. */
	private List<String> texts;

	TreeMenu(Client client)
	{
		this.client = client;
	}

	// ------------------------------------------------------------------ recognising and reading

	/** Whether a style's interface is loaded and mounted in a slot that is not hidden. */
	boolean isOpen(Style s)
	{
		Widget slot = slot(s);
		return slot != null && !slot.isHidden();
	}

	/** The style whose interface is open now (MODERN first), or null. */
	Style openStyle()
	{
		return isOpen(Style.MODERN) ? Style.MODERN : isOpen(Style.CLASSIC) ? Style.CLASSIC : null;
	}

	/**
	 * The menu's title as the game shows it, tags stripped, or null: MODERN, the first text child
	 * of TITLE; CLASSIC, the first text child of LJ_LAYER2. Read from the widgets, never from the
	 * setup script's arguments (whose order is not proven).
	 */
	String title(Style s)
	{
		Widget parent = s == null ? null : client.getWidget(s == Style.MODERN ? InterfaceID.MenuNew.TITLE : InterfaceID.Menu.LJ_LAYER2);
		for (Widget c : children(parent))
		{
			if (c != null && c.getType() == WidgetType.TEXT)
			{
				return clean(c.getText());
			}
		}
		return null;
	}

	/** Whether a menu title is the spirit tree's ({@code expected}, from trees.json), ignoring case. */
	static boolean isTitle(String title, String expected)
	{
		return title != null && expected != null && title.trim().equalsIgnoreCase(expected.trim());
	}

	/** Starts tracking a menu (nothing is changed yet). */
	void open(Style s)
	{
		style = s;
		rows = Collections.emptyList();
		texts = null;
		geometry = null;
	}

	/**
	 * After the setup script ran (again): the rows are new components, so the records of the old
	 * ones are dropped (static components keep theirs), and the rows are read afresh.
	 */
	void rebuilt(List<Tree> trees, String grey)
	{
		changes.values().removeIf(c -> c.index >= 0);
		texts = null;
		read(trees, grey);
	}

	/**
	 * Reads the rows from the live widgets: cheap when nothing changed (the texts are compared
	 * first), so it runs every tick, because another plugin (Better Teleport Menu) may re-text a
	 * row, and so rebind its key, while the menu is open. A row the game or another plugin hid is
	 * not listed (Better Teleport Menu hides disabled ones); the rows we hid for the map are.
	 *
	 * @return whether the rows changed
	 */
	boolean read(List<Tree> trees, String grey)
	{
		Widget layer = style == null ? null : client.getWidget(style.rowLayer());
		Widget[] kids = layer == null ? null : layer.getChildren();
		List<String> now = new ArrayList<>();
		for (int i = 0; kids != null && i < kids.length; i++)
		{
			Widget w = kids[i];
			boolean listed = w != null && w.getType() == WidgetType.TEXT && (!w.isSelfHidden() || hiddenByUs(w, style.rowLayer(), i));
			now.add(listed ? w.getText() : null);
		}
		if (now.equals(texts))
		{
			return false;
		}
		texts = now;
		List<Row> next = parseRows(now, rows, trees, grey);
		boolean changed = !next.equals(rows);
		rows = next;
		return changed;
	}

	/**
	 * The row as the live widget shows it now (for {@link Scene#rowShown}): re-parsed, so a row
	 * re-texted since still maps right and "Please wait..." keeps its mapping; null when the
	 * widget is gone or hidden (by anyone).
	 */
	Row liveRow(Row row, List<Tree> trees, String grey)
	{
		Widget w = row == null ? null : rowWidget(row.getIndex());
		if (w == null || w.isHidden())
		{
			return null;
		}
		return parseRow(row.getIndex(), w.getText(), row, trees, grey);
	}

	// ------------------------------------------------------------------ parsing (pure)

	/** Rows from the text of each child of the row layer (null for children that are not text). */
	static List<Row> parseRows(List<String> texts, List<Row> previous, List<Tree> trees, String grey)
	{
		List<Row> out = new ArrayList<>();
		for (int i = 0; i < texts.size(); i++)
		{
			Row before = null;
			for (Row r : previous)
			{
				before = r.getIndex() == i ? r : before;
			}
			Row r = parseRow(i, texts.get(i), before, trees, grey);
			if (r != null)
			{
				out.add(r);
			}
		}
		return out;
	}

	/**
	 * One row (DESIGN 3.3): grey when {@code <col=grey>} follows the first ": "; tags stripped and
	 * whitespace collapsed; "Please wait..." keeps the row's previous mapping; the key is the text
	 * before the first ": " when that is 1-6 characters; the label is matched against the trees.
	 *
	 * @param previous this row as read before (same index), or null
	 * @param grey the unavailable colour, e.g. "5f5f5f"
	 * @return null for an empty row
	 */
	static Row parseRow(int index, String raw, Row previous, List<Tree> trees, String grey)
	{
		String text = clean(raw);
		if (text == null || text.isEmpty())
		{
			return null;
		}
		if (PLEASE_WAIT.equalsIgnoreCase(text))
		{
			return previous != null && previous.getIndex() == index ? previous : new Row(index, null, text, false, null);
		}
		String lower = raw.toLowerCase(Locale.ROOT);
		boolean isGrey = grey != null && lower.indexOf("<col=" + grey.toLowerCase(Locale.ROOT) + ">", Math.max(0, lower.indexOf(": "))) >= 0;
		String key = null;
		String label = text;
		int sep = text.indexOf(": ");
		if (sep >= 1 && sep <= 6)
		{
			key = text.substring(0, sep).trim();
			label = text.substring(sep + 2).trim();
		}
		Tree t = match(label, text, trees);
		return new Row(index, key, label, isGrey, t == null ? null : t.getId());
	}

	/**
	 * The tree a row names: an exact tree whose menu label equals the label, or a prefix tree whose
	 * menu label starts it (case-insensitive, whitespace collapsed). Failing that, the tree whose
	 * menu label occurs in the whole row text, longest first, then earliest (Better Teleport Menu's
	 * re-texted forms, such as a long hotkey before the name). Null for Cancel and unknown rows.
	 */
	static Tree match(String label, String whole, List<Tree> trees)
	{
		String l = norm(label);
		for (Tree t : trees)
		{
			String m = norm(t.getMenuLabel());
			if (!m.isEmpty() && (t.isPrefix() ? l.startsWith(m) : l.equals(m)))
			{
				return t;
			}
		}
		String w = norm(whole);
		Tree best = null;
		int bestLen = 0;
		int bestAt = Integer.MAX_VALUE;
		for (Tree t : trees)
		{
			String m = norm(t.getMenuLabel());
			int at = m.isEmpty() ? -1 : w.indexOf(m);
			if (at >= 0 && (m.length() > bestLen || (m.length() == bestLen && at < bestAt)))
			{
				best = t;
				bestLen = m.length();
				bestAt = at;
			}
		}
		return best;
	}

	/** Tags stripped, whitespace collapsed, trimmed; null stays null. */
	static String clean(String s)
	{
		return s == null ? null : SPACES.matcher(Text.removeTags(s)).replaceAll(" ").trim();
	}

	private static String norm(String s)
	{
		return s == null ? "" : SPACES.matcher(s).replaceAll(" ").trim().toLowerCase(Locale.ROOT);
	}

	// ------------------------------------------------------------------ geometry (pure)

	/**
	 * MODERN (DESIGN 4.3): the shown row becomes a {@link #TRAVEL_W} x {@link #TRAVEL_H} button
	 * (as much as fits in CONTENT_SCROLL) at the top of the scroll area, its right end under the
	 * close button's; UNIVERSE hangs below the slot so that only its title strip and that button
	 * show, in the slot's bottom-right corner, the close button (in the title strip) just above
	 * the button's right end.
	 *
	 * @param csX CONTENT_SCROLL's position relative to UNIVERSE (the sum of the relative positions
	 *     of CONTENT_SCROLL, CONTENT and CONTENT_FRAME); {@code csW} x {@code csH} its size
	 * @param closeRight the close button's right end relative to UNIVERSE, or 0 when unknown (the
	 *     button then ends at the scroll area's right end)
	 */
	static Geometry modern(int slotW, int slotH, int csX, int csY, int csW, int csH, int closeRight, int scrollX, int scrollY)
	{
		int cw = Math.min(csW, TRAVEL_W);
		int ch = Math.min(csH, TRAVEL_H);
		// UNIVERSE-relative right end of the button: under the close button's, inside the scroll area
		int right = closeRight > 0 ? Math.max(csX + cw, Math.min(csX + csW, closeRight)) : csX + csW;
		int ux = slotW - RIGHT - right;
		int uy = slotH - BOTTOM - (csY + ch);
		Rectangle cell = new Rectangle(ux + right - cw, uy + csY, cw, ch);
		return new Geometry(new Point(ux, uy), new Point(scrollX + right - csX - cw, scrollY), new Dimension(cw, ch), cell, null);
	}

	/**
	 * CLASSIC (DESIGN 4.3): the parchment model and scrollbar are hidden; LJ_LAYER1 hangs below
	 * the slot with the shown row moved to its top and made {@link #TRAVEL_H} tall (its full
	 * width kept); its text, centred both ways, shows in the middle {@link #TRAVEL_W} px (the
	 * cell); the close button goes just above the cell's right end.
	 *
	 * @param rw LJ_LAYER1's width (386), the rows' width; {@code listH} its height
	 */
	static Geometry classic(int slotW, int slotH, int rw, int listH, int scrollY)
	{
		int cw = Math.min(rw, TRAVEL_W);
		int ch = Math.min(listH, TRAVEL_H);
		int lx = slotW - RIGHT - cw - (rw - cw) / 2;
		int ly = slotH - BOTTOM - ch;
		Rectangle cell = new Rectangle(lx + (rw - cw) / 2, ly, cw, ch);
		return new Geometry(new Point(lx, ly), new Point(0, scrollY), new Dimension(rw, ch), cell,
			new Point(slotW - RIGHT - CLOSE_W, slotH - BOTTOM - ch - CLOSE_GAP - CLOSE_H));
	}

	// ------------------------------------------------------------------ Map mode changes

	/**
	 * Map mode (DESIGN 4.3): moves and hides the menu's components so only the close button and,
	 * when {@code shown} is set, that row show in the slot's bottom-right corner. Safe to call
	 * again at any time (after a rebuild, each tick, on a new selection): only what differs is
	 * written.
	 *
	 * @param shown the selected tree's row when it is to be the Travel button, else null
	 */
	void apply(Row shown)
	{
		// a row hidden by the game or another plugin is never shown, not even as the Travel row
		Widget w = shown == null ? null : rowWidget(shown.getIndex());
		Change c = w == null ? null : find(w, style.rowLayer(), shown.getIndex());
		Row s = w == null || (w.isSelfHidden() && (c == null || !c.hidden)) ? null : shown;
		if (style == Style.MODERN)
		{
			applyModern(s);
		}
		else if (style == Style.CLASSIC)
		{
			applyClassic(s);
		}
	}

	private void applyModern(Row shown)
	{
		Widget base = base();
		Widget universe = client.getWidget(InterfaceID.MenuNew.UNIVERSE);
		Widget frame = client.getWidget(InterfaceID.MenuNew.CONTENT_FRAME);
		Widget content = client.getWidget(InterfaceID.MenuNew.CONTENT);
		Widget scroll = client.getWidget(InterfaceID.MenuNew.CONTENT_SCROLL);
		Widget text = client.getWidget(InterfaceID.MenuNew.TEXT);
		Widget graphics = client.getWidget(InterfaceID.MenuNew.GRAPHICS);
		Widget title = client.getWidget(InterfaceID.MenuNew.TITLE);
		if (base == null || universe == null || frame == null || content == null || scroll == null || text == null || graphics == null
			|| text.getChild(0) == null)
		{
			return;
		}
		Geometry g = modern(base.getWidth(), base.getHeight(),
			frame.getRelativeX() + content.getRelativeX() + scroll.getRelativeX(),
			frame.getRelativeY() + content.getRelativeY() + scroll.getRelativeY(),
			scroll.getWidth(), scroll.getHeight(), title == null ? 0 : title.getRelativeX() + title.getWidth() - MODERN_CLOSE_INSET,
			scroll.getScrollX(), scroll.getScrollY());
		geometry = g;
		move(universe, InterfaceID.MenuNew.UNIVERSE, -1, WidgetPositionMode.ABSOLUTE_LEFT, WidgetPositionMode.ABSOLUTE_TOP, g.getRoot().x, g.getRoot().y);
		// both are absolute-sized rectangles (proc 9143): text over its black backing
		int[] size = {WidgetSizeMode.ABSOLUTE, WidgetSizeMode.ABSOLUTE, g.getRowSize().width, g.getRowSize().height};
		placeRows(text, InterfaceID.MenuNew.TEXT, shown, g.getRow(), true, size);
		placeRows(graphics, InterfaceID.MenuNew.GRAPHICS, shown, g.getRow(), true, size);
	}

	private void applyClassic(Row shown)
	{
		Widget base = base();
		Widget list = client.getWidget(InterfaceID.Menu.LJ_LAYER1);
		if (base == null || list == null)
		{
			return;
		}
		Geometry g = classic(base.getWidth(), base.getHeight(), list.getWidth(), list.getHeight(), list.getScrollY());
		geometry = g;
		// only the parchment model, never LJ_LAYER2 itself: it holds the key-listener layer (the
		// game's hotkeys) and the title, which Better Teleport Menu checks; the map covers both
		Widget layer2 = client.getWidget(InterfaceID.Menu.LJ_LAYER2);
		int parchment = parchment(layer2);
		if (parchment >= 0)
		{
			hide(layer2.getChild(parchment), InterfaceID.Menu.LJ_LAYER2, parchment);
		}
		hide(client.getWidget(InterfaceID.Menu.LJ_SCROLL_BAR), InterfaceID.Menu.LJ_SCROLL_BAR, -1);
		move(list, InterfaceID.Menu.LJ_LAYER1, -1, WidgetPositionMode.ABSOLUTE_LEFT, WidgetPositionMode.ABSOLUTE_TOP, g.getRoot().x, g.getRoot().y);
		// the row's width stays "LJ_LAYER1's minus 0" (proc 218); only its height is set
		placeRows(list, InterfaceID.Menu.LJ_LAYER1, shown, g.getRow(), false, new int[]{-1, WidgetSizeMode.ABSOLUTE, -1, g.getRowSize().height});
		move(client.getWidget(InterfaceID.Menu.ROOT_GRAPHIC3), InterfaceID.Menu.ROOT_GRAPHIC3, -1,
			WidgetPositionMode.ABSOLUTE_LEFT, WidgetPositionMode.ABSOLUTE_TOP, g.getClose().x, g.getClose().y);
	}

	/** The index of LJ_LAYER2's parchment scroll (proc 219's model child, 0 today), or -1. */
	private static int parchment(Widget layer2)
	{
		List<Widget> kids = children(layer2);
		for (int i = 0; i < kids.size(); i++)
		{
			if (kids.get(i) != null && kids.get(i).getType() == WidgetType.MODEL)
			{
				return i;
			}
		}
		return -1;
	}

	/**
	 * Hides every row of a layer but the shown one, which goes to the cell at the Travel button's
	 * size (shown again only if we hid it). A row that stops being the shown one goes back where
	 * it was, at its own size, before it is hidden.
	 *
	 * @param size the shown row's {width mode, height mode, width, height}; -1 keeps that field
	 */
	private void placeRows(Widget layer, int id, Row shown, Point at, boolean moveX, int[] size)
	{
		Widget[] kids = layer.getChildren();
		for (int i = 0; kids != null && i < kids.length; i++)
		{
			Widget w = kids[i];
			if (w == null)
			{
				continue;
			}
			if (shown != null && i == shown.getIndex())
			{
				unhide(w, id, i);
				int[] now = size(w);
				int[] target = new int[4];
				for (int k = 0; k < 4; k++)
				{
					target[k] = size[k] < 0 ? now[k] : size[k];
				}
				place(w, id, i, new int[]{moveX ? WidgetPositionMode.ABSOLUTE_LEFT : w.getXPositionMode(), WidgetPositionMode.ABSOLUTE_TOP,
					moveX ? at.x : w.getOriginalX(), at.y}, target);
			}
			else
			{
				Change c = find(w, id, i);
				if (c != null)
				{
					unmove(c);
				}
				hide(w, id, i);
			}
		}
	}

	/**
	 * Puts back everything we changed, exactly, and forgets it: a widget only while it is still
	 * the live one for its id; a hide only while the widget is still self-hidden; a move only while
	 * the widget still holds what we wrote (otherwise the game or someone else has moved it since).
	 */
	void restore()
	{
		for (Change c : changes.values())
		{
			if (!isLive(c))
			{
				continue;
			}
			if (c.hidden && c.widget.isSelfHidden())
			{
				c.widget.setHidden(false);
			}
			unmove(c);
		}
		changes.clear();
		geometry = null;
	}

	/** Restores and stops tracking. */
	void close()
	{
		restore();
		style = null;
		rows = Collections.emptyList();
		texts = null;
	}

	/** Whether any change of ours is recorded. */
	boolean isChanged()
	{
		return !changes.isEmpty();
	}

	private void hide(Widget w, int id, int index)
	{
		if (w != null && !w.isSelfHidden())
		{
			w.setHidden(true);
			record(w, id, index).hidden = true;
		}
	}

	/** Shows a widget again, only if we hid it. */
	private void unhide(Widget w, int id, int index)
	{
		Change c = find(w, id, index);
		if (c != null && c.hidden)
		{
			c.hidden = false;
			if (w.isSelfHidden())
			{
				w.setHidden(false);
			}
		}
	}

	/** Moves a widget (position modes and x/y), keeping its size. */
	private void move(Widget w, int id, int index, int xMode, int yMode, int x, int y)
	{
		if (w != null)
		{
			place(w, id, index, new int[]{xMode, yMode, x, y}, null);
		}
	}

	/**
	 * Moves a widget and, for the Travel row only, resizes it (DESIGN 1 rule 4: the one resize).
	 * What it held is recorded the first time, and again whenever it no longer holds what we wrote
	 * (the game laid it out afresh); position and size are recorded apart.
	 *
	 * @param size {width mode, height mode, width, height}, or null to keep the size
	 */
	private void place(Widget w, int id, int index, int[] position, int[] size)
	{
		Change c = record(w, id, index);
		int[] now = position(w);
		if (c.written == null || !Arrays.equals(now, c.written))
		{
			c.original = now;
		}
		boolean changed = !Arrays.equals(now, position);
		if (changed)
		{
			write(w, position);
		}
		c.written = position;
		if (size != null)
		{
			int[] was = size(w);
			if (c.writtenSize == null || !Arrays.equals(was, c.writtenSize))
			{
				c.originalSize = was;
			}
			if (!Arrays.equals(was, size))
			{
				writeSize(w, size);
				changed = true;
			}
			c.writtenSize = size;
		}
		if (changed)
		{
			w.revalidate();
		}
	}

	/**
	 * Puts a moved (and resized) widget back: its position if it still holds the position we
	 * wrote, its size if it still holds the size we wrote. Both are forgotten either way.
	 */
	private static void unmove(Change c)
	{
		boolean changed = false;
		if (c.written != null && Arrays.equals(position(c.widget), c.written) && !Arrays.equals(c.original, c.written))
		{
			write(c.widget, c.original);
			changed = true;
		}
		if (c.writtenSize != null && Arrays.equals(size(c.widget), c.writtenSize) && !Arrays.equals(c.originalSize, c.writtenSize))
		{
			writeSize(c.widget, c.originalSize);
			changed = true;
		}
		if (changed)
		{
			c.widget.revalidate();
		}
		c.original = null;
		c.written = null;
		c.originalSize = null;
		c.writtenSize = null;
	}

	/** Whether we hid this widget (and it is the one we hid); unlike {@link #find}, changes no record. */
	private boolean hiddenByUs(Widget w, int id, int index)
	{
		Change c = changes.get(id + "/" + index);
		return c != null && c.widget == w && c.hidden;
	}

	/** The record for this live widget, or null; a record left over from an older widget is dropped. */
	private Change find(Widget w, int id, int index)
	{
		String k = id + "/" + index;
		Change c = changes.get(k);
		if (c != null && c.widget != w)
		{
			changes.remove(k);
			return null;
		}
		return c;
	}

	private Change record(Widget w, int id, int index)
	{
		Change c = find(w, id, index);
		if (c == null)
		{
			c = new Change(w, id, index);
			changes.put(id + "/" + index, c);
		}
		return c;
	}

	private boolean isLive(Change c)
	{
		Widget w = client.getWidget(c.id);
		if (w != null && c.index >= 0)
		{
			w = w.getChild(c.index);
		}
		return w == c.widget;
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

	private static int[] size(Widget w)
	{
		return new int[]{w.getWidthMode(), w.getHeightMode(), w.getOriginalWidth(), w.getOriginalHeight()};
	}

	/** The only widget resize in the plugin: the Travel row's, and its exact restore (DESIGN 1 rule 4). */
	private static void writeSize(Widget w, int[] s)
	{
		w.setWidthMode(s[0]);
		w.setHeightMode(s[1]);
		w.setOriginalWidth(s[2]);
		w.setOriginalHeight(s[3]);
	}

	// ------------------------------------------------------------------ places on screen

	/** The main modal slot holding the menu (its bounds are the 512x334 "menu rect"), or null. */
	Widget slot()
	{
		return slot(style);
	}

	private Widget slot(Style s)
	{
		Widget root = s == null ? null : client.getWidget(s.root());
		return root == null ? null : root.getParent();
	}

	/** What the geometry is relative to: MODERN INFINITE (UNIVERSE's parent, filling the slot); CLASSIC the slot. */
	private Widget base()
	{
		return style == Style.MODERN ? client.getWidget(InterfaceID.MenuNew.INFINITE) : slot();
	}

	/** The slot's canvas bounds, or null when not laid out. */
	Rectangle slotBounds()
	{
		return bounds(slot());
	}

	/** A row's text component, fetched live by index. */
	Widget rowWidget(int index)
	{
		Widget layer = style == null ? null : client.getWidget(style.rowLayer());
		return layer == null || index < 0 ? null : layer.getChild(index);
	}

	/**
	 * The Travel cell in canvas coordinates (shown or covered), or null before the first
	 * {@link #apply}. While a row is shown there, intersected with its live bounds.
	 */
	Rectangle rowCell(Row shown)
	{
		Geometry g = geometry;
		Rectangle b = bounds(base());
		if (g == null || b == null)
		{
			return null;
		}
		Rectangle cell = new Rectangle(b.x + g.getCell().x, b.y + g.getCell().y, g.getCell().width, g.getCell().height);
		Widget w = shown == null ? null : rowWidget(shown.getIndex());
		Rectangle r = w == null || w.isHidden() ? null : bounds(w);
		return r != null && r.intersects(cell) ? cell.intersection(r) : cell;
	}

	/** The close button's live canvas bounds (MODERN: found in TITLE by type and its "Close" op), or null. */
	Rectangle closeRect()
	{
		Widget w = null;
		if (style == Style.MODERN)
		{
			for (Widget c : children(client.getWidget(InterfaceID.MenuNew.TITLE)))
			{
				if (w == null && c != null && c.getType() == WidgetType.GRAPHIC && c.getActions() != null
					&& Arrays.asList(c.getActions()).contains("Close"))
				{
					w = c;
				}
			}
		}
		else if (style == Style.CLASSIC)
		{
			w = client.getWidget(InterfaceID.Menu.ROOT_GRAPHIC3);
		}
		return w == null || w.isHidden() ? null : bounds(w);
	}

	/**
	 * The plain menu's top-left corner in canvas coordinates, where List mode's Map button and the
	 * step-aside notice go: MODERN UNIVERSE's, CLASSIC the parchment scroll's (slot (55, 37)).
	 */
	Point anchor()
	{
		if (style == Style.MODERN)
		{
			Rectangle u = bounds(client.getWidget(InterfaceID.MenuNew.UNIVERSE));
			if (u != null)
			{
				return u.getLocation();
			}
		}
		Rectangle s = slotBounds();
		return s == null ? null : new Point(s.x + CLASSIC_SCROLL.x, s.y + CLASSIC_SCROLL.y);
	}

	private static Rectangle bounds(Widget w)
	{
		Rectangle r = w == null ? null : w.getBounds();
		return r == null || r.width <= 0 || r.height <= 0 ? null : r;
	}

	private static List<Widget> children(Widget w)
	{
		Widget[] kids = w == null ? null : w.getChildren();
		return kids == null ? Collections.emptyList() : Arrays.asList(kids);
	}
}
