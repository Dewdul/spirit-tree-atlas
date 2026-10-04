/*
 * Copyright (c) 2026, Dewdul
 * All rights reserved.
 * Licensed under the BSD 2-Clause License. See LICENSE.
 */
package com.fairyringatlas;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.gameval.DBTableID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.util.Text;

/**
 * The ring data: rings.json and map/index.json from the jar, patched with the game's own fairy
 * ring DB table, plus the per-account state read while the interface is open (visited,
 * favourites, last destination).
 */
@Slf4j
public class RingRepository
{
	static final int[] FAVE_VARBITS = {
		VarbitID.FAIRYRING_FAVE_1, VarbitID.FAIRYRING_FAVE_2, VarbitID.FAIRYRING_FAVE_3, VarbitID.FAIRYRING_FAVE_4,
		VarbitID.FAIRYRING_FAVE_5, VarbitID.FAIRYRING_FAVE_6, VarbitID.FAIRYRING_FAVE_7, VarbitID.FAIRYRING_FAVE_8,
		VarbitID.FAIRYRING_FAVE_9, VarbitID.FAIRYRING_FAVE_10,
	};

	@Getter
	private final List<Ring> rings = new ArrayList<>();
	private final Map<String, Ring> byCode = new HashMap<>();
	@Getter
	private final List<Portal> portals = new ArrayList<>();
	@Getter
	private final List<String> globalRequirements = new ArrayList<>();
	@Getter
	private final MapIndex index;
	private final Map<String, Layer> layers = new LinkedHashMap<>();
	@Getter
	private final String base;
	@Getter
	private final boolean ringsLoaded;
	@Getter
	private final boolean indexLoaded;

	/** Log row and star component per code, for all 64 codes (used ones or not). */
	@Getter
	private final Map<String, int[]> logComponents = new LinkedHashMap<>();
	@Getter
	private boolean dbLoaded;

	private final Set<String> visited = new HashSet<>();
	/** Code to favourites-block slot (0..9). */
	private final Map<String, Integer> favourites = new HashMap<>();
	@Getter
	private String lastCode;
	@Getter
	private boolean staffless;
	/** The town of the player's house portal, or null when unknown (or no house). */
	@Getter
	private String houseTown;
	private int houseValue = -1;

	/**
	 * House portals by the value of POH_HOUSE_LOCATION (2187), as Shortest Path reads it. A
	 * portal outside the bundled map (Prifddinas) has no position.
	 */
	private static final Map<Integer, int[]> HOUSE_PORTALS = new HashMap<>();
	private static final Map<Integer, String> HOUSE_TOWNS = new HashMap<>();

	static
	{
		house(1, "Rimmington", 2953, 3224);
		house(2, "Taverley", 2893, 3465);
		house(3, "Pollnivneach", 3340, 3003);
		house(4, "Rellekka", 2670, 3631);
		house(5, "Brimhaven", 2757, 3178);
		house(6, "Yanille", 2544, 3096);
		house(8, "Hosidius", 1743, 3517);
		house(9, "Prifddinas", 0, 0);
		house(13, "Aldarin", 1422, 2963);
	}

	private static void house(int value, String town, int x, int y)
	{
		HOUSE_TOWNS.put(value, town);
		HOUSE_PORTALS.put(value, new int[]{x, y});
	}
	/** Changes whenever visited, favourites, the last destination or an unlock check change; for cache keys. */
	@Getter
	private int stateHash;
	/** Unlock check per code (rings with conditions only), read while the dials are open. */
	private Map<String, UnlockCheck.Result> unlocks = Collections.emptyMap();
	/** The varbits and varps those checks read, so a change to one re-runs them. */
	private Set<Integer> unlockVarbits = Collections.emptySet();
	private Set<Integer> unlockVarps = Collections.emptySet();

	private static final class RingsFile
	{
		List<Ring> rings;
		Global global;
		List<Portal> portals;
	}

	private static final class Global
	{
		List<String> requirements;
	}

