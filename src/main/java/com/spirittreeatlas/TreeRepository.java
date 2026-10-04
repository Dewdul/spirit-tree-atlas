/*
 * Copyright (c) 2026, Dewdul
 * All rights reserved.
 * Licensed under the BSD 2-Clause License. See LICENSE.
 */
package com.spirittreeatlas;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.awt.geom.Point2D;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.Getter;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

/**
 * The tree data (trees.json and map/index.json from the jar, DESIGN 3) and the state read for
 * each open of the menu (DESIGN 3.4): which trees the menu lists and how, where the player is,
 * the last trip and the house. Loading is done once; the state methods run on the client thread.
 */
@Slf4j
public class TreeRepository
{
	/** "You are here": a tree whose centre is at most this many tiles away (Chebyshev). */
	static final int HERE_TILES = 6;
	/** Surface stand-ins sharing a portal are spread this many pixels apart on screen. */
	public static final int SPREAD = 18;

	@Getter
	private final List<Tree> trees = new ArrayList<>();
	private final Map<String, Tree> byId = new HashMap<>();
	@Getter
	private final List<Portal> portals = new ArrayList<>();
	@Getter
	private final List<Portal> housePortals = new ArrayList<>();
	@Getter
	private final List<String> globalRequirements = new ArrayList<>();
	/** The menu's title, "Spirit Tree Locations". */
	@Getter
	private final String title;
	/** The colour of an unavailable row's name, "5f5f5f". */
	@Getter
	private final String unavailableColour;
	@Getter
	private final MapIndex index;
	private final Map<String, Layer> layers = new LinkedHashMap<>();
	@Getter
	private final boolean treesLoaded;
	@Getter
	private final boolean indexLoaded;

	/** The open menu's rows by tree id; empty until read. */
	private Map<String, TreeMenu.Row> rows = Collections.emptyMap();
	/** The tree the player stands at, or null. */
	@Getter
	private String here;
	/** The last trip's tree (SPIRIT_TREE_PREVIOUS), or null. */
	@Getter
	private String last;
	/** POH_HOUSE_LOCATION as last placed; -1 until read. */
	@Getter
	private int houseValue = -1;
	/** Changes whenever the rows, here, last or the house change; for cache keys. */
	@Getter
	private int stateHash;

	/**
	 * A tree on another layer, also drawn on the surface at its layer's portal so the overview
	 * shows every destination. {@code slot} is its place among the stand-ins sharing that portal,
	 * centred on it (-0.5 and 0.5 for two), in units of {@link #SPREAD} px.
	 */
	@Value
	public static class StandIn
	{
		Tree tree;
		Portal portal;
		double slot;

		public double screenX(MapView v)
		{
			return v.screenX(portal.getX() + 0.5) + slot * SPREAD;
		}

		public double screenY(MapView v)
		{
			return v.screenY(portal.getY() + 0.5);
		}
	}

	private static final class TreesFile
	{
		String title;
		String unavailableColour;
		Global global;
		List<Tree> trees;
		List<Portal> housePortals;
		List<Portal> portals;
	}

	private static final class Global
	{
		List<String> requirements;
	}

	private TreeRepository(TreesFile file, MapIndex index)
	{
		this.treesLoaded = file != null && file.trees != null;
		this.indexLoaded = index != null;
		this.index = index == null ? MapIndex.empty() : index;
		this.title = file == null || file.title == null ? "Spirit Tree Locations" : file.title;
		this.unavailableColour = file == null || file.unavailableColour == null ? "5f5f5f" : file.unavailableColour;

		for (Layer l : this.index.getLayers())
		{
			if (l != null && l.isValid())
			{
				layers.put(l.getId(), l);
			}
		}
		for (Layer l : Layer.defaults())
		{
			layers.putIfAbsent(l.getId(), l);
		}
		// surface first, the rest in index order
		Map<String, Layer> ordered = new LinkedHashMap<>();
		ordered.put(Layer.SURFACE, layers.remove(Layer.SURFACE));
		ordered.putAll(layers);
		layers.clear();
		layers.putAll(ordered);

		if (treesLoaded)
		{
			for (Tree t : file.trees)
			{
				if (t != null && t.getId() != null && !byId.containsKey(t.getId()))
				{
					trees.add(t);
					byId.put(t.getId(), t);
				}
			}
			addAll(portals, file.portals);
			addAll(housePortals, file.housePortals);
			if (file.global != null && file.global.requirements != null)
			{
				globalRequirements.addAll(file.global.requirements);
			}
		}
		// the house is off the map until its portal is known
		placeHouse(-1);
	}

	/**
	 * Loads from the classpath with the given Gson (the plugin's injected one).
	 *
	 * @param base resource folder ending in "/", normally "/com/spirittreeatlas/"
	 */
	public static TreeRepository load(Gson gson, String base)
	{
		return new TreeRepository(read(gson, base + "trees.json", TreesFile.class), read(gson, base + "map/index.json", MapIndex.class));
	}

