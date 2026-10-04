/*
 * Copyright (c) 2026, Dewdul
 * All rights reserved.
 * Licensed under the BSD 2-Clause License. See LICENSE.
 */
package com.spirittreeatlas;

import java.awt.Color;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

@ConfigGroup(SpiritTreeAtlasConfig.GROUP)
public interface SpiritTreeAtlasConfig extends Config
{
	String GROUP = "fairyringatlas";

	enum StartView
	{
		FIT_ALL,
		AROUND_YOU,
		REMEMBER,
	}

	@ConfigSection(name = "Map", description = "Size and contents of the map", position = 0)
	String mapSection = "map";

	@ConfigSection(name = "Markers", description = "How rings are drawn", position = 1)
	String markerSection = "markers";

	@ConfigSection(name = "Travel log", description = "Help with the travel log and the dials", position = 2)
	String logSection = "travelLog";

	@ConfigSection(name = "Advanced", description = "Other options", position = 3, closedByDefault = true)
	String advancedSection = "advanced";

	@ConfigItem(keyName = "openInMapMode", name = "Open as map",
		description = "Show the map over the dials when you use a fairy ring; otherwise start with the dials",
		section = mapSection, position = 0)
	default boolean openInMapMode()
	{
		return true;
	}

	@ConfigItem(keyName = "useFreeSpace", name = "Use free space",
		description = "Resizable mode: while the map shows, move the fairy ring interface into the corner of the free screen space so the map can fill it. It goes back when the map closes",
		section = mapSection, position = 1)
	default boolean useFreeSpace()
	{
		return true;
	}

	@Range(min = 512, max = 2000)
	@ConfigItem(keyName = "mapMaxWidth", name = "Max width",
		description = "Largest map width in pixels (resizable mode). With Use free space, a smaller map is centred in the free space", section = mapSection, position = 2)
	default int maxWidth()
	{
		return 2000;
	}

	@Range(min = 334, max = 1400)
	@ConfigItem(keyName = "mapMaxHeight", name = "Max height",
		description = "Largest map height in pixels (resizable mode). With Use free space, a smaller map is centred in the free space", section = mapSection, position = 3)
	default int maxHeight()
	{
		return 1400;
	}

	@ConfigItem(keyName = "openAt", name = "Open at",
		description = "Centre the map on the ring you are standing at, fit all rings, or remember the last view this session",
		section = mapSection, position = 4)
	default StartView startView()
	{
		return StartView.AROUND_YOU;
	}

	@ConfigItem(keyName = "placeLabels", name = "Place names",
		description = "Show world map place names", section = mapSection, position = 5)
	default boolean placeLabels()
	{
		return true;
	}

	@ConfigItem(keyName = "mapIcons", name = "Map icons",
		description = "Show world map icons when zoomed in", section = mapSection, position = 6)
	default boolean mapIcons()
	{
		return true;
	}

	@ConfigItem(keyName = "autoFitSearch", name = "Fit to search",
		description = "Zoom the map to the rings matching the travel log search", section = mapSection, position = 7)
	default boolean autoFitSearch()
	{
		return true;
	}

	@ConfigItem(keyName = "fullDetails", name = "Full ring details",
		description = "Show everything about a ring on its card (description, every nearby place, notes) instead of a short summary",
		section = mapSection, position = 8)
	default boolean fullDetails()
	{
		return false;
	}

	@ConfigItem(keyName = "codeLabels", name = "Code labels",
		description = "Which rings show their code beside the marker", section = markerSection, position = 0)
	default Scene.CodeLabels codeLabels()
	{
		return Scene.CodeLabels.ALL;
	}

	@ConfigItem(keyName = "dimUnvisited", name = "Dim locked rings",
		description = "Draw rings you have not unlocked (not in your travel log yet) at half opacity", section = markerSection, position = 1)
	default boolean dimUnvisited()
	{
		return false;
	}

	@ConfigItem(keyName = "visitedColor", name = "Visited colour",
		description = "Colour of rings in your travel log", section = markerSection, position = 2)
	default Color visitedColor()
	{
		return new Color(0x3FD9C8);
	}

	@ConfigItem(keyName = "selectedColor", name = "Selected colour",
		description = "Colour of the selected ring and its travel log row", section = markerSection, position = 3)
	default Color selectedColor()
	{
		return new Color(0xFF981F);
	}

	@ConfigItem(keyName = "favouriteColor", name = "Favourite colour",
		description = "Colour of the favourite star", section = markerSection, position = 4)
	default Color favouriteColor()
	{
		return new Color(0xFFD700);
	}

	@ConfigItem(keyName = "filterTravelLog", name = "Filter travel log",
		description = "Narrow the travel log to the selected ring, moved to the top", section = logSection, position = 0)
	default boolean filterTravelLog()
	{
		return true;
	}

	@ConfigItem(keyName = "dialGuidance", name = "Dial guidance",
		description = "In dial mode, show which way to turn each dial for the selected ring", section = logSection, position = 1)
	default boolean dialGuidance()
	{
		return true;
	}

	@ConfigItem(keyName = "menuNames", name = "Names in ring menu",
		description = "Show destination names in a fairy ring's right-click menu: Last-destination and the Favourites codes",
		section = logSection, position = 2)
	default boolean menuNames()
	{
		return true;
	}

	@ConfigItem(keyName = "clueHelper", name = "Clue helper",
		description = "Mark the active clue step on the map and preselect fairy ring clue codes. Needs the core Clue Scroll plugin to be enabled", section = advancedSection, position = 0)
	default boolean clueHelper()
	{
		return true;
	}
}
