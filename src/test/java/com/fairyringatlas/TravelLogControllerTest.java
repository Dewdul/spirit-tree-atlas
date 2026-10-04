/*
 * Copyright (c) 2026, Dewdul
 * All rights reserved.
 * Licensed under the BSD 2-Clause License. See LICENSE.
 */
package com.fairyringatlas;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;

/** The travel log filter against a fake log: three ordinary rows with code labels, and one favourite. */
public class TravelLogControllerTest
{
	private static final int ROW_COLOUR = 0xff981f;
	private static final int LABEL_COLOUR = 0xff3f3f;
	private static final int SELECTED = 0x00ff00;

	/** A widget backed by a map of properties; only what the controller touches. */
	private static final class Fake
	{
		final Map<String, Object> p = new HashMap<>();
		Widget proxy;

		Object get(String k, Object def)
		{
			return p.getOrDefault(k, def);
		}
	}

	private final Map<Integer, Fake> widgets = new HashMap<>();
	private final List<Widget> labels = new ArrayList<>();
	private final Map<String, int[]> logComponents = new LinkedHashMap<>();
	private final Map<String, Ring> rings = new HashMap<>();
	private Fake contents;
	private TravelLogController log;

	private Fake widget(int id)
	{
		Fake f = new Fake();
		f.proxy = (Widget) Proxy.newProxyInstance(Widget.class.getClassLoader(), new Class<?>[]{Widget.class}, (proxy, m, args) ->
		{
			String n = m.getName();
			switch (n)
			{
				case "equals":
					return proxy == args[0];
				case "hashCode":
					return System.identityHashCode(proxy);
				case "toString":
					return "Fake" + f.p;
				case "isHidden":
				case "isSelfHidden":
					return f.get("hidden", false);
				case "getText":
					return f.get("text", "");
				case "getDynamicChildren":
					return f.get("children", new Widget[0]);
				case "getRelativeY":
				case "getOriginalY":
					return f.get("y", 0);
				case "getHeight":
				case "getOriginalHeight":
					return f.get("height", 15);
				case "getScrollHeight":
					return f.get("scrollHeight", 0);
				case "getScrollY":
					return f.get("scrollY", 0);
				case "getTextColor":
					return f.get("colour", 0);
				case "setHidden":
					f.p.put("hidden", args[0]);
					return proxy;
				case "setOriginalY":
					f.p.put("y", args[0]);
					return proxy;
				case "setOriginalHeight":
					f.p.put("height", args[0]);
					return proxy;
				case "setScrollHeight":
					f.p.put("scrollHeight", args[0]);
					return proxy;
				case "setScrollY":
					f.p.put("scrollY", args[0]);
					return proxy;
				case "setTextColor":
					f.p.put("colour", args[0]);
					return proxy;
				default:
					return m.getReturnType() == Widget.class ? proxy : null;
			}
		});
		if (id != 0)
		{
			widgets.put(id, f);
		}
		return f;
	}

	/** An ordinary row with its star and its dynamic code label, at row index i. */
	private void addRow(String code, int i)
	{
		int rowId = 1000 + i;
		int starId = 2000 + i;
		Fake row = widget(rowId);
		row.p.put("text", code + " place");
		row.p.put("y", i * 18);
		row.p.put("colour", ROW_COLOUR);
		widget(starId).p.put("y", i * 18);
		Fake label = widget(0);
		label.p.put("text", DialMath.spaced(code));
		label.p.put("y", i * 18);
		label.p.put("colour", LABEL_COLOUR);
		labels.add(label.proxy);
		logComponents.put(code, new int[]{rowId, starId});
		Ring r = new Ring(code, code, 3000, 3000, Layer.SURFACE);
		r.setRowComponent(rowId);
		r.setStarComponent(starId);
		rings.put(code, r);
	}

