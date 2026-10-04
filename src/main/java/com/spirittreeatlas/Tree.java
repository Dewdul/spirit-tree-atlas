/*
 * Copyright (c) 2026, Dewdul
 * All rights reserved.
 * Licensed under the BSD 2-Clause License. See LICENSE.
 */
package com.spirittreeatlas;

import java.util.Collections;
import java.util.List;
import lombok.Getter;

/**
 * One destination of trees.json (DESIGN 3.1). Gson fills the fields; only the house is changed
 * afterwards, when {@link TreeRepository#placeHouse} puts it at the player's house portal.
 * Positions are tree centres in tile-index units: a marker is drawn at (x + 0.5, y + 0.5).
 */
@Getter
public class Tree
{
	public static final String MATCH_PREFIX = "prefix";
	public static final String KIND_HOUSE = "house";

	/** What the open menu says about a tree (DESIGN 3.4). */
	public enum Status
	{
		/** Its row is listed and not grey. */
		AVAILABLE,
		/** Its row is listed in grey: the game refuses it. */
		LOCKED,
		/** No row maps to it (or no menu has been read): unknown. */
		ABSENT,
	}

	private String id;
	private String menuLabel;
	private String match;
	private int previousValue;
	private String name;
	private String label;
	private String area;
	private String kind;
	private String layer;
	private double x;
	private double y;
	private int plane;
	private int[] arrival;
	private String lockedHint;
	private List<String> requirements;
	private List<String> poi;
	private List<String> notes;
	private List<String> danger;
	/** The house's town, once placed; part of its name and label. */
	private transient String town;

	public Tree()
	{
	}

	Tree(String id, String menuLabel, String layer, double x, double y)
	{
		this.id = id;
		this.menuLabel = menuLabel;
		this.name = menuLabel;
		this.label = menuLabel;
		this.layer = layer;
		this.x = x;
		this.y = y;
	}

	public boolean isHouse()
	{
		return KIND_HOUSE.equals(kind);
	}

	/** Whether the row is matched by its start ("Your house (Rimmington)"), not as a whole. */
	public boolean isPrefix()
	{
		return MATCH_PREFIX.equals(match);
	}

	/** Whether the tree has a place on a map layer: everything but an unplaced house. */
	public boolean isMapped()
	{
		return layer != null && !Layer.POH.equals(layer);
	}

	/** Card title; the house names its town: "Your house (Rimmington)". */
	public String getName()
	{
		return withTown(name != null ? name : id);
	}

	/** The short name beside the marker; the house names its town too. */
	public String getLabel()
	{
		return withTown(label != null ? label : getName());
	}

	public String getLockedHint()
	{
		return lockedHint == null ? "Not available yet" : lockedHint;
	}

	public List<String> getRequirements()
	{
		return nonNull(requirements);
	}

	public List<String> getPoi()
	{
		return nonNull(poi);
	}

	public List<String> getNotes()
	{
		return nonNull(notes);
	}

	public List<String> getDanger()
	{
		return nonNull(danger);
	}

	/** Puts the house at a portal (layer and centre) with its town, or takes it off the map (layer null). */
	void place(String layer, double x, double y, int plane, String town)
	{
		this.layer = layer == null ? Layer.POH : layer;
		this.x = x;
		this.y = y;
		this.plane = plane;
		this.town = town;
	}

	private String withTown(String s)
	{
		return town == null || s == null ? s : s + " (" + town + ")";
	}

	private static List<String> nonNull(List<String> list)
	{
		return list == null ? Collections.emptyList() : list;
	}
}
