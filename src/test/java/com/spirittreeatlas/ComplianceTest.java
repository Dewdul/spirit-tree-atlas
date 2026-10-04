/*
 * Copyright (c) 2026, Dewdul
 * All rights reserved.
 * Licensed under the BSD 2-Clause License. See LICENSE.
 */
package com.spirittreeatlas;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * DESIGN 1 (hard rules 1-5): the plugin never sends a game action, writes vars, injects input,
 * uses reflection, adds widget ops, listeners or text, resizes any widget but the Travel row,
 * captures the keyboard, touches the key listener layers or the network. This scans the main
 * sources for the APIs that would do any of that, however they are written: comments are
 * stripped, whitespace is collapsed, method references count as calls, and names match as whole
 * words.
 */
public class ComplianceTest
{
	/** Every string of DESIGN rule 1, as written there. */
	private static final String[] RULE_1 = {
		"client.menuAction", "client.runScript", "createScriptEventBuilder", "ScriptEvent.setCanSendPackets",
		"setVarbit", "setVarbitValue", "setVarcIntValue", "setVarcStrValue", "java.awt.Robot", "dispatchEvent",
		"KeyboardFocusManager", "setAccessible", "getDeclaredField", "getDeclaredMethod",
		"setAction", "setOnOpListener", "setOnKeyListener", "setOnClickListener", "setOnMouseOverListener", "setText",
		"setTextColor",
	};
	/** The same APIs however they are reached, and the rest of the rules (input, reflection, network). */
	private static final String[] FORBIDDEN = {
		"menuAction", "runScript", "setCanSendPackets", "setVarp", "setVarpValue", "queueChangedVarp", "Robot", "requestFocus",
		"requestFocusInWindow",
		"java.lang.reflect", "Class.forName", "setOnMouseLeaveListener", "setOnTimerListener", "setOnDialogAbortListener",
		"setOnMouseRepeatListener", "setOnTargetEnterListener", "setOnTargetLeaveListener", "setHasListener", "setName",
		"setSpriteId", "setOpacity", "createChild", "deleteAllChildren", "clearActions", "setSubOp",
		// whole words no longer catch these as "setText" did: a widget restyled is a widget re-texted (rule 4)
		"setTextShadowed", "setFontId", "setXTextAlignment", "setYTextAlignment", "setLineHeight", "setModelId",
		"setItemId", "setItemQuantity", "setFilled", "setSpriteTiling", "setBorderType",
		"KeyListener", "registerKeyListener", "KeyManager",
		"OkHttpClient", "HttpURLConnection", "openConnection", "java.net", "java.net.URL", "java.net.Socket",
		"hopToWorld", "invokeMenuAction",
		// a game script's event re-run, or a game menu entry retargeted: nothing here needs either
		"getScriptEvent", "setParam0", "setParam1", "setIdentifier",
		// the other ways to resize or lay out a widget (only the Travel row is resized, through RESIZE in
		// TreeMenu.writeSize), and the rest of the widget setters: none is needed to hide and move (rule 4)
		"setWidth", "setHeight", "setSize", "setPos", "setRelativeX", "setRelativeY", "setForcedPosition",
		"setScrollX", "setScrollY", "setScrollWidth", "setScrollHeight", "revalidateScroll", "setChildren",
		"setOnScrollWheelListener", "setOnDragListener", "setOnDragCompleteListener", "setOnHoldListener",
		"setOnReleaseListener", "setOnVarTransmitListener", "setVarTransmitTrigger", "setClickMask", "setTargetVerb",
		"setTargetPriority", "setNoClickThrough", "setNoScrollThrough", "setDragParent", "setDragDeadTime",
		"setDragDeadZone", "setContentType", "setAnimationId", "setRotationX", "setRotationY", "setRotationZ",
		"setModelZoom", "setModelType", "setFlippedHorizontally", "setFlippedVertically", "setItemQuantityMode",
	};
	/** The widget size setters: allowed only for the Travel row, in TreeMenu.writeSize ({@link #onlyTheTravelRowIsResized}). */
	private static final String[] RESIZE = {"setWidthMode", "setHeightMode", "setOriginalWidth", "setOriginalHeight"};
	/** The row layers, whose dynamic children are the rows (DESIGN 2.2, 2.3). */
	private static final List<String> ROW_LAYERS = Arrays.asList("InterfaceID.MenuNew.TEXT", "InterfaceID.MenuNew.GRAPHICS",
		"InterfaceID.Menu.LJ_LAYER1");
	/** The only static component Map mode may hide: no key-listener layer and nothing that holds one (DESIGN 4.3). */
	private static final String HIDEABLE_STATIC = "InterfaceID.Menu.LJ_SCROLL_BAR";
	private static final Pattern SPACE_AROUND = Pattern.compile("\\s*(::|[.(),;])\\s*");

