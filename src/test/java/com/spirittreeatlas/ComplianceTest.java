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
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * DESIGN 1 (hard rules 1-5): the plugin never sends a game action, writes vars, injects input,
 * uses reflection, adds widget ops, listeners or text, captures the keyboard, touches the key
 * listener layers or the network. This scans the main sources for the APIs that would do any of
 * that, however they are written: comments are stripped, whitespace is collapsed, method
 * references count as calls, and names match as whole words.
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
		"menuAction", "runScript", "setCanSendPackets", "setVarp", "Robot", "requestFocus", "requestFocusInWindow",
		"java.lang.reflect", "Class.forName", "setOnMouseLeaveListener", "setOnTimerListener", "setOnDialogAbortListener",
		"setOnMouseRepeatListener", "setOnTargetEnterListener", "setOnTargetLeaveListener", "setHasListener", "setName",
		"setSpriteId", "setOpacity", "setOriginalWidth", "setOriginalHeight", "setWidthMode", "setHeightMode", "createChild",
		"deleteAllChildren", "clearActions", "setSubOp",
		"KeyListener", "registerKeyListener", "KeyManager",
		"OkHttpClient", "HttpURLConnection", "openConnection", "java.net.URL", "java.net.Socket",
		"hopToWorld", "invokeMenuAction",
		// a game script's event re-run, or a game menu entry retargeted: nothing here needs either
		"getScriptEvent", "setParam0", "setParam1", "setIdentifier",
	};
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
			if (args.get(2).equals("-1"))
			{
				assertEquals("a static component hidden", HIDEABLE_STATIC, args.get(1));
			}
		}
		assertTrue("TreeMenu hides through hide()", calls >= 2);
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
