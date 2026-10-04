/*
 * Copyright (c) 2026, Dewdul
 * All rights reserved.
 * Licensed under the BSD 2-Clause License. See LICENSE.
 */
package com.spirittreeatlas;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import lombok.Getter;

/** map/index.json: which tiles ship, the layers, place labels and map icons (DESIGN 3.2). */
@Getter
public class MapIndex
{
	private String cache;
	private int tileSize = 256;
	private List<Integer> levels;
	private Map<String, List<String>> tiles;
	private Map<String, Map<String, String>> solid;
	private List<Layer> layers;
	private List<Label> labels;
	/** Map icons as compact [x, y, sprite] triples (the bulk of the index). */
	@Getter(lombok.AccessLevel.NONE)
	private List<int[]> icons;
	@Getter(lombok.AccessLevel.NONE)
	private transient List<Icon> iconList;

	/** A world-map place name. s is the size class: 0 small, 1 medium, 2 large. */
	@Getter
	public static class Label
	{
		private String t;
		private int x;
		private int y;
		private int s;
		private String layer;
		/** Optional in-game text colour, e.g. "#ff981f"; white when absent. */
		private String c;
		private transient String[] lines;
		private transient java.awt.Color color;

		public java.awt.Color getColor()
		{
			if (color == null)
			{
				color = Layer.parseColor(c, java.awt.Color.WHITE);
			}
			return color;
		}

		/** The text split on the cache's {@code <br>} line breaks. */
		public String[] getLines()
		{
			if (lines == null)
			{
				lines = t == null ? new String[0] : t.split("<br>");
			}
			return lines;
		}
	}

	/** A map-function icon drawn with a game sprite. */
	@Getter
	public static class Icon
	{
		private final int x;
		private final int y;
		private final int sprite;

		Icon(int x, int y, int sprite)
		{
			this.x = x;
			this.y = y;
			this.sprite = sprite;
		}
	}

	public List<Integer> getLevels()
	{
		return levels == null ? Collections.emptyList() : levels;
	}

	public List<Label> getLabels()
	{
		return labels == null ? Collections.emptyList() : labels;
	}

	public List<Icon> getIcons()
	{
		if (iconList == null)
		{
			List<Icon> out = new ArrayList<>();
			for (int[] i : icons == null ? Collections.<int[]>emptyList() : icons)
			{
				if (i != null && i.length >= 3)
				{
					out.add(new Icon(i[0], i[1], i[2]));
				}
			}
			iconList = out;
		}
		return iconList;
	}

	public List<Layer> getLayers()
	{
		return layers == null ? new ArrayList<>() : layers;
	}

	public Map<String, List<String>> getTiles()
	{
		return tiles == null ? Collections.emptyMap() : tiles;
	}

	public Map<String, Map<String, String>> getSolid()
	{
		return solid == null ? Collections.emptyMap() : solid;
	}

	/** An index with no imagery: the plugin then draws plain backgrounds and markers. */
	static MapIndex empty()
	{
		return new MapIndex();
	}
}
