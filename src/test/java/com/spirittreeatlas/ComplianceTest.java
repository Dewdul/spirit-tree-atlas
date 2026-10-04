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
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * DESIGN 1 (hard rules 1-5): the plugin never sends a game action, writes vars, injects input,
 * uses reflection, adds widget ops, listeners or text, captures the keyboard, touches the key
 * listener layers or the network. This scans the main sources for the APIs that would do any of
 * that.
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
		"menuAction(", "runScript(", "setCanSendPackets", "setVarp", "new Robot(", "requestFocus",
		"java.lang.reflect", "Class.forName", "setOnMouseLeaveListener", "setOnTimerListener", "setOnDialogAbortListener",
		"setOnMouseRepeatListener", "setOnTargetEnterListener", "setOnTargetLeaveListener", "setHasListener", "setName(",
		"setSpriteId", "setOpacity", "setOriginalWidth", "setOriginalHeight", "setWidthMode", "setHeightMode", "createChild",
		"deleteAllChildren", "clearActions", "setSubOp",
		"KeyListener", "registerKeyListener", "KeyManager",
		"OkHttpClient", "HttpURLConnection", "openConnection", "java.net.URL", "java.net.Socket",
		"hopToWorld", "invokeMenuAction",
	};

	@Test
	public void mainSourcesUseNoForbiddenApi() throws IOException
	{
		Path root = Paths.get("src", "main", "java");
		assertTrue("run from the project directory", Files.isDirectory(root));
		List<Path> files;
		try (Stream<Path> walk = Files.walk(root))
		{
			files = walk.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
		}
		assertFalse(files.isEmpty());
		List<String> found = new ArrayList<>();
		for (Path f : files)
		{
			List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
			for (int i = 0; i < lines.size(); i++)
			{
				for (String bad : concat(RULE_1, FORBIDDEN))
				{
					if (lines.get(i).contains(bad))
					{
						found.add(f.getFileName() + ":" + (i + 1) + " uses " + bad);
					}
				}
			}
		}
		assertTrue(String.join("\n", found), found.isEmpty());
	}

	private static String[] concat(String[] a, String[] b)
	{
		String[] out = java.util.Arrays.copyOf(a, a.length + b.length);
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
		Path root = Paths.get("src", "main", "java");
		try (Stream<Path> walk = Files.walk(root))
		{
			for (Path f : walk.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList()))
			{
				List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
				for (int i = 0; i < lines.size(); i++)
				{
					String code = lines.get(i).trim();
					boolean comment = code.startsWith("*") || code.startsWith("/*") || code.startsWith("//");
					assertTrue(f.getFileName() + ":" + (i + 1), comment || !code.contains("KEYLISTENERS"));
				}
			}
		}
	}

	@Test
	public void menuEntriesAreRuneliteOnly() throws IOException
	{
		Path root = Paths.get("src", "main", "java");
		try (Stream<Path> walk = Files.walk(root))
		{
			for (Path f : walk.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList()))
			{
				String src = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
				for (String line : src.split("\n"))
				{
					if (line.contains(".setType(MenuAction."))
					{
						assertTrue(f + ": " + line.trim(), line.contains("MenuAction.RUNELITE"));
					}
				}
			}
		}
	}
}
