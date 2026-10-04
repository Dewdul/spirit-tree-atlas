/*
 * Copyright (c) 2026, Dewdul
 * All rights reserved.
 * Licensed under the BSD 2-Clause License. See LICENSE.
 */
package com.spirittreeatlas;

import lombok.Getter;

/** A surface position where an underground layer is entered (rings.json "portals"). */
@Getter
public class Portal
{
	private String layer;
	private int x;
	private int y;
	private String label;
}