	/** Every main source, comments stripped and whitespace collapsed ({@link #normalise}), by file name. */
	private static Map<String, String> sources() throws IOException
	{
		Path root = Paths.get("src", "main", "java");
		assertTrue("run from the project directory", Files.isDirectory(root));
		List<Path> files;
		try (Stream<Path> walk = Files.walk(root))
		{
			files = walk.filter(p -> p.toString().endsWith(".java")).sorted().collect(Collectors.toList());
		}
		assertFalse(files.isEmpty());
		Map<String, String> out = new LinkedHashMap<>();
		for (Path f : files)
		{
			out.put(f.getFileName().toString(), normalise(new String(Files.readAllBytes(f), StandardCharsets.UTF_8)));
		}
		return out;
	}

	/**
	 * Java source as one line: comments removed (string and char literals kept, so a "//" inside
	 * one is not a comment), every run of whitespace one space, no space around . :: ( ) , ;
	 * and method references written as calls' receivers ("client::runScript" reads "client.runScript").
	 */
	static String normalise(String src)
	{
		StringBuilder b = new StringBuilder(src.length());
		int i = 0;
		while (i < src.length())
		{
			char c = src.charAt(i);
			char next = i + 1 < src.length() ? src.charAt(i + 1) : 0;
			if (c == '/' && next == '/')
			{
				while (i < src.length() && src.charAt(i) != '\n')
				{
					i++;
				}
			}
			else if (c == '/' && next == '*')
			{
				int end = src.indexOf("*/", i + 2);
				i = end < 0 ? src.length() : end + 2;
				b.append(' ');
			}
			else if (c == '"' || c == '\'')
			{
				int j = i + 1;
				while (j < src.length() && src.charAt(j) != c)
				{
					j += src.charAt(j) == '\\' ? 2 : 1;
				}
				j = Math.min(j + 1, src.length());
				b.append(src, i, j);
				i = j;
			}
			else
			{
				b.append(c);
				i++;
			}
		}
		String s = b.toString().replaceAll("\\s+", " ");
		return SPACE_AROUND.matcher(s).replaceAll("$1").replace("::", ".").trim();
	}

	/** A forbidden name as a whole word ("." between its parts), so a longer identifier holding it does not match. */
	private static Pattern word(String name)
	{
		return Pattern.compile("(?<![\\w$])" + Pattern.quote(name) + "(?![\\w$])");
	}

	@Test
	public void mainSourcesUseNoForbiddenApi() throws IOException
	{
		List<String> found = new ArrayList<>();
		for (Map.Entry<String, String> f : sources().entrySet())
		{
			for (String bad : concat(RULE_1, FORBIDDEN))
			{
				if (word(bad).matcher(f.getValue()).find())
				{
					found.add(f.getKey() + " uses " + bad);
				}
			}
		}
		assertTrue(String.join("\n", found), found.isEmpty());
	}

	/** The scan sees through the ways a call can be written. */
	@Test
	public void theScanSeesThroughFormatting()
	{
		String[] hidden = {
			"Consumer<Object[]> run = client::runScript;",
			"client.runScript (1437, 0);",
			"client\n\t.runScript(1437);",
			"String s = \"//\"; client./* why */menuAction(0, 0);",
			"e.getScriptEvent().run();",
		};
		for (String src : hidden)
		{
			String n = normalise(src);
			boolean caught = false;
			for (String bad : concat(RULE_1, FORBIDDEN))
			{
				caught |= word(bad).matcher(n).find();
			}
			assertTrue(src, caught);
		}
		// comments and longer identifiers are not uses
		String clean = normalise("// client.runScript(1)\n/* setText */ int setTextAlignment = 0; String s = \"a\";");
		for (String bad : concat(RULE_1, FORBIDDEN))
		{
			assertFalse(bad, word(bad).matcher(clean).find());
		}
		assertEquals("a.b(c,d);", normalise("a . b ( c , d ) ;"));
	}

