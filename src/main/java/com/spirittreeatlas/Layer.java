/*
 * Copyright (c) 2026, Dewdul
 * All rights reserved.
 * Licensed under the BSD 2-Clause License. See LICENSE.
 */
package com.spirittreeatlas;

import java.awt.Color;
import java.util.Arrays;
import java.util.List;
import lombok.Getter;

/**
 * A map layer from index.json: an area of the world drawn as its own map. Bounds are world tiles,
 * inclusive-exclusive: [x0, y0, x1, y1].
 */
@Getter
public class Layer
{
	public static final String SURFACE = "surface";
	public static final String POH = "poh";

	private String id;
	private String name;
	private int[] bounds;
	private String background;
	private transient Color backgroundColor;

	public Layer()
	{
	}

	Layer(String id, String name, int x0, int y0, int x1, int y1, String background)
	{
		this.id = id;
		this.name = name;
		this.bounds = new int[]{x0, y0, x1, y1};
		this.background = background;
	}

	public boolean isSurface()
	{
		return SURFACE.equals(id);
	}

	public boolean contains(double x, double y)
	{
		return bounds != null && x >= bounds[0] && x < bounds[2] && y >= bounds[1] && y < bounds[3];
	}

	public Color getBackgroundColor()
	{
		if (backgroundColor == null)
		{
			backgroundColor = parseColor(background, new Color(0x10181c));
		}
		return backgroundColor;
	}

	boolean isValid()
	{
		return id != null && bounds != null && bounds.length == 4 && bounds[2] > bounds[0] && bounds[3] > bounds[1];
	}

	static Color parseColor(String hex, Color fallback)
	{
		if (hex == null)
		{
			return fallback;
		}
		try
		{
			return new Color(Integer.parseInt(hex.startsWith("#") ? hex.substring(1) : hex, 16));
		}
		catch (NumberFormatException e)
		{
			return fallback;
		}
	}

	/**
	 * Layers used when index.json is missing or lacks one: the ids and rough bounds of DESIGN 3.3.
	 * Without imagery the map is a plain background with markers.
	 */
	static List<Layer> defaults()
	{
		return Arrays.asList(
			new Layer(SURFACE, "Gielinor", 1016, 2104, 3976, 4168, "#4a5d89"),
			new Layer("zanaris", "Zanaris", 2304, 4288, 2560, 4544, "#000000"),
			new Layer("abyss", "The Abyss", 2880, 4672, 3200, 4992, "#000000"),
			new Layer("dorgesh_south", "Dorgesh-Kaan South Dungeon", 2624, 5120, 2816, 5312, "#000000"),
			new Layer("fisher_realm", "Fisher Realm", 2560, 4608, 2752, 4800, "#000000"),
			new Layer("enchanted_valley", "Enchanted Valley", 2944, 4416, 3136, 4608, "#000000"),
			new Layer("mor_ul_rek", "Mor Ul Rek", 2336, 5024, 2560, 5248, "#000000"),
			new Layer("cosmic_plane", "Cosmic entity's plane", 1984, 4736, 2176, 4928, "#000000"),
			new Layer("gorak_plane", "Gorak Plane", 2944, 5248, 3136, 5440, "#000000"),
			new Layer("yubiusk", "Yu'biusk", 3456, 4288, 3648, 4480, "#000000"),
			new Layer("grimstone", "Grimstone Dungeon", 2816, 10368, 3008, 10560, "#000000"),
			new Layer("hollows", "Myreque Hideout (The Hollows)", 3328, 9728, 3584, 9984, "#000000"));
	}
}
