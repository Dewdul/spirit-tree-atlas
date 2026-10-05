/*
 * Copyright (c) 2026, Dewdul
 * All rights reserved.
 * Licensed under the BSD 2-Clause License. See LICENSE.
 */
package com.spirittreeatlas;

import java.awt.Rectangle;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetPositionMode;
import net.runelite.api.widgets.WidgetSizeMode;
import net.runelite.api.widgets.WidgetType;

/**
 * Both spirit tree menus as plain-field widgets behind a fake client, laid out the way the cache
 * definitions and the setup scripts lay them out (DESIGN 2.2, 2.3): enough for TreeMenu to
 * recognise, read, change and restore them, and for tests to see every write (and every resize:
 * only the Travel row may be resized, DESIGN 1 rule 4).
 */
final class FakeMenu
{
	static final String TITLE = "Spirit Tree Locations";
	/** The menu's options in today's order (DESIGN 2.4). */
	static final String[] OPTIONS = {
		"Tree Gnome Village", "Gnome Stronghold", "Battlefield of Khazard", "Grand Exchange", "Feldip Hills", "Prifddinas",
		"Port Sarim", "Etceteria", "Brimhaven", "Hosidius", "Farming Guild", "Your house (Rimmington)", "Poison Waste",
		"Laguna Aurorae", "Cancel",
	};
	static final String KEYS = "123456789ABCDEFGHIJ";
	/** Where the slot is on the canvas. */
	static final Rectangle SLOT = new Rectangle(100, 200, 512, 334);

	/** A widget backed by plain fields; positions are laid out against the parent on revalidate. */
	static final class W
	{
		final int id;
		final int index;
		final int type;
		W parent;
		W[] children = new W[0];
		String text;
		String[] actions;
		boolean hidden;
		int xMode = WidgetPositionMode.ABSOLUTE_LEFT;
		int yMode = WidgetPositionMode.ABSOLUTE_TOP;
		int x;
		int y;
		int wMode = WidgetSizeMode.ABSOLUTE;
		int hMode = WidgetSizeMode.ABSOLUTE;
		/** The size fields as set (absolute: the size; minus: what is taken off the parent's). */
		int ow;
		int oh;
		/** The size as laid out from the size fields. */
		int w;
		int h;
		int relX;
		int relY;
		int scrollX;
		int scrollY;
		/** Canvas bounds of a widget without a parent (the slot). */
		Rectangle bounds;
		/** Every setter call made on it. */
		int writes;
		/** The size setter calls among them. */
		int resizes;
		Widget proxy;

		W(int id, int index, int type)
		{
			this.id = id;
			this.index = index;
			this.type = type;
		}

		/** Lays out its size, then its position, against the parent (a widget without one keeps its fields as its size). */
		void layOut()
		{
			w = wMode == WidgetSizeMode.MINUS && parent != null ? parent.w - ow : ow;
			h = hMode == WidgetSizeMode.MINUS && parent != null ? parent.h - oh : oh;
			int pw = parent == null ? w : parent.w;
			int ph = parent == null ? h : parent.h;
			relX = xMode == WidgetPositionMode.ABSOLUTE_CENTER ? (pw - w) / 2 + x : xMode == WidgetPositionMode.ABSOLUTE_RIGHT ? pw - w - x : x;
			relY = yMode == WidgetPositionMode.ABSOLUTE_CENTER ? (ph - h) / 2 + y : yMode == WidgetPositionMode.ABSOLUTE_BOTTOM ? ph - h - y : y;
		}

		boolean isHidden()
		{
			return hidden || (parent != null && parent.isHidden());
		}

		Rectangle canvas()
		{
			if (parent == null)
			{
				return new Rectangle(bounds);
			}
			Rectangle p = parent.canvas();
			return new Rectangle(p.x + relX - parent.scrollX, p.y + relY - parent.scrollY, w, h);
		}

		int[] position()
		{
			return new int[]{xMode, yMode, x, y};
		}

		int[] size()
		{
			return new int[]{wMode, hMode, ow, oh};
		}
	}

	final Map<Integer, W> live = new HashMap<>();
	final Client client;
	final W slot;
	/** What the fake client reports for the plugin: the tick count and the game's mouse-over text. */
	int tick = 100;
	boolean mouseover = true;