	private static String[] concat(String[] a, String[] b)
	{
		String[] out = Arrays.copyOf(a, a.length + b.length);
		System.arraycopy(b, 0, out, a.length, b.length);
		return out;
	}

	/**
	 * Hard rule 4: the key-listener layers (MenuNew.KEYLISTENERS, Menu.KEYLISTENERS) hold the
	 * game's own hotkeys; no code refers to them at all, so nothing can hide or move them.
	 */
	@Test
	public void keyListenerLayersAreNeverReferenced() throws IOException
	{
		for (Map.Entry<String, String> f : sources().entrySet())
		{
			assertFalse(f.getKey(), word("KEYLISTENERS").matcher(f.getValue()).find());
		}
	}

	/**
	 * Hard rule 4, structurally: the game skips a hidden component's whole subtree, so hiding a
	 * component that holds a key-listener layer (947 INFINITE, UNIVERSE, CONTENT_FRAME, CONTENT;
	 * 187 LJ_LAYER2) turns the hotkeys off. Every hide goes through TreeMenu.hide (the one
	 * setHidden(true)), and the only static component it is ever given is the classic scrollbar;
	 * the rest are dynamic children (rows, the parchment model), which hold no key listener.
	 * TreeMenuTest.hotkeysKeepWorkingInMapMode checks the same against the fake menus.
	 */
	@Test
	public void nothingHoldingTheKeyListenersIsHidden() throws IOException
	{
		Map<String, String> sources = sources();
		int hides = 0;
		for (Map.Entry<String, String> f : sources.entrySet())
		{
			Matcher m = Pattern.compile("(?<![\\w$])setHidden\\(([^)]*)\\)").matcher(f.getValue());
			while (m.find())
			{
				assertTrue(f.getKey() + ": setHidden(" + m.group(1) + ")", m.group(1).equals("true") || m.group(1).equals("false"));
				if (m.group(1).equals("true"))
				{
					assertEquals("setHidden(true) outside TreeMenu.hide", "TreeMenu.java", f.getKey());
					hides++;
				}
			}
		}
		assertEquals("one setHidden(true), in TreeMenu.hide", 1, hides);

		String menu = sources.get("TreeMenu.java");
		int calls = 0;
		Matcher m = word("hide").matcher(menu);
		while (m.find())
		{
			int open = m.end();
			if (open >= menu.length() || menu.charAt(open) != '(' || menu.startsWith("void ", m.start() - 5))
			{
				continue;
			}
			List<String> args = arguments(menu, open);
			assertEquals("hide" + args, 3, args.size());
			calls++;
			// a static component (index -1, or one looked up by id) is only ever the scrollbar
			if (args.get(2).equals("-1") || args.get(0).contains("getWidget("))
			{
				assertEquals("a static component hidden", Arrays.asList("client.getWidget(" + HIDEABLE_STATIC + ")", HIDEABLE_STATIC, "-1"), args);
			}
		}
		assertTrue("TreeMenu hides through hide()", calls >= 2);
	}

