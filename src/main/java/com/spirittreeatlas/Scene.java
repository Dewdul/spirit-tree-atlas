/*
 * Copyright (c) 2026, Dewdul
 * All rights reserved.
 * Licensed under the BSD 2-Clause License. See LICENSE.
 */
package com.spirittreeatlas;

import java.awt.Color;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * Everything the painters need for one frame, as plain data, so the map can be drawn without a
 * game client (previews and tests). The overlay refills one instance every frame; MapPreview
 * fills its own. This is the contract between the plugin and the painters ({@link AtlasPainter},
 * {@link ChromePainter}): painters read the inputs and write only the outputs.
 *
 * <p>All rectangles are canvas coordinates. Tree ids are trees.json ids ("GRAND_EXCHANGE").
 */
public final class Scene
{
	// ------------------------------------------------------------------ data

	/** Layers, map index (place labels, icons) and portals. */
	public TreeRepository repo;
	/** The view: layer, centre, zoom and the map's rectangle on screen. */
	public MapView view;
	/** Every tree in data order, the house placed (or not) at its portal; draw those {@link Tree#isMapped()} on the view's layer. */
	public List<Tree> trees = new ArrayList<>();
	/** What the open menu says about each tree, by id; a missing id is {@link Tree.Status#ABSENT}. */
	public Map<String, Tree.Status> states = new HashMap<>();
	/** Each tree's hotkey as its row shows it ("4", "C"), by id; missing when it has none. */
	public Map<String, String> keys = new HashMap<>();
	/** Surface stand-ins for the trees on other layers (draw them on the surface view only). */
	public List<TreeRepository.StandIn> standIns = new ArrayList<>();
	/** Every tree in the open menu's order: the quick-select panel's rows ({@link TreeRepository#menuOrder()}). */
	public List<Tree> menuOrder = new ArrayList<>();

	// ------------------------------------------------------------------ state

	/** Selected tree id, or null. */
	public String selected;
	/** The tree under the mouse (a marker or a stand-in), or null; kept while a right-click menu is open. */
	public Tree hovered;
	/** The tree the player stands at, or null. */
	public String here;
	/** The last trip's tree, or null. */
	public String last;
	/**
	 * Places where the real game widgets show through the map: the close button always, the
	 * Travel cell while {@link #rowShown}. Nothing is drawn inside them.
	 */
	public final List<Rectangle> holes = new ArrayList<>();
	/** The Travel cell in the map's bottom-right corner, shown or covered; null when unknown. */
	public Rectangle rowCell;
	/** The real close button; null when unknown. */
	public Rectangle closeRect;
	/** Whether the selected tree's real row is uncovered in {@link #rowCell} ({@link #rowShown(String, TreeMenu.Row, String)}). */
	public boolean rowShown;
	/** The covered cell's one line ({@link #standInText}); null while the row shows. */
	public String standIn;
	/** The caption over the cell's left end. */
	public String caption = "Travel";
	/** A one-line notice (stepping aside for another plugin, DESIGN 4.10), or null. */
	public String notice;
	/** The mouse while the map owns input there (for hover looks), else null. */
	public Point mouse;
	/** Frame time in ms, for pulses and halos. */
	public long now;
	/** Whether the quick-select panel is open (else only its tab shows). */
	public boolean panelOpen = true;
	/** How far (px) the quick-select panel's rows are scrolled. */
	public int panelScroll;

	// ------------------------------------------------------------------ settings

	/** World map place names from index.json. */
	public boolean placeLabels = true;
	/** World map icons from index.json, drawn from 2 ppt. */
	public boolean mapIcons = true;
	/** The tree card shows everything rather than a short summary. */
	public boolean fullDetails;
	/** Each tree's label beside its marker. */
	public boolean treeLabels = true;
	/** Each tree's hotkey in a badge at its marker's top-right. */
	public boolean keyHints = true;
	/** Locked trees at half opacity. */
	public boolean dimLocked;
	/** The quick-select panel down the map's left edge (open or as its tab); off: neither. */
	public boolean quickSelect = true;
	/** The canopy of an available tree. */
	public Color availableColor = new Color(0x5BD45B);
	/** The selected tree's ring, halo and label. */
	public Color selectedColor = new Color(0xFF981F);
	/** Sprite lookup for map icons; returns null until a sprite is loaded. */
	public IntFunction<BufferedImage> sprites = id -> null;

	// ------------------------------------------------------------------ output (painters)

