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

/** DESIGN 4.11. */
@ConfigGroup(SpiritTreeAtlasConfig.GROUP)
public interface SpiritTreeAtlasConfig extends Config
{
	String GROUP = "spirittreeatlas";

	enum OpenAt
	{
		FIT_ALL,
		AROUND_YOU,
		REMEMBER,
	}

	@ConfigSection(name = "Map", description = "Size and contents of the map", position = 0)
	String mapSection = "map";

	@ConfigSection(name = "Markers", description = "How trees are drawn", position = 1)
	String markerSection = "markers";

	@ConfigItem(keyName = "openInMapMode", name = "Open as map",
		description = "Show the map over the spirit tree menu when it opens; otherwise start with the plain list",
		section = mapSection, position = 0)
	default boolean openInMapMode()
	{
		return true;
	}

	@ConfigItem(keyName = "useFreeSpace", name = "Use free space",
		description = "Resizable mode: while the map shows, move the spirit tree menu into the corner of the free screen space so the map can fill it. It goes back when the map closes",
		section = mapSection, position = 1)
	default boolean useFreeSpace()
	{
		return true;
	}

	@Range(min = 512, max = 2000)
	@ConfigItem(keyName = "mapMaxWidth", name = "Max width",
		description = "Largest map width in pixels (resizable mode). With Use free space, a smaller map is centred in the free space", section = mapSection, position = 2)
	default int mapMaxWidth()
	{
		return 2000;
	}

	@Range(min = 334, max = 1400)
	@ConfigItem(keyName = "mapMaxHeight", name = "Max height",
		description = "Largest map height in pixels (resizable mode). With Use free space, a smaller map is centred in the free space", section = mapSection, position = 3)
	default int mapMaxHeight()
	{
		return 1400;
	}

	@ConfigItem(keyName = "openAt", name = "Open at",
		description = "Fit every spirit tree, centre the map on where you are, or remember the last view this session",
		section = mapSection, position = 4)
	default OpenAt openAt()
	{
		return OpenAt.FIT_ALL;
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

	@ConfigItem(keyName = "fullDetails", name = "Full tree details",
		description = "Show everything about a tree on its card (every requirement, nearby place, danger and note) instead of a short summary",
		section = mapSection, position = 7)
	default boolean fullDetails()
	{
		return false;
	}

	@ConfigItem(keyName = "treeLabels", name = "Tree names",
		description = "Show each tree's name beside its marker", section = markerSection, position = 0)
	default boolean treeLabels()
	{
		return true;
	}

	@ConfigItem(keyName = "keyHints", name = "Key hints",
		description = "Show each tree's menu key on its marker: press it to travel without clicking", section = markerSection, position = 1)
	default boolean keyHints()
	{
		return true;
	}

	@ConfigItem(keyName = "dimLocked", name = "Dim locked trees",
		description = "Draw trees the menu lists as unavailable at half opacity", section = markerSection, position = 2)
	default boolean dimLocked()
	{
		return false;
	}

	@ConfigItem(keyName = "availableColor", name = "Available colour",
		description = "Colour of the trees you can travel to", section = markerSection, position = 3)
	default Color availableColor()
	{
		return new Color(0x5BD45B);
	}

	@ConfigItem(keyName = "selectedColor", name = "Selected colour",
		description = "Colour of the selected tree", section = markerSection, position = 4)
	default Color selectedColor()
	{
		return new Color(0xFF981F);
	}
}