	/**
	 * Hard rule 4's one resize: the Travel row is made button-sized (DESIGN 4.3), like Fairy Ring
	 * Atlas's Teleport button, and nothing else is ever resized. Structurally: the size setters
	 * appear only in TreeMenu.writeSize, once each; writeSize is called only by place (writing the
	 * size it is given) and by unmove (putting back the size it recorded); place is given a size
	 * only by placeRows, which runs only on the row layers. TreeMenuTest checks that only the shown
	 * row is resized and that its size and size modes are put back exactly.
	 */
	@Test
	public void onlyTheTravelRowIsResized() throws IOException
	{
		Map<String, String> sources = sources();
		for (Map.Entry<String, String> f : sources.entrySet())
		{
			for (String set : RESIZE)
			{
				Matcher m = word(set).matcher(f.getValue());
				int n = 0;
				while (m.find())
				{
					assertEquals(set + " outside TreeMenu", "TreeMenu.java", f.getKey());
					n++;
				}
				assertEquals(f.getKey() + " " + set, f.getKey().equals("TreeMenu.java") ? 1 : 0, n);
			}
		}
		String menu = sources.get("TreeMenu.java");
		String writeSize = body(menu, "void writeSize(");
		for (String set : RESIZE)
		{
			assertTrue(set + " outside writeSize", word(set).matcher(writeSize).find());
		}
		assertEquals(Arrays.asList("place: writeSize(w,size)", "unmove: writeSize(c.widget,c.originalSize)"),
			calls(menu, "writeSize", "place", "unmove"));
		// and never reached any other way (a method reference): its declaration and those two calls only
		int uses = 0;
		Matcher ws = word("writeSize").matcher(menu);
		while (ws.find())
		{
			uses++;
		}
		assertEquals("writeSize: declared, called twice", 3, uses);
		// place with a size (not null) only from placeRows
		for (String call : calls(menu, "place", "move", "placeRows"))
		{
			assertTrue(call, call.startsWith("placeRows: ") || call.startsWith("move: ") && call.endsWith(",null)"));
		}
		int rows = 0;
		Matcher m = word("placeRows").matcher(menu);
		while (m.find())
		{
			int open = m.end();
			if (open < menu.length() && menu.charAt(open) == '(' && !menu.startsWith("void ", m.start() - 5))
			{
				assertTrue(arguments(menu, open).toString(), ROW_LAYERS.contains(arguments(menu, open).get(1)));
				rows++;
			}
		}
		assertEquals("placeRows on TEXT, GRAPHICS and LJ_LAYER1", 3, rows);
	}

	/** The body of the method declared by {@code decl} ("void name("), braces included. */
	private static String body(String src, String decl)
	{
		int at = src.indexOf(decl);
		assertTrue(decl, at >= 0);
		assertEquals(decl + " declared once", -1, src.indexOf(decl, at + 1));
		int start = src.indexOf('{', at);
		int depth = 0;
		for (int i = start; i < src.length(); i++)
		{
			depth += src.charAt(i) == '{' ? 1 : src.charAt(i) == '}' ? -1 : 0;
			if (depth == 0)
			{
				return src.substring(start, i + 1);
			}
		}
		throw new AssertionError("unbalanced " + decl);
	}

	/**
	 * Every call of {@code name} (not its declaration), as "caller: name(args)", each checked to sit
	 * in the body of one of the allowed callers.
	 */
	private static List<String> calls(String src, String name, String... callers)
	{
		List<String> out = new ArrayList<>();
		Matcher m = word(name).matcher(src);
		while (m.find())
		{
			int open = m.end();
			if (open >= src.length() || src.charAt(open) != '(' || src.startsWith("void ", m.start() - 5))
			{
				continue;
			}
			String in = null;
			for (String caller : callers)
			{
				String b = body(src, "void " + caller + "(");
				int from = src.indexOf(b);
				if (m.start() > from && m.start() < from + b.length())
				{
					in = caller;
				}
			}
			assertNotNull(name + " called outside " + Arrays.toString(callers), in);
			out.add(in + ": " + name + "(" + String.join(",", arguments(src, open)) + ")");
		}
		return out;
	}

	/** The top-level arguments of the call whose "(" is at {@code open}. */
	private static List<String> arguments(String src, int open)
	{
		List<String> out = new ArrayList<>();
		int depth = 0;
		int start = open + 1;
		for (int i = open; i < src.length(); i++)
		{
			char c = src.charAt(i);
			if (c == '(')
			{
				depth++;
			}
			else if (c == ')' && --depth == 0)
			{
				out.add(src.substring(start, i));
				return out;
			}
			else if (c == ',' && depth == 1)
			{
				out.add(src.substring(start, i));
				start = i + 1;
			}
		}
		return out;
	}

	/** Hard rule 3: every menu entry type written is MenuAction.RUNELITE (also through a method reference). */
	@Test
	public void menuEntriesAreRuneliteOnly() throws IOException
	{
		int entries = 0;
		for (Map.Entry<String, String> f : sources().entrySet())
		{
			Matcher m = word("setType").matcher(f.getValue());
			while (m.find())
			{
				assertTrue(f.getKey() + ": " + f.getValue().substring(m.start(), Math.min(f.getValue().length(), m.end() + 30)),
					f.getValue().startsWith("(MenuAction.RUNELITE)", m.end()));
				entries++;
			}
		}
		assertTrue("the plugin adds its menu entries", entries > 0);
	}
}