	/** Hit regions, later ones on top; the overlay publishes them for input and menus. */
	public final List<Hit> hits = new ArrayList<>();
	/** The info card (or the hint card), which labels and other chrome keep clear of. */
	public Rectangle card;
	/** The top bar along the map's top edge. */
	public Rectangle topBar;
	/** The large "Back to Gielinor" button on other layers; null on the surface. */
	public Rectangle backButton;
	/** The "Travel" caption's box, set by the chrome layout. */
	public Rectangle captionRect;
	/**
	 * The stand-in's box over the covered cell, set by the chrome layout: the cell, widened to the
	 * left when its one line needs it; null while the row shows. Labels and the card keep clear of it.
	 */
	public Rectangle standInRect;
	/**
	 * The quick-select panel, or its closed tab, inside the map's left edge under the top bar; null
	 * when it is off. Fits, labels and the card keep clear of it.
	 */
	public Rectangle panel;

	// ------------------------------------------------------------------ helpers

	/** The view's layer, or null. */
	public Layer layer()
	{
		return repo == null || view == null ? null : repo.layer(view.getLayer());
	}

	public Tree tree(String id)
	{
		if (id != null)
		{
			for (Tree t : trees)
			{
				if (id.equals(t.getId()))
				{
					return t;
				}
			}
		}
		return null;
	}

	public Tree.Status status(Tree t)
	{
		Tree.Status s = t == null ? null : states.get(t.getId());
		return s == null ? Tree.Status.ABSENT : s;
	}

	public String key(Tree t)
	{
		return t == null ? null : keys.get(t.getId());
	}

	/** The holes, the Travel cell (shown or covered) and its caption: what cards and labels keep clear of. */
	public List<Rectangle> blockers()
	{
		if (rowCell == null && captionRect == null)
		{
			return holes;
		}
		List<Rectangle> out = new ArrayList<>(holes);
		for (Rectangle r : new Rectangle[]{rowCell, captionRect})
		{
			if (r != null && !out.contains(r))
			{
				out.add(r);
			}
		}
		return out;
	}

	/** Fills the per-tree inputs from the repository's state (the overlay, previews and tests). */
	public void fromRepository(TreeRepository r)
	{
		repo = r;
		trees = r.getTrees();
		states = new HashMap<>();
		keys = new HashMap<>();
		for (Tree t : trees)
		{
			states.put(t.getId(), r.status(t.getId()));
			if (r.key(t.getId()) != null)
			{
				keys.put(t.getId(), r.key(t.getId()));
			}
		}
		standIns = r.standIns();
		menuOrder = r.menuOrder();
		here = r.getHere();
		last = r.getLast();
	}

	// ------------------------------------------------------------------ decisions (pure)

	/**
	 * DESIGN 4.4: whether the selected tree's real row is uncovered as the Travel button: a tree is
	 * selected, it has a row, the row is not grey, the tree is not where the player stands, and the
	 * row's text still maps to it.
	 *
	 * @param row the selected tree's row as the live widget shows it now (null when it has none,
	 *     or the widget is gone or hidden)
	 */
	public static boolean rowShown(String selected, TreeMenu.Row row, String here)
	{
		return selected != null && row != null && !row.isGrey() && !selected.equals(here) && selected.equals(row.getTreeId());
	}

	/**
	 * DESIGN 4.4: what the stand-in over the covered Travel cell says.
	 *
	 * @param row the selected tree's row, or null
	 */
	public static String standInText(Tree selected, TreeMenu.Row row, String here)
	{
		if (selected == null)
		{
			return "Pick a tree on the map";
		}
		if (row != null && row.isGrey())
		{
			return "Locked: " + selected.getLockedHint();
		}
		if (selected.getId().equals(here))
		{
			return "You are here";
		}
		return "Not in this tree's list";
	}

	/**
	 * DESIGN 4.5: the card's next-step line for the selected tree.
	 *
	 * @param status what the menu says about it; {@code key} its row's hotkey, or null
	 */
	public static String nextStep(Tree t, Tree.Status status, String key, String here)
	{
		if (t.getId().equals(here))
		{
			return "You are at this tree.";
		}
		if (status == Tree.Status.LOCKED)
		{
			return "Not available yet: " + t.getLockedHint() + ".";
		}
		if (status == Tree.Status.ABSENT)
		{
			return "This tree is not in the list here.";
		}
		return key == null ? "Click Travel." : "Click Travel, or press " + key + ".";
	}

	/** DESIGN 4.6: the card's status line (before any "last trip" badge). */
	public static String statusText(Tree t, Tree.Status status, String here)
	{
		if (t.getId().equals(here))
		{
			return "You are here";
		}
		switch (status)
		{
			case AVAILABLE:
				return "Available";
			case LOCKED:
				return "Locked - " + t.getLockedHint();
			default:
				return "Not in the list";
		}
	}
}
