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
 * DESIGN 1.1: the plugin never sends a game action, writes vars, injects input, uses reflection,
 * captures the keyboard or touches the network. This scans the main sources for the APIs that
 * would do any of that.
 */
public class ComplianceTest
{
	private static final String[] FORBIDDEN = {
		"menuAction(", "runScript(", "createScriptEventBuilder", "setCanSendPackets",
		"setVarbit", "setVarp", "setVarcIntValue", "setVarcStrValue",
		"java.awt.Robot", "new Robot(", "dispatchEvent", "KeyboardFocusManager", "requestFocus",
		"java.lang.reflect", "getDeclaredField", "getDeclaredMethod", "setAccessible", "Class.forName",
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
				for (String bad : FORBIDDEN)
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