	static <T> T read(Gson gson, String path, Class<T> type)
	{
		try (InputStream in = TreeRepository.class.getResourceAsStream(path))
		{
			if (in == null)
			{
				log.debug("resource {} not bundled", path);
				return null;
			}
			try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8))
			{
				return gson.fromJson(reader, type);
			}
		}
		catch (IOException | JsonParseException e)
		{
			log.warn("cannot read {}", path, e);
			return null;
		}
	}

	private static void addAll(List<Portal> to, List<Portal> from)
	{
		for (Portal p : from == null ? Collections.<Portal>emptyList() : from)
		{
			if (p != null && p.getLayer() != null)
			{
				to.add(p);
			}
		}
	}

	// ------------------------------------------------------------------ layers and trees

	public List<Layer> getLayers()
	{
		return new ArrayList<>(layers.values());
	}

	public Layer layer(String id)
	{
		return id == null ? null : layers.get(id);
	}

	public Layer surface()
	{
		return layers.get(Layer.SURFACE);
	}

	/** The layer a world point falls in (surface first), or null. */
	public Layer layerAt(double x, double y)
	{
		for (Layer l : layers.values())
		{
			if (l.contains(x, y))
			{
				return l;
			}
		}
		return null;
	}

	public Tree tree(String id)
	{
		return id == null ? null : byId.get(id);
	}

	/** The house's tree ("Your house (<town>)"), or null when the data has none. */
	public Tree house()
	{
		for (Tree t : trees)
		{
			if (t.isHouse())
			{
				return t;
			}
		}
		return null;
	}

	/** The trees drawn on a layer, in data order. */
	public List<Tree> mappedIn(String layerId)
	{
		List<Tree> out = new ArrayList<>();
		for (Tree t : trees)
		{
			if (t.isMapped() && t.getLayer().equals(layerId))
			{
				out.add(t);
			}
		}
		return out;
	}

	/**
	 * Every tree on another layer that has a portal on the surface, as stand-ins at that portal,
	 * spread when they share it (DESIGN 3.4).
	 */
	public List<StandIn> standIns()
	{
		List<StandIn> out = new ArrayList<>();
		for (Portal p : portals)
		{
			List<Tree> here = Layer.SURFACE.equals(p.getLayer()) ? Collections.<Tree>emptyList() : mappedIn(p.getLayer());
			for (int i = 0; i < here.size(); i++)
			{
				out.add(new StandIn(here.get(i), p, i - (here.size() - 1) / 2.0));
			}
		}
		return out;
	}

	/** The world points of every surface marker (stand-ins at their portal): what Fit fits. */
	public List<Point2D> surfaceMarkers()
	{
		List<Point2D> out = new ArrayList<>();
		for (Tree t : mappedIn(Layer.SURFACE))
		{
			out.add(new Point2D.Double(t.getX(), t.getY()));
		}
		for (StandIn s : standIns())
		{
			out.add(new Point2D.Double(s.getPortal().getX(), s.getPortal().getY()));
		}
		return out;
	}

	// ------------------------------------------------------------------ state per open of the menu

	/** Availability from the open menu's rows (DESIGN 3.4); a tree with no row is absent. */
	public void applyRows(List<TreeMenu.Row> menuRows)
	{
		Map<String, TreeMenu.Row> m = new HashMap<>();
		for (TreeMenu.Row r : menuRows)
		{
			if (r.getTreeId() != null)
			{
				m.putIfAbsent(r.getTreeId(), r);
			}
		}
		rows = m;
		rehash();
	}

	/** The menu row of a tree, or null. */
	public TreeMenu.Row row(String treeId)
	{
		return treeId == null ? null : rows.get(treeId);
	}

	/**
	 * Every tree in the open menu's order (the quick-select panel's rows): the listed trees by
	 * their row, then the trees the menu does not list, in data order (the menu's order today).
	 */
	public List<Tree> menuOrder()
	{
		List<Tree> out = new ArrayList<>(trees);
		// a stable sort: unlisted trees keep their data order after the listed ones
		out.sort(Comparator.comparingInt(t -> rows.containsKey(t.getId()) ? rows.get(t.getId()).getIndex() : Integer.MAX_VALUE));
		return out;
	}

	public Tree.Status status(String treeId)
	{
		TreeMenu.Row r = row(treeId);
		return r == null ? Tree.Status.ABSENT : r.isGrey() ? Tree.Status.LOCKED : Tree.Status.AVAILABLE;
	}

	/** The tree's hotkey as its row shows it, or null. */
	public String key(String treeId)
	{
		TreeMenu.Row r = row(treeId);
		return r == null ? null : r.getKey();
	}

	/**
	 * Where the player is when the menu opens: the tree whose centre is within {@link #HERE_TILES}
	 * tiles on the same plane, or none. Never the house: in a player-owned house (an instance) we
	 * cannot tell whose house it is, and in a friend's, ours is somewhere else to travel to.
	 */
	public void locate(int x, int y, int plane)
	{
		here = null;
		double best = Double.MAX_VALUE;
		for (Tree t : trees)
		{
			double d = Math.max(Math.abs(t.getX() - x), Math.abs(t.getY() - y));
			if (!t.isHouse() && t.isMapped() && t.getPlane() == plane && d <= HERE_TILES && d < best)
			{
				best = d;
				here = t.getId();
			}
		}
		rehash();
	}

	/** The last trip from SPIRIT_TREE_PREVIOUS: the tree with that previous value; none for 0. */
	public void setLast(int value)
	{
		last = null;
		for (Tree t : trees)
		{
			if (value != 0 && t.getPreviousValue() == value && last == null)
			{
				last = t.getId();
			}
		}
		rehash();
	}

	/**
	 * Puts the house's tree at its portal (POH_HOUSE_LOCATION, cache enum 252) on that portal's
	 * layer, named after its town; an unknown value (or no house) takes it off the map.
	 */
	public void placeHouse(int value)
	{
		houseValue = value;
		Tree house = house();
		if (house != null)
		{
			Portal p = null;
			for (Portal hp : housePortals)
			{
				p = hp.getValue() == value ? hp : p;
			}
			if (p != null && layer(p.getLayer()) != null)
			{
				house.place(p.getLayer(), p.getX(), p.getY(), p.getPlane(), p.getTown());
			}
			else
			{
				house.place(null, 0, 0, 0, p == null ? null : p.getTown());
			}
		}
		rehash();
	}

	private void rehash()
	{
		stateHash = Objects.hash(rows, here, last, houseValue);
	}
}