	private RingRepository(String base, RingsFile file, MapIndex index)
	{
		this.base = base;
		this.ringsLoaded = file != null && file.rings != null;
		this.indexLoaded = index != null;
		this.index = index == null ? MapIndex.empty() : index;

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
		Layer surface = layers.remove(Layer.SURFACE);
		Map<String, Layer> ordered = new LinkedHashMap<>();
		ordered.put(Layer.SURFACE, surface);
		ordered.putAll(layers);
		layers.clear();
		layers.putAll(ordered);

		if (ringsLoaded)
		{
			for (Ring r : file.rings)
			{
				if (r == null)
				{
					continue;
				}
				rings.add(r);
				if (r.isDialable())
				{
					byCode.put(r.getCode(), r);
				}
			}
			if (file.portals != null)
			{
				portals.addAll(file.portals);
			}
			if (file.global != null && file.global.requirements != null)
			{
				globalRequirements.addAll(file.global.requirements);
			}
		}
	}

	/**
	 * Load from the classpath.
	 *
	 * @param base resource folder ending in "/", normally "/com/fairyringatlas/"
	 */
	public static RingRepository load(Gson gson, String base)
	{
		RingsFile file = read(gson, base + "rings.json", RingsFile.class);
		MapIndex index = read(gson, base + "map/index.json", MapIndex.class);
		return new RingRepository(base, file, index);
	}