	@Before
	public void setUp()
	{
		contents = widget(InterfaceID.FairyringsLog.CONTENTS);
		contents.p.put("scrollHeight", 400);
		contents.p.put("scrollY", 40);
		addRow("AIQ", 0);
		addRow("CKS", 1);
		addRow("DJR", 2);
		contents.p.put("children", labels.toArray(new Widget[0]));
		Fake fave = widget(TravelLogController.FAVE_ROWS[0]);
		fave.p.put("text", "BKR place");
		fave.p.put("colour", ROW_COLOUR);
		widget(TravelLogController.FAVE_CODES[0]).p.put("colour", LABEL_COLOUR);
		widget(TravelLogController.FAVE_ICONS[0]);
		rings.put("BKR", new Ring("BKR", "BKR", 3000, 3000, Layer.SURFACE));
		Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class}, (proxy, m, args) ->
		{
			if (m.getName().equals("getWidget") && args != null && args.length == 1)
			{
				Fake f = widgets.get((Integer) args[0]);
				return f == null ? null : f.proxy;
			}
			if (m.getName().equals("hashCode"))
			{
				return 1;
			}
			return null;
		});
		log = new TravelLogController(client);
	}

	private boolean hidden(String code)
	{
		return (boolean) widgets.get(logComponents.get(code)[0]).get("hidden", false);
	}

	private boolean apply(String code, int slot)
	{
		return log.apply(rings.get(code), slot, logComponents, SELECTED);
	}

	@Test
	public void filtersToOneRowAndRestoresExactly()
	{
		assertTrue(apply("CKS", -1));
		assertFalse(hidden("CKS"));
		assertTrue(hidden("AIQ"));
		assertTrue(hidden("DJR"));
		assertEquals(0, widgets.get(1001).get("y", -1));
		assertEquals(SELECTED, widgets.get(1001).get("colour", 0));
		log.restore();
		assertFalse(hidden("AIQ") || hidden("CKS") || hidden("DJR"));
		assertEquals(18, widgets.get(1001).get("y", -1));
		assertEquals(ROW_COLOUR, widgets.get(1001).get("colour", 0));
		assertEquals(40, contents.get("scrollY", 0));
	}

	@Test
	public void selectingASecondRingRefilters()
	{
		assertTrue(apply("AIQ", -1));
		// the AIQ filter hid CKS; the new filter must still find it
		assertTrue(apply("CKS", -1));
		assertTrue(log.isFiltered());
		assertFalse(hidden("CKS"));
		assertTrue(hidden("AIQ"));
		assertTrue(hidden("DJR"));
		assertEquals(0, widgets.get(1001).get("y", -1));
		// and from an ordinary row to a favourite, whose row the first filter hid
		assertTrue(apply("BKR", 0));
		assertFalse((boolean) widgets.get(TravelLogController.FAVE_ROWS[0]).get("hidden", false));
		assertTrue(hidden("CKS"));
		log.restore();
		assertEquals(18, widgets.get(1001).get("y", -1));
		assertEquals(ROW_COLOUR, widgets.get(1001).get("colour", 0));
	}

	@Test
	public void rebuildDoesNotRecordTheSelectionColourAsOriginal()
	{
		assertTrue(apply("BKR", 0));
		Fake code = widgets.get(TravelLogController.FAVE_CODES[0]);
		assertEquals(SELECTED, code.get("colour", 0));
		// proc 8080 rebuilds: it resets layout and hidden flags, never colours
		log.forget();
		for (Fake f : widgets.values())
		{
			f.p.put("hidden", false);
		}
		assertTrue(apply("BKR", 0));
		log.restore();
		assertEquals(LABEL_COLOUR, code.get("colour", 0));
		assertEquals(ROW_COLOUR, widgets.get(TravelLogController.FAVE_ROWS[0]).get("colour", 0));
	}

	@Test
	public void missingRowLeavesTheLogRestored()
	{
		assertTrue(apply("AIQ", -1));
		widgets.get(1001).p.put("text", "");
		assertFalse(apply("CKS", -1));
		assertFalse(log.isFiltered());
		assertFalse(hidden("AIQ") || hidden("DJR"));
	}
}