	FakeMenu()
	{
		slot = new W(InterfaceID.ToplevelOsrsStretch.MAINMODAL, -1, WidgetType.LAYER);
		slot.bounds = new Rectangle(SLOT);
		slot.w = slot.ow = SLOT.width;
		slot.h = slot.oh = SLOT.height;
		proxy(slot);
		client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class}, (p, m, args) ->
		{
			switch (m.getName())
			{
				case "getWidget":
					W f = args.length == 1 ? live.get((int) args[0]) : null;
					return f == null ? null : f.proxy;
				case "isClientThread":
					return true;
				case "getTickCount":
					return tick;
				case "getVarbitValue":
					return 0;
				case "isResized":
				case "isMenuOpen":
					return false;
				case "isMouseoverTextEnabled":
					return mouseover;
				case "setMouseoverTextEnabled":
					mouseover = (boolean) args[0];
					return null;
				case "equals":
					return p == args[0];
				case "hashCode":
					return System.identityHashCode(p);
				default:
					return null;
			}
		});
	}

	/** A static component, laid out in its parent. */
	W add(int id, W parent, int xMode, int yMode, int x, int y, int w, int h)
	{
		W c = new W(id, -1, WidgetType.LAYER);
		c.parent = parent;
		c.xMode = xMode;
		c.yMode = yMode;
		c.x = x;
		c.y = y;
		c.w = c.ow = w;
		c.h = c.oh = h;
		c.layOut();
		proxy(c);
		live.put(id, c);
		return c;
	}

	/** A dynamic child at index i of parent, at (x, y) from the parent's top-left. */
	W child(W parent, int i, int type, int x, int y, int w, int h, String text)
	{
		W c = new W(parent.id, i, type);
		c.parent = parent;
		c.x = x;
		c.y = y;
		c.w = c.ow = w;
		c.h = c.oh = h;
		c.text = text;
		c.layOut();
		proxy(c);
		W[] kids = parent.children.length > i ? parent.children : java.util.Arrays.copyOf(parent.children, i + 1);
		kids[i] = c;
		parent.children = kids;
		return c;
	}

	static String modernRow(int i, String option)
	{
		return "<col=ffffff>" + KEYS.charAt(i) + "</col>: " + option;
	}

	static String classicRow(int i, String option)
	{
		return "<col=735a28>" + KEYS.charAt(i) + "</col>: " + option;
	}

	/**
	 * The modern menu (947) after script 9142 for these options: entries 161x20, two balanced
	 * columns of ceil(n/2) rows, UNIVERSE (16 + 2*161) x (58 + rows*20), centred in INFINITE.
	 */
	static FakeMenu modern(String[] options)
	{
		FakeMenu f = new FakeMenu();
		int n = options.length;
		int rows = (n + 1) / 2;
		int uw = 16 + 2 * 161;
		int uh = 58 + rows * 20;
		int c = WidgetPositionMode.ABSOLUTE_CENTER;
		int l = WidgetPositionMode.ABSOLUTE_LEFT;
		int b = WidgetPositionMode.ABSOLUTE_BOTTOM;
		W infinite = f.add(InterfaceID.MenuNew.INFINITE, f.slot, c, c, 0, 0, 512, 334);
		W universe = f.add(InterfaceID.MenuNew.UNIVERSE, infinite, c, c, 0, 0, uw, uh);
		f.add(InterfaceID.MenuNew.FRAME, universe, l, l, 0, 0, uw, uh);
		W title = f.add(InterfaceID.MenuNew.TITLE, universe, c, l, 0, 6, uw - 12, 45);
		W frame = f.add(InterfaceID.MenuNew.CONTENT_FRAME, universe, c, b, 0, 6, uw - 12, uh - 56);
		W content = f.add(InterfaceID.MenuNew.CONTENT, frame, c, l, 0, 2, frame.w - 4, frame.h - 2);
		W keys = f.add(InterfaceID.MenuNew.KEYLISTENERS, content, l, l, 0, 0, 1, 1);
		W scroll = f.add(InterfaceID.MenuNew.CONTENT_SCROLL, content, l, l, 0, 0, content.w, content.h);
		W graphics = f.add(InterfaceID.MenuNew.GRAPHICS, scroll, l, l, 0, 0, scroll.w, scroll.h);
		W text = f.add(InterfaceID.MenuNew.TEXT, scroll, l, l, 0, 0, scroll.w, scroll.h);
		f.add(InterfaceID.MenuNew.SCROLLBAR, content, c, b, 0, 2, content.w, 0);
		// the title bar (script 9144): two thinbox rectangles, the graphic, the title, the close button
		f.child(title, 0, WidgetType.RECTANGLE, 0, 0, title.w, title.h, null);
		f.child(title, 1, WidgetType.RECTANGLE, 1, 1, title.w - 2, title.h - 2, null);
		f.child(title, 2, WidgetType.GRAPHIC, 10, 10, 0, 0, null);
		f.child(title, 3, WidgetType.TEXT, 36, 0, title.w - 72, title.h, "<col=ff981f>" + TITLE);
		W close = f.child(title, 4, WidgetType.GRAPHIC, 12, 0, 26, 23, null);
		close.xMode = WidgetPositionMode.ABSOLUTE_RIGHT;
		close.yMode = WidgetPositionMode.ABSOLUTE_CENTER;
		close.actions = new String[]{"Close"};
		close.layOut();
		for (int i = 0; i < n; i++)
		{
			int ex = (i / rows) * 161;
			int ey = (i % rows) * 20;
			f.child(graphics, i, WidgetType.RECTANGLE, ex, ey, 161, 20, null);
			f.child(text, i, WidgetType.TEXT, ex, ey, 161, 20, modernRow(i, options[i]));
			f.child(keys, i, WidgetType.RECTANGLE, 0, 0, 1, 1, null);
		}
		return f;
	}

	/** The classic menu (187) after script 217 / proc 219: rows 386x16 in a 386x232 scroll layer. */
	static FakeMenu classic(String[] options)
	{
		FakeMenu f = new FakeMenu();
		int l = WidgetPositionMode.ABSOLUTE_LEFT;
		W layer2 = f.add(InterfaceID.Menu.LJ_LAYER2, f.slot, l, l, 0, 0, 512, 334);
		// the key-listener layer is a static child of LJ_LAYER2, not a root (cache: 187:1's parent is 187:0)
		W keys = f.add(InterfaceID.Menu.KEYLISTENERS, layer2, l, l, 0, 0, 1, 1);
		f.parchment(layer2, TITLE);
		f.add(InterfaceID.Menu.LJ_SCROLL_BAR, f.slot, l, l, 441, 70, 16, 232);
		W list = f.add(InterfaceID.Menu.LJ_LAYER1, f.slot, l, l, 55, 70, 386, 232);
		W close = f.add(InterfaceID.Menu.ROOT_GRAPHIC3, f.slot, l, l, 449, 36, 26, 23);
		close.actions = new String[]{"Close"};
		for (int i = 0; i < options.length; i++)
		{
			// proc 218: centre-anchored, LJ_LAYER1's width minus 0, 16 px tall
			W row = f.child(list, i, WidgetType.TEXT, 0, 16 * i, 0, 16, classicRow(i, options[i]));
			row.xMode = WidgetPositionMode.ABSOLUTE_CENTER;
			row.wMode = WidgetSizeMode.MINUS;
			row.layOut();
			f.child(keys, i, WidgetType.RECTANGLE, 0, 0, 0, 0, null);
		}
		return f;
	}

	/** Proc 219's dynamic children of LJ_LAYER2: 0 the parchment scroll model, 1 the title text. */
	private void parchment(W layer2, String title)
	{
		child(layer2, 0, WidgetType.MODEL, 0, 0, 512, 334, null);
		child(layer2, 1, WidgetType.TEXT, 67, 37, 358, 33, title);
	}

	W get(int id)
	{
		return live.get(id);
	}

	/**
	 * The setup script running again in the open menu: cc_deleteall, then new rows (new widget
	 * objects) for these options; the classic proc 219 also puts the list back in its place.
	 */
	void rebuild(String[] options)
	{
		boolean modern = live.containsKey(InterfaceID.MenuNew.TEXT);
		FakeMenu fresh = modern ? modern(options) : classic(options);
		int[] layers = modern ? new int[]{InterfaceID.MenuNew.TEXT, InterfaceID.MenuNew.GRAPHICS} : new int[]{InterfaceID.Menu.LJ_LAYER1};
		for (int id : layers)
		{
			W layer = get(id);
			layer.children = new W[0];
			for (W c : fresh.get(id).children)
			{
				W row = child(layer, c.index, c.type, c.x, c.y, c.ow, c.oh, c.text);
				row.xMode = c.xMode;
				row.wMode = c.wMode;
				row.hMode = c.hMode;
				row.layOut();
			}
		}
		if (!modern)
		{
			// proc 219: cc_deleteall on LJ_LAYER2 (its static key-listener layer stays), a new parchment and title
			W layer2 = get(InterfaceID.Menu.LJ_LAYER2);
			String title = layer2.children[1].text;
			layer2.children = new W[0];
			parchment(layer2, title);
			W list = get(InterfaceID.Menu.LJ_LAYER1);
			list.xMode = WidgetPositionMode.ABSOLUTE_LEFT;
			list.yMode = WidgetPositionMode.ABSOLUTE_TOP;
			list.x = 55;
			list.y = 70;
			list.layOut();
		}
	}

	/** The classic parchment scroll model (LJ_LAYER2 child 0). */
	W parchmentModel()
	{
		return get(InterfaceID.Menu.LJ_LAYER2).children[0];
	}

	/**
	 * Hard rule 4: why the game's hotkeys would not work now, or null when they would. The client
	 * skips a hidden component's whole subtree, so no ancestor of a key-listener layer may be
	 * self-hidden, and every key-listener child must be effectively visible.
	 */
	String hotkeysBlocked()
	{
		for (int id : new int[]{InterfaceID.MenuNew.KEYLISTENERS, InterfaceID.Menu.KEYLISTENERS})
		{
			W keys = get(id);
			if (keys == null)
			{
				continue;
			}
			for (W a = keys; a != null; a = a.parent)
			{
				if (a.hidden)
				{
					return "component " + a.id + " holding key listeners " + id + " is hidden";
				}
			}
			if (keys.children.length == 0)
			{
				return "no key listeners under " + id;
			}
			for (W c : keys.children)
			{
				if (c == null || c.isHidden())
				{
					return "key listener " + id + "/" + (c == null ? "?" : c.index) + " is hidden";
				}
			}
		}
		return null;
	}

	/** The title text widget (modern: TITLE child 3; classic: LJ_LAYER2 child 1). */
	W title()
	{
		W modern = get(InterfaceID.MenuNew.TITLE);
		return modern != null ? modern.children[3] : get(InterfaceID.Menu.LJ_LAYER2).children[1];
	}

	/** Every widget of the menu, static and dynamic. */
	List<W> all()
	{
		List<W> out = new ArrayList<>();
		for (W w : live.values())
		{
			out.add(w);
			for (W c : w.children)
			{
				if (c != null)
				{
					out.add(c);
				}
			}
		}
		return out;
	}

	private void proxy(W f)
	{
		f.proxy = (Widget) Proxy.newProxyInstance(Widget.class.getClassLoader(), new Class<?>[]{Widget.class}, (p, m, args) ->
		{
			switch (m.getName())
			{
				case "equals":
					return p == args[0];
				case "hashCode":
					return System.identityHashCode(p);
				case "getId":
					return f.id;
				case "getIndex":
					return f.index;
				case "getType":
					return f.type;
				case "getText":
					return f.text;
				case "getActions":
					return f.actions;
				case "getParent":
					return f.parent == null ? null : f.parent.proxy;
				case "getChildren":
					Widget[] kids = new Widget[f.children.length];
					for (int i = 0; i < kids.length; i++)
					{
						kids[i] = f.children[i] == null ? null : f.children[i].proxy;
					}
					return kids;
				case "getChild":
					int i = (int) args[0];
					return i >= 0 && i < f.children.length && f.children[i] != null ? f.children[i].proxy : null;
				case "isHidden":
					return f.isHidden();
				case "isSelfHidden":
					return f.hidden;
				case "setHidden":
					f.writes++;
					f.hidden = (boolean) args[0];
					return p;
				case "getXPositionMode":
					return f.xMode;
				case "getYPositionMode":
					return f.yMode;
				case "getOriginalX":
					return f.x;
				case "getOriginalY":
					return f.y;
				case "setXPositionMode":
					f.writes++;
					f.xMode = (int) args[0];
					return p;
				case "setYPositionMode":
					f.writes++;
					f.yMode = (int) args[0];
					return p;
				case "setOriginalX":
					f.writes++;
					f.x = (int) args[0];
					return p;
				case "setOriginalY":
					f.writes++;
					f.y = (int) args[0];
					return p;
				case "revalidate":
					f.writes++;
					f.layOut();
					return null;
				case "getRelativeX":
					return f.relX;
				case "getRelativeY":
					return f.relY;
				case "getWidth":
					return f.w;
				case "getHeight":
					return f.h;
				case "getOriginalWidth":
					return f.ow;
				case "getOriginalHeight":
					return f.oh;
				case "getWidthMode":
					return f.wMode;
				case "getHeightMode":
					return f.hMode;
				case "setWidthMode":
				case "setHeightMode":
				case "setOriginalWidth":
				case "setOriginalHeight":
					f.writes++;
					f.resizes++;
					int v = (int) args[0];
					if (m.getName().equals("setWidthMode"))
					{
						f.wMode = v;
					}
					else if (m.getName().equals("setHeightMode"))
					{
						f.hMode = v;
					}
					else if (m.getName().equals("setOriginalWidth"))
					{
						f.ow = v;
					}
					else
					{
						f.oh = v;
					}
					return p;
				case "getScrollX":
					return f.scrollX;
				case "getScrollY":
					return f.scrollY;
				case "getBounds":
					return f.canvas();
				default:
					// any other call (a re-text, a restyle, a new listener) would be a hard-rule breach
					throw new AssertionError("unexpected widget call " + m.getName());
			}
		});
	}
}