	static <T> T read(Gson gson, String path, Class<T> type)
	{
		try (InputStream in = RingRepository.class.getResourceAsStream(path))
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

	public Ring ring(String code)
	{
		return code == null ? null : byCode.get(code);
	}

	/** The layer a world point falls in, or null. */
	public Layer layerAt(int x, int y)
	{
		for (Layer l : layers.values())
		{
			if (l.contains(x + 0.5, y + 0.5))
			{
				return l;
			}
		}
		return null;
	}

	/**
	 * Read DB table 89 once (client thread): positions, log row components, and codes the JSON
	 * does not know yet. Any failure leaves the JSON data alone.
	 */
	public void readDbTable(Client client)
	{
		if (dbLoaded)
		{
			return;
		}
		dbLoaded = true;
		try
		{
			List<Integer> rows = client.getDBTableRows(DBTableID.Fairyring.ID);
			if (rows == null)
			{
				return;
			}
			for (int row : rows)
			{
				String code = DialMath.normalize(string(client, row, DBTableID.Fairyring.COL_CODE));
				if (code == null)
				{
					continue;
				}
				int rowComponent = number(client, row, DBTableID.Fairyring.COL_TEXT_COMPONENT);
				int star = number(client, row, DBTableID.Fairyring.COL_FAVE_ICON_COMPONENT);
				logComponents.put(code, new int[]{rowComponent, star});

				String desc = string(client, row, DBTableID.Fairyring.COL_DESC);
				int coord = number(client, row, DBTableID.Fairyring.COL_DEST_COORD);
				Ring ring = byCode.get(code);
				if (ring == null)
				{
					if (desc == null || desc.isEmpty() || coord <= 0)
					{
						continue;
					}
					int x = (coord >> 14) & 0x3FFF;
					int y = coord & 0x3FFF;
					Layer l = layerAt(x, y);
					// the cache text reads "<br>Region: Place "
					String text = Text.removeTags(desc).trim();
					int colon = text.indexOf(':');
					String name = colon > 0 ? text.substring(colon + 1).trim() : text;
					ring = new Ring(code, name.isEmpty() ? text : name, x, y, l == null ? null : l.getId());
					if (colon > 0)
					{
						ring.setArea(text.substring(0, colon).trim());
					}
					rings.add(ring);
					byCode.put(code, ring);
				}
				ring.setRowComponent(rowComponent);
				ring.setStarComponent(star);
				if (coord > 0 && desc != null && !desc.isEmpty())
				{
					applyCoord(ring, coord);
				}
			}
		}
		catch (RuntimeException e)
		{
			log.warn("fairy ring DB table read failed; using bundled data only", e);
		}
	}

	private void applyCoord(Ring ring, int coord)
	{
		int x = (coord >> 14) & 0x3FFF;
		int y = coord & 0x3FFF;
		int plane = (coord >>> 28) & 3;
		if (x == ring.getX() && y == ring.getY())
		{
			return;
		}
		Layer current = layer(ring.getLayer());
		if (current == null || !current.contains(x + 0.5, y + 0.5))
		{
			Layer l = layerAt(x, y);
			if (l == null)
			{
				return;
			}
			ring.setLayer(l.getId());
		}
		ring.setX(x);
		ring.setY(y);
		ring.setPlane(plane);
	}

	private static Object field(Client client, int row, int column)
	{
		Object[] v = client.getDBTableField(row, column, 0);
		return v == null || v.length == 0 ? null : v[0];
	}

	private static String string(Client client, int row, int column)
	{
		Object o = field(client, row, column);
		return o instanceof String ? (String) o : null;
	}

	private static int number(Client client, int row, int column)
	{
		Object o = field(client, row, column);
		return o instanceof Number ? ((Number) o).intValue() : -1;
	}

	/**
	 * Refresh visited, favourites and last destination (client thread, while the interface is
	 * open). Visited is the log varbit, a non-empty log row, or being a favourite.
	 */
	public void refreshState(Client client)
	{
		favourites.clear();
		for (int i = 0; i < FAVE_VARBITS.length; i++)
		{
			String code = DialMath.fromFavourite(client.getVarbitValue(FAVE_VARBITS[i]));
			if (code != null)
			{
				favourites.put(code, i);
			}
		}
		// the favourites block itself says which row holds which code; it wins over slot order
		for (int i = 0; i < TravelLogController.FAVE_ROWS.length; i++)
		{
			Widget row = client.getWidget(TravelLogController.FAVE_ROWS[i]);
			Widget label = client.getWidget(TravelLogController.FAVE_CODES[i]);
			if (row != null && label != null && row.getText() != null && !row.getText().isEmpty())
			{
				String code = DialMath.normalize(Text.removeTags(label.getText()));
				if (code != null)
				{
					favourites.put(code, i);
				}
			}
		}

		visited.clear();
		for (Ring r : byCode.values())
		{
			boolean v = r.getLogVarbit() > 0 && client.getVarbitValue(r.getLogVarbit()) == 1;
			if (!v && r.getRowComponent() > 0)
			{
				Widget row = client.getWidget(r.getRowComponent());
				v = row != null && row.getText() != null && !row.getText().isEmpty();
			}
			if (v || favourites.containsKey(r.getCode()))
			{
				visited.add(r.getCode());
			}
		}

		lastCode = client.getVarbitValue(VarbitID.FAIRYRING_LASTLOC_SET) == 1
			? DialMath.fromIndex(client.getVarbitValue(VarbitID.FAIRYRING_LASTLOC)) : null;
		staffless = client.getVarbitValue(VarbitID.LUMBRIDGE_DIARY_ELITE_COMPLETE) == 1;
		placeHouse(client.getVarbitValue(VarbitID.POH_HOUSE_LOCATION));
		rehash();
	}

	/**
	 * Puts DIQ (the house's own ring) at the player's house portal on the world map, so it can be
	 * found and zoomed to; with no known portal on the map it stays on the house card only.
	 */
	void placeHouse(int value)
	{
		Ring diq = byCode.get("DIQ");
		houseValue = value;
		houseTown = HOUSE_TOWNS.get(value);
		if (diq == null)
		{
			return;
		}
		int[] at = HOUSE_PORTALS.get(value);
		Layer surface = surface();
		if (at != null && at[0] != 0 && surface != null && surface.contains(at[0], at[1]))
		{
			diq.setX(at[0]);
			diq.setY(at[1]);
			diq.setPlane(0);
			diq.setLayer(Layer.SURFACE);
		}
		else
		{
			diq.setX(0);
			diq.setY(0);
			diq.setLayer(Layer.POH);
		}
	}

	/** A ring's name for display: the house's ring also names the town its portal is in. */
	public String displayName(Ring r)
	{
		return "DIQ".equals(r.getCode()) && houseTown != null ? r.getName() + " (" + houseTown + ")" : r.getName();
	}

	public boolean isVisited(String code)
	{
		return code != null && visited.contains(code);
	}

	public boolean isFavourite(String code)
	{
		return code != null && favourites.containsKey(code);
	}

	/** The favourites-block slot (0..9) showing a code, or -1. */
	public int favouriteSlot(String code)
	{
		Integer s = code == null ? null : favourites.get(code);
		return s == null ? -1 : s;
	}

	/** The favourite rings in favourites-block order. */
	public List<Ring> favouriteRings()
	{
		List<Map.Entry<String, Integer>> slots = new ArrayList<>(favourites.entrySet());
		slots.sort(Map.Entry.<String, Integer>comparingByValue().thenComparing(Map.Entry.comparingByKey()));
		List<Ring> out = new ArrayList<>();
		for (Map.Entry<String, Integer> e : slots)
		{
			Ring r = byCode.get(e.getKey());
			if (r != null)
			{
				out.add(r);
			}
		}
		return out;
	}

	/** Whether the code can be used from the travel log: visited or favourite. */
	public boolean hasLogRow(String code)
	{
		return isVisited(code) || isFavourite(code);
	}

	/** Test and preview hook: mark state without a client. */
	void setState(Set<String> visitedCodes, Map<String, Integer> faves, String last)
	{
		visited.clear();
		visited.addAll(visitedCodes);
		favourites.clear();
		favourites.putAll(faves);
		lastCode = last;
		rehash();
	}

	private void rehash()
	{
		stateHash = java.util.Objects.hash(visited, favourites, lastCode, houseValue, unlocks);
	}

	/**
	 * Whether a first visit can unlock the ring: MET for a ring with no conditions, a hint
	 * (UNKNOWN) until {@link #checkUnlocks} has read the vars.
	 */
	public UnlockCheck.Result unlock(Ring r)
	{
		UnlockCheck.Result u = r == null ? null : unlocks.get(r.getCode());
		return u != null ? u : r == null ? UnlockCheck.MET : UnlockCheck.unchecked(r.getUnlock());
	}

	/** Client thread (the client-backed vars run the quest status script): re-checks every ring's unlock conditions. */
	public void checkUnlocks(UnlockCheck.Vars vars)
	{
		Map<String, UnlockCheck.Result> out = new HashMap<>();
		Set<Integer> varbits = new HashSet<>();
		Set<Integer> varps = new HashSet<>();
		for (Ring r : byCode.values())
		{
			if (r.getUnlock().isEmpty())
			{
				continue;
			}
			out.put(r.getCode(), UnlockCheck.evaluate(r.getUnlock(), vars));
			for (Ring.Condition c : r.getUnlock())
			{
				if ("varbit".equals(c.getType()))
				{
					varbits.add(c.getId());
				}
				else if ("varp".equals(c.getType()))
				{
					varps.add(c.getId());
				}
			}
		}
		unlocks = out;
		unlockVarbits = varbits;
		unlockVarps = varps;
		rehash();
	}

	/** Whether a VarbitChanged for this varbit (-1 for a whole varp) and varp touches an unlock check. */
	public boolean watchesUnlock(int varbitId, int varpId)
	{
		return unlockVarbits.contains(varbitId) || unlockVarps.contains(varpId);
	}

	/** Dialable rings in code order. */
	public List<Ring> dialable()
	{
		List<Ring> out = new ArrayList<>(byCode.values());
		out.sort((a, b) -> a.getCode().compareTo(b.getCode()));
		return out;
	}

	/** Dialable rings of a layer, in code order. */
	public List<Ring> ringsIn(String layerId)
	{
		List<Ring> out = new ArrayList<>();
		for (Ring r : dialable())
		{
			if (layerId.equals(r.getLayer()))
			{
				out.add(r);
			}
		}
		return out;
	}

	/**
	 * The ring a click on a layer's card (or portal) selects: the first one in the travel log,
	 * else the first; null for a layer with no dialable ring.
	 */
	public Ring defaultRing(String layerId)
	{
		List<Ring> rings = ringsIn(layerId);
		for (Ring r : rings)
		{
			if (hasLogRow(r.getCode()))
			{
				return r;
			}
		}
		return rings.isEmpty() ? null : rings.get(0);
	}

	/** Rings on the map layer: destinations plus the informational sequence/exit entries. */
	public List<Ring> mappedIn(String layerId)
	{
		List<Ring> out = new ArrayList<>();
		for (Ring r : rings)
		{
			if (r.isMapped() && layerId.equals(r.getLayer()))
			{
				out.add(r);
			}
		}
		return out;
	}

	public List<Ring> matching(String query)
	{
		if (query == null || query.isEmpty())
		{
			return Collections.emptyList();
		}
		List<Ring> out = new ArrayList<>();
		for (Ring r : dialable())
		{
			if (r.matches(query))
			{
				out.add(r);
			}
		}
		return out;
	}
}
