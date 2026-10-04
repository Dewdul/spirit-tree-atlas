/*
 * Copyright (c) 2026, Dewdul
 * All rights reserved.
 * Licensed under the BSD 2-Clause License. See LICENSE.
 */
package com.spirittreeatlas;

import lombok.Getter;

/**
 * A place on a map from trees.json: where an off-surface layer is entered on the surface
 * ("portals": layer, x, y, label), or a house portal by its POH_HOUSE_LOCATION value
 * ("housePortals": value, town, x, y, plane, layer).
 */
@Getter
public class Portal
{
	private String layer;
	private double x;
	private double y;
	private String label;
	private int value;
	private String town;
	private int plane;

	public Portal()
	{
	}

	Portal(String layer, double x, double y, String label)
	{
		this.layer = layer;
		this.x = x;
		this.y = y;
		this.label = label;
	}
}
