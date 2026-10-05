/*
 * Copyright (c) 2026, Dewdul
 * All rights reserved.
 * Licensed under the BSD 2-Clause License. See LICENSE.
 */
package com.spirittreeatlas;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import javax.inject.Inject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Menu;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.PostMenuSort;
import net.runelite.api.events.ResizeableChanged;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.ObjectID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.PluginChanged;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.ui.JagexColors;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.Text;

/**
 * Spirit Tree Atlas (DESIGN 4): while the spirit tree menu is open, a zoomable map of every
 * destination covers it. A map click only selects a tree; its real menu row then sits in the
 * map's corner as the Travel button, which the player clicks (or presses the row's key) to go.
 */
@Slf4j
@PluginDescriptor(
	name = "Spirit Tree Atlas",
	description = "Turns the spirit tree menu into a high-resolution, zoomable map of every destination: click a tree, then click Travel or press its key",
	tags = {"spirit", "tree", "map", "teleport", "travel", "transport", "gnome"}
)
public class SpiritTreeAtlasPlugin extends Plugin
{
	static final String RESOURCES = "/com/spirittreeatlas/";
	private static final int REOPEN_TICKS = 3;
	/** About 30 s after the menu closes, its caches are released. */
	private static final int TRIM_TICKS = 50;
	/** Except the overview tiles it opens on: they stay about 5 minutes, for the next tree of a run. */
	private static final int OVERVIEW_TICKS = 500;
	/**
	 * Levels z <= this always count as the overview the menu opens on (FIT_ALL is z=0 at most);
	 * the level of the last view it opened on (z=1 around you) is kept too, see {@link #keepLevel}.
	 */
	private static final int OVERVIEW_LEVEL = 0;
	/** Around you (DESIGN 4.7): the zoom the map opens at, centred on where you are. */
	static final double AROUND_PPT = 2;
	/** A world tile no layer holds: where the player is when we cannot tell (no player, an instance). */
	private static final int NOWHERE = Integer.MIN_VALUE / 2;
	/**
	 * Objects whose "Travel" opens the menu: the travel locs, and the world trees whose menu
	 * entries name them by their multiloc parent (an entry carries the parent's id).
	 */
	private static final Set<Integer> TRAVEL_TREES = Set.of(
		ObjectID.SPIRITTREE_BIG_2OPS, ObjectID.SPIRITTREE_BIG_2OPS_ORBS, ObjectID.SPIRITTREE_SMALL_2OPS,
		ObjectID.SPIRITTREE_PRIF_2OPS, ObjectID.POG_SPIRIT_TREE_ALIVE_STATIC, ObjectID.SPIRIT_TREE_FULLYGROWN,
		ObjectID.POH_SPIRIT_TREE, ObjectID.LEAGUE_5_POH_SPIRIT_TREE, ObjectID.XMAS20_POH_SPIRIT_TREE,
		ObjectID.ENT, ObjectID.STRONGHOLD_ENT, ObjectID.SPIRITTREE_SMALL, ObjectID.SPIRITTREE_PRIF,
		ObjectID.POG_SPIRIT_TREE_MULTI, ObjectID.FARMING_SPIRIT_TREE_PATCH_1, ObjectID.FARMING_SPIRIT_TREE_PATCH_2,
		ObjectID.FARMING_SPIRIT_TREE_PATCH_3, ObjectID.FARMING_SPIRIT_TREE_PATCH_4, ObjectID.FARMING_SPIRIT_TREE_PATCH_5);
	/** Spiritual fairy trees, whose "Tree" opens the menu. */
	private static final Set<Integer> FAIRY_TREES = Set.of(
		ObjectID.POH_SPIRIT_RING, ObjectID.LEAGUE_5_POH_SPIRIT_RING, ObjectID.XMAS20_POH_SPIRIT_RING);
	private static final long ANIM_MS = 250;
	private static final String TELEPORT_MAPS = "com.mjhylkema.TeleportMaps.TeleportMapsPlugin";
	/** Teleport Maps' config group; its {@code showSpiritTreeMap} key turns its spirit tree map on and off. */
	private static final String TELEPORT_MAPS_GROUP = "teleportmaps";
	private static final String SPIRIT_TREE_MENU = "com.spirit.SpiritTreeMenuPlugin";
	static final String TELEPORT_MAPS_NOTICE = "Teleport Maps is showing its spirit tree map - turn that off in Teleport Maps to use Spirit Tree Atlas";
	static final String SPIRIT_TREE_MENU_NOTICE = "Spirit Tree Menu is rearranging this menu - turn it off to use Spirit Tree Atlas";
	/** The other plugin was turned off, but the menu may still hold its changes until it is opened again. */
	static final String REOPEN_NOTICE = "Close and reopen the spirit tree menu to use Spirit Tree Atlas";
	/** {@link #menuFor} while the game's menu holds List mode's "Show Map" entry. */
	private static final String MAP_BUTTON = "mapButton";
	/** Hidden config key: whether the player last left the quick-select panel open. */
	static final String KEY_PANEL_OPEN = "quickSelectOpen";
	/** Maps narrower than this (fixed mode) start with the quick-select panel closed. */
	static final int NARROW_MAP = 700;
	/** How far one wheel notch scrolls the quick-select panel: two rows. */
	static final int PANEL_WHEEL_STEP = 2 * (ChromePainter.ROW_H + ChromePainter.ROW_GAP);
	/** A tree's marker counts as in view only this far (px) inside the map's free area. */
	private static final int IN_VIEW_MARGIN = 16;

	enum Mode
	{
		/** The map covers the menu; only the close button and the Travel row show through. */
		MAP,
		/** The plain menu, with only a floating Map button. */
		LIST,
	}

	@Inject
	private Client client;
	@Inject
	private ClientThread clientThread;
	@Inject
	private SpiritTreeAtlasConfig config;
	@Inject
	private ConfigManager configManager;
	@Inject
	private OverlayManager overlayManager;
	@Inject
	private MouseManager mouseManager;
	@Inject
	private PluginManager pluginManager;
	@Inject
	private Gson gson;
	@Inject
	private AtlasOverlay overlay;
	@Inject
	private RowBackdrop backdrop;
	@Inject
	private AtlasInput input;

	@Getter
	private TreeRepository repo;
	@Getter
	private TileStore tiles;
	private ExecutorService executor;
	/** The menu's widgets: recognising, reading, and Map mode's changes (client thread). */
	@Getter
	private TreeMenu menu;

	// --- shared with the input thread and the overlays
	@Getter
	private volatile boolean open;
	@Getter
	private volatile Mode mode = Mode.MAP;
	/** While another plugin owns this menu (DESIGN 4.10): the one line the overlay shows instead of the map. */
	@Getter
	private volatile String notice;
	@Getter
	private volatile String selected;
	private volatile boolean menuOpen;
	private final Object viewLock = new Object();
	private volatile MapView view;
	private MapView animFrom;
	private MapView animTo;
	private long animStart;
	private volatile List<Hit> hits = Collections.emptyList();
	private volatile List<Rectangle> holes = Collections.emptyList();
	/** List mode's Map button as last drawn, or null. */
	@Getter
	private volatile Rectangle mapButton;
	/** The Travel hole as last drawn (the real row shows there), or null; RowBackdrop fills it. */
	@Getter
	private volatile Rectangle travelHole;
	/** What the game's current menu was built for (see {@link #menuKey(Hit)}); null when not ours. */
	private volatile String menuFor;
	/** How far the quick-select panel's rows are scrolled, and the most that is useful (last frame). */
	@Getter
	private volatile int panelScroll;
	private volatile int panelScrollMax;
	/** Whether the quick-select panel is open on the map as last laid out; see {@link #isPanelOpen(int)}. */
	private volatile boolean panelOpenNow;
	/** The player's open or close as saved ({@link #KEY_PANEL_OPEN}; open until they close it). */
	private boolean panelOpenSaved = true;
	/** The player's open or close this session; null until they choose (then narrow maps start closed). */
	private Boolean panelChoice;

	// --- client thread state
	/**
	 * Whether another plugin owned this menu when it was built (opened or rebuilt), which is when
	 * those plugins change it; it may still hold their changes after they are turned off.
	 */
	private boolean claimed;
	private boolean needsInitialView;
	private int playerX = NOWHERE;
	private int playerY = NOWHERE;
	/** Whether the player was in an instance (a house) when the menu opened. */
	private boolean inInstance;
	/**
	 * The coarsest level kept longest after a close: that of the last view the menu opened on (or
	 * was loaded ahead for), at least the overview's (DESIGN 4.9).
	 */
	private int keepLevel = OVERVIEW_LEVEL;
	private int closedTick = -100;
	private Mode closedMode = Mode.MAP;
	private MapView sessionView;
	private final Map<String, MapView> layerViews = new HashMap<>();
	/**
	 * Whether we turned off the game's mouse-over text (top-left of the screen) for the map, which
	 * shows its own hover card; it is turned back on as soon as the map is not showing.
	 */
	private boolean mouseoverHiddenByUs;
	/** The menu's slot, moved into the free space while the map shows in resizable mode. */
	private final ModalSlot modalSlot = new ModalSlot();
	/** Whether the decoded tiles and map-sized caches were released since the menu closed. */
	private boolean trimmed = true;
	/** Whether all but the overview tiles (and the map-sized caches) were released. */
	private boolean fineTrimmed = true;
	/** When a click on a tree last started loading the map ahead of the menu. */
	private int warmTick = -100;

	@Provides
	SpiritTreeAtlasConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(SpiritTreeAtlasConfig.class);
	}

	@Override
	protected void startUp()
	{
		repo = TreeRepository.load(gson, RESOURCES);
		executor = TileStore.newExecutor();
		tiles = new TileStore(repo.getIndex(), repo.getLayers(), RESOURCES, executor);
		menu = new TreeMenu(client);
		overlayManager.add(backdrop);
		overlayManager.add(overlay);
		mouseManager.registerMouseListener(input);
		mouseManager.registerMouseWheelListener(input);
		log.debug("started: trees={} index={} imagery={}", repo.isTreesLoaded(), repo.isIndexLoaded(), tiles.hasImagery());
		clientThread.invokeLater(() ->
		{
			if (client.getGameState() != GameState.LOGGED_IN)
			{
				return;
			}
			repo.placeHouse(client.getVarbitValue(VarbitID.POH_HOUSE_LOCATION));
			// the menu may already be open
			TreeMenu.Style s = menu.openStyle();
			if (!open && s != null && TreeMenu.isTitle(menu.title(s), repo.getTitle()))
			{
				openMenu(s);
			}
		});
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		overlayManager.remove(backdrop);
		mouseManager.unregisterMouseListener(input);
		mouseManager.unregisterMouseWheelListener(input);
		input.reset();
		open = false;
		// the rest is client-thread state (the overlay may be mid-frame on it right now); the
		// overlays are already removed, so nothing renders after this runs
		TreeMenu m = menu;
		ExecutorService ex = executor;
		TileStore store = tiles;
		clientThread.invoke(() ->
		{
			// again here: a tick already running on the client thread may have opened it meanwhile
			open = false;
			m.close();
			modalSlot.restore(client);
			restoreMouseoverText();
			overlay.reset();
			view = null;
			selected = null;
			notice = null;
			menuFor = null;
			hits = Collections.emptyList();
			holes = Collections.emptyList();
			travelHole = null;
			mapButton = null;
			ex.shutdownNow();
			store.close();
		});
	}

	// ------------------------------------------------------------------ open / close

	/**
	 * The setup script of the open menu is about to run again (a rebuild, or another menu built in
	 * the same interface): put the menu back as the game made it first, so the script works on
	 * the game's own state, and whatever is hidden or moved during the rebuild (by the script, or
	 * by another plugin after it, such as Better Teleport Menu's taller classic list hiding its
	 * scrollbar) is never taken for ours.
	 */
	@Subscribe
	public void onScriptPreFired(ScriptPreFired e)
	{
		if (open && TreeMenu.Style.forScript(e.getScriptId()) == menu.getStyle())
		{
			menu.restore();
		}
	}

	/**
	 * The setup script of either menu ran (the layout is final): open on the spirit tree's
	 * title, rebuild when it ran again inside the open menu, and treat any other title on our
	 * interface as a close (DESIGN 3.3).
	 */
	@Subscribe
	public void onScriptPostFired(ScriptPostFired e)
	{
		TreeMenu.Style s = TreeMenu.Style.forScript(e.getScriptId());
		if (s == null)
		{
			return;
		}
		boolean ours = TreeMenu.isTitle(menu.title(s), repo.getTitle());
		boolean same = open && menu.getStyle() == s;
		if (!ours)
		{
			if (same)
			{
				closeMenu();
			}
			return;
		}
		if (same)
		{
			readRows();
			checkOwner(true);
			applyMenu();
			return;
		}
		if (open)
		{
			closeMenu();
		}
		openMenu(s);
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded e)
	{
		if (modalSlot.holds())
		{
			// before the new interface's first frame: if it took the menu's slot (or the toplevel
			// changed and the menu moved to the new one), put the slot back now rather than at the
			// next tick
			updateSlot();
		}
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed e)
	{
		// not unloaded: the interface is only being moved (to the other toplevel when switching
		// between fixed and resizable) and stays open, with our changes
		if (open && e.isUnload() && TreeMenu.Style.forGroup(e.getGroupId()) == menu.getStyle())
		{
			closeMenu();
		}
	}

	private void openMenu(TreeMenu.Style s)
	{
		int tick = client.getTickCount();
		boolean reopen = tick >= closedTick && tick - closedTick <= REOPEN_TICKS;
		open = true;
		trimmed = false;
		fineTrimmed = false;
		mode = reopen ? closedMode : config.openInMapMode() ? Mode.MAP : Mode.LIST;
		if (!reopen)
		{
			selected = null;
			panelScroll = 0;
			needsInitialView = true;
		}
		panelOpenSaved = !"false".equals(hiddenSetting(KEY_PANEL_OPEN));
		menu.open(s);
		repo.placeHouse(client.getVarbitValue(VarbitID.POH_HOUSE_LOCATION));
		repo.setLast(client.getVarbitValue(VarbitID.SPIRIT_TREE_PREVIOUS));
		readRows();
		locatePlayer();
		claimed = false;
		checkOwner(true);
		applyMenu();
	}

	private void readRows()
	{
		menu.rebuilt(repo.getTrees(), repo.getUnavailableColour());
		repo.applyRows(menu.getRows());
	}

	private void closeMenu()
	{
		open = false;
		closedTick = client.getTickCount();
		closedMode = mode;
		MapView v = view;
		if (v != null)
		{
			sessionView = v;
		}
		synchronized (viewLock)
		{
			animTo = null;
		}
		input.reset();
		menu.close();
		modalSlot.restore(client);
		restoreMouseoverText();
		notice = null;
		claimed = false;
		menuFor = null;
		hits = Collections.emptyList();
		holes = Collections.emptyList();
		travelHole = null;
		mapButton = null;
	}

	/** Client thread: releases the decoded tiles and the map-sized buffers while the menu is closed. */
	private void trimCaches()
	{
		trimmed = true;
		fineTrimmed = true;
		tiles.trim();
		overlay.releaseCaches();
	}

	/**
	 * Client thread, while the menu is closed: releases its caches as {@link #trimCaches} does in
	 * two steps (DESIGN 4.9). The overview tiles it opens on stay longest, so the next tree of a
	 * farming run opens at once; the login screen still releases everything.
	 */
	private void trimWhileClosed()
	{
		int tick = client.getTickCount();
		int since = tick - Math.max(closedTick, warmTick);
		boolean reset = tick < closedTick || tick < warmTick;
		if (!trimmed && (since >= OVERVIEW_TICKS || reset))
		{
			trimCaches();
		}
		else if (!fineTrimmed && since >= TRIM_TICKS)
		{
			fineTrimmed = true;
			tiles.trimFinerThan(keepLevel);
			overlay.releaseCaches();
		}
	}

	/**
	 * DESIGN 4.9: "Travel" on a spirit tree (or "Tree" on a spiritual fairy tree) opens the menu once
	 * the player gets there, so the map it will open on starts loading now, from our own bundled
	 * tiles. The click itself is left alone.
	 */
	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked e)
	{
		if (!open && config.openInMapMode() && opensTreeMenu(e.getMenuAction(), e.getId(), e.getMenuOption(), e.getMenuTarget()))
		{
			prewarm(e.getParam0(), e.getParam1());
		}
	}

	/** Whether a clicked menu entry is a spirit tree's "Travel" (or a spiritual fairy tree's "Tree"). */
	static boolean opensTreeMenu(MenuAction action, int id, String option, String target)
	{
		if (action != MenuAction.GAME_OBJECT_FIRST_OPTION && action != MenuAction.GAME_OBJECT_SECOND_OPTION
			&& action != MenuAction.GAME_OBJECT_THIRD_OPTION && action != MenuAction.GAME_OBJECT_FOURTH_OPTION
			&& action != MenuAction.GAME_OBJECT_FIFTH_OPTION)
		{
			return false;
		}
		// by name too, should an id change
		String name = target == null ? "" : Text.removeTags(target).trim();
		if ("Travel".equalsIgnoreCase(option))
		{
			return TRAVEL_TREES.contains(id) || "Spirit tree".equalsIgnoreCase(name);
		}
		return "Tree".equalsIgnoreCase(option) && (FAIRY_TREES.contains(id) || "Spiritual Fairy Tree".equalsIgnoreCase(name));
	}

	/**
	 * Queues the tiles of the view the menu will open on: the initial view (4.7) on the last map
	 * rect of the session or, before the first open, the rect the layout would give now, as seen
	 * from the clicked tree, where the player will stand.
	 *
	 * @param sceneX the clicked object's scene tile (the menu entry's param0)
	 * @param sceneY its param1
	 */
	private void prewarm(int sceneX, int sceneY)
	{
		if (!tiles.hasImagery())
		{
			return;
		}
		MapView last = view;
		Rectangle rect = last != null ? last.rect() : expectedRect();
		WorldView wv = client.getTopLevelWorldView();
		repo.placeHouse(client.getVarbitValue(VarbitID.POH_HOUSE_LOCATION));
		MapView v;
		if (wv == null)
		{
			locatePlayer();
			v = initialView(rect);
		}
		else
		{
			boolean instance = wv.isInstance();
			int x = instance ? NOWHERE : wv.getBaseX() + sceneX;
			int y = instance ? NOWHERE : wv.getBaseY() + sceneY;
			v = initialView(config.openOn(), repo, repo.tree(repo.treeAt(x, y, wv.getPlane())), x, y, instance, sessionView, rect, insets(rect));
		}
		MapRenderer.prefetch(v, tiles);
		keep(v);
		trimmed = false;
		fineTrimmed = false;
		warmTick = client.getTickCount();
	}

	/** The tiles of the level this view opens on (and coarser) stay longest after a close (DESIGN 4.9). */
	private void keep(MapView v)
	{
		if (tiles != null)
		{
			keepLevel = Math.max(OVERVIEW_LEVEL, tiles.levelFor(v.getPpt()));
		}
	}

	/** The map rect before the first open: the slot in fixed mode; else the free space as the caps and the layout allow. */
	private Rectangle expectedRect()
	{
		int w = ModalSlot.SLOT_W;
		int h = ModalSlot.SLOT_H;
		if (!client.isResized())
		{
			return new Rectangle(0, 0, w, h);
		}
		Rectangle canvas = MapLayout.canvas(client);
		List<Rectangle> obstacles = MapLayout.obstacles(client);
		int maxW = config.mapMaxWidth();
		int maxH = config.mapMaxHeight();
		java.awt.Point corner = config.useFreeSpace() ? MapLayout.slotCorner(canvas, canvas, w, h, obstacles, maxW, maxH) : null;
		Rectangle slot = corner != null ? new Rectangle(corner.x - w, corner.y - h, w, h)
			: new Rectangle(canvas.x + (canvas.width - w) / 2, canvas.y + (canvas.height - h) / 2, w, h);
		return MapLayout.compute(canvas, slot, obstacles, maxW, maxH);
	}

	private void locatePlayer()
	{
		Player p = client.getLocalPlayer();
		WorldView wv = client.getTopLevelWorldView();
		inInstance = wv != null && wv.isInstance();
		if (p == null || inInstance)
		{
			// an instance's tiles are not the world's: no tree is "here" in a house (3.4)
			playerX = NOWHERE;
			playerY = NOWHERE;
			repo.locate(NOWHERE, NOWHERE, -1);
			return;
		}
		WorldPoint wp = p.getWorldLocation();
		playerX = wp.getX();
		playerY = wp.getY();
		repo.locate(wp.getX(), wp.getY(), wp.getPlane());
	}

	/**
	 * DESIGN 4.10: whether to step aside, checked at open, at each rebuild and whenever a plugin
	 * or Teleport Maps' settings change while the menu is open. Stepping aside takes effect at
	 * once (everything is put back). It ends at once too, unless the other plugin had the menu
	 * when it was built: those plugins change the menu then, so the map waits for a reopen.
	 *
	 * @param built the menu has just been opened or rebuilt
	 */
	private void checkOwner(boolean built)
	{
		String n = stepAside(menu.getStyle());
		claimed |= built && n != null;
		String was = notice;
		notice = n != null ? n : claimed ? REOPEN_NOTICE : null;
		if (was == null && notice != null)
		{
			input.reset();
		}
	}

	/**
	 * DESIGN 4.10: the notice when another plugin owns this menu (Teleport Maps showing its own
	 * spirit tree map; Spirit Tree Menu rearranging the classic one), else null. We then change
	 * nothing. Never declared as a conflict: that would turn off all of Teleport Maps' maps.
	 */
	String stepAside(TreeMenu.Style s)
	{
		try
		{
			for (Plugin p : pluginManager.getPlugins())
			{
				String name = p.getClass().getName();
				if (TELEPORT_MAPS.equals(name) && pluginManager.isPluginEnabled(p)
					&& !"false".equals(configManager.getConfiguration(TELEPORT_MAPS_GROUP, "showSpiritTreeMap")))
				{
					return TELEPORT_MAPS_NOTICE;
				}
				if (SPIRIT_TREE_MENU.equals(name) && s == TreeMenu.Style.CLASSIC && pluginManager.isPluginEnabled(p))
				{
					return SPIRIT_TREE_MENU_NOTICE;
				}
			}
		}
		catch (RuntimeException e)
		{
			log.debug("plugin scan failed", e);
		}
		return null;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged e)
	{
		GameState state = e.getGameState();
		// a map reload or a brief reconnect keeps the interfaces (and our changes) alive; a real
		// close is still caught by WidgetClosed or the per-tick visibility check
		if (state == GameState.LOGGED_IN || state == GameState.LOADING || state == GameState.CONNECTION_LOST)
		{
			return;
		}
		// the slot is a toplevel component the game may keep or reload: put back what we wrote to it
		modalSlot.restore(client);
		if (open)
		{
			// logout or hop: the interfaces go. Whatever is still the live widget for its id is put
			// back; widgets the game has already let go of are left alone
			closeMenu();
		}
		if (state == GameState.LOGIN_SCREEN)
		{
			trimCaches();
		}
	}

	@Subscribe
	public void onClientTick(ClientTick e)
	{
		menuOpen = client.isMenuOpen();
	}

	@Subscribe
	public void onGameTick(GameTick e)
	{
		if (open && !isStillOurs())
		{
			closeMenu();
		}
		else if (open)
		{
			// another plugin may re-text the rows (and so rebind their keys) while the menu is open
			if (menu.read(repo.getTrees(), repo.getUnavailableColour()))
			{
				repo.applyRows(menu.getRows());
			}
			applyMenu();
		}
		else
		{
			// a missed setup script: the same title test on whichever menu is up
			TreeMenu.Style s = menu.openStyle();
			if (s != null && TreeMenu.isTitle(menu.title(s), repo.getTitle()))
			{
				openMenu(s);
			}
		}
		if (!open)
		{
			if (modalSlot.holds())
			{
				modalSlot.restore(client);
			}
			// free the decoded tiles and map-sized buffers once the menu has been left alone for a
			// while; a quick reopen stays warm
			trimWhileClosed();
		}
	}

	/**
	 * Whether the tracked menu is still open and still the spirit tree's (hard rule 8): a menu
	 * built in the same interface by a script we do not hook is caught here. The title is not
	 * checked while stepping aside: Teleport Maps deletes the classic one.
	 */
	private boolean isStillOurs()
	{
		TreeMenu.Style s = menu.getStyle();
		return menu.isOpen(s) && (notice != null || TreeMenu.isTitle(menu.title(s), repo.getTitle()));
	}

	@Subscribe
	public void onResizeableChanged(ResizeableChanged e)
	{
		if (open || modalSlot.holds())
		{
			updateSlot();
		}
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged e)
	{
		if (e.getVarbitId() == VarbitID.POH_HOUSE_LOCATION)
		{
			repo.placeHouse(e.getValue());
		}
		else if (e.getVarbitId() == VarbitID.SPIRIT_TREE_PREVIOUS)
		{
			repo.setLast(e.getValue());
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged e)
	{
		if (SpiritTreeAtlasConfig.GROUP.equals(e.getGroup()) || TELEPORT_MAPS_GROUP.equals(e.getGroup()))
		{
			clientThread.invoke(() ->
			{
				if (open)
				{
					checkOwner(false);
					applyMenu();
				}
				else if (modalSlot.holds())
				{
					updateSlot();
				}
			});
		}
	}

	/** Teleport Maps or Spirit Tree Menu turned on or off while the menu is open: step aside or back (DESIGN 4.10). */
	@Subscribe
	public void onPluginChanged(PluginChanged e)
	{
		clientThread.invoke(() ->
		{
			if (open)
			{
				checkOwner(false);
				applyMenu();
			}
		});
	}

	// ------------------------------------------------------------------ the menu's widgets

	/**
	 * Map mode's widget changes (DESIGN 4.3) with the selected tree's row as the Travel button
	 * when it is usable; in List mode or while stepping aside, everything put back. Run on open,
	 * after every rebuild, each tick and whenever the selection changes.
	 */
	private void applyMenu()
	{
		if (!open)
		{
			return;
		}
		if (mode == Mode.MAP && notice == null)
		{
			TreeMenu.Row row = repo.row(selected);
			menu.apply(Scene.rowShown(selected, row, repo.getHere()) ? row : null);
			if (client.isMouseoverTextEnabled())
			{
				client.setMouseoverTextEnabled(false);
				mouseoverHiddenByUs = true;
			}
		}
		else
		{
			menu.restore();
			restoreMouseoverText();
		}
		updateSlot();
	}

	/**
	 * Resizable mode, "Use free space": while the map shows, the menu's slot sits in the corner of
	 * the free space (so the map fills it); otherwise it is where the game put it.
	 */
	private void updateSlot()
	{
		boolean wanted = open && mode == Mode.MAP && notice == null && config.useFreeSpace() && client.isResized();
		TreeMenu.Style s = menu.getStyle();
		modalSlot.update(client, s == null ? -1 : s.root(), wanted, config.mapMaxWidth(), config.mapMaxHeight());
	}

	/** Turn the game's mouse-over text back on, if we turned it off. */
	private void restoreMouseoverText()
	{
		if (mouseoverHiddenByUs)
		{
			client.setMouseoverTextEnabled(true);
			mouseoverHiddenByUs = false;
		}
	}

	void setMode(Mode m)
	{
		mode = m;
		input.reset();
		if (m == Mode.LIST)
		{
			hits = Collections.emptyList();
			holes = Collections.emptyList();
			travelHole = null;
		}
		applyMenu();
	}

	// ------------------------------------------------------------------ selection

	/** Client thread: selects a tree; its real row, when usable, moves into the corner at once. */
	void select(String id)
	{
		if (repo.tree(id) == null)
		{
			return;
		}
		selected = id;
		applyMenu();
	}

	void clearSelection()
	{
		selected = null;
		applyMenu();
	}

	// ------------------------------------------------------------------ view (input thread safe)

	/** Called by the overlay each frame: lays the view onto the live map rectangle and steps the animation. */
	MapView frameView(Rectangle rect)
	{
		synchronized (viewLock)
		{
			if (view == null || needsInitialView)
			{
				view = initialView(rect);
				needsInitialView = false;
				keep(view);
			}
			if (animTo != null)
			{
				double t = (System.currentTimeMillis() - animStart) / (double) ANIM_MS;
				view = MapView.interpolate(animFrom, animTo, MapView.easeOut(t)).withRect(rect);
				if (t >= 1)
				{
					animTo = null;
				}
			}
			else
			{
				view = view.withRect(rect);
			}
			return view;
		}
	}

	/** DESIGN 4.7: the view the menu opens on, from where the player stood when it opened. */
	private MapView initialView(Rectangle rect)
	{
		return initialView(config.openOn(), repo, repo.tree(repo.getHere()), playerX, playerY, inInstance, sessionView, rect, insets(rect));
	}

	/**
	 * DESIGN 4.7: the view the menu opens on. Around you: centred at {@link #AROUND_PPT} on the
	 * tree you are at, else on your own tile when a layer holds it, on that tree's or tile's layer
	 * (Prifddinas too); in an instance, on your house's portal when the house is placed. Fit all:
	 * every surface marker, or the Prifddinas layer when you are at its tree. Remember: the
	 * session's last view. Whatever does not apply fits every surface marker.
	 *
	 * @param here     the tree the player is at, or null (never the house)
	 * @param x        the player's world tile, or a tile no layer holds when unknown
	 * @param instance the player is in an instance (a house; whose, the client cannot tell)
	 */
	static MapView initialView(SpiritTreeAtlasConfig.OpenAt openAt, TreeRepository repo, Tree here, double x, double y,
		boolean instance, MapView remembered, Rectangle rect, Insets in)
	{
		boolean hereMapped = here != null && here.isMapped();
		switch (openAt)
		{
			case REMEMBER:
				Layer l = remembered == null ? null : repo.layer(remembered.getLayer());
				if (l != null)
				{
					return remembered.withRect(rect).withLayer(l);
				}
				break;
			case AROUND_YOU:
				// in a house, its portal: the view only; the house is never "here" (3.4)
				Tree at = instance ? repo.house() : hereMapped ? here : null;
				Layer on;
				if (at != null)
				{
					on = at.isMapped() ? repo.layer(at.getLayer()) : null;
					x = at.getX();
					y = at.getY();
				}
				else
				{
					on = instance ? null : repo.layerAt(x + 0.5, y + 0.5);
				}
				if (on != null)
				{
					return MapView.of(on, rect).focusOn(x + 0.5, y + 0.5, AROUND_PPT, in);
				}
				break;
			default:
				Layer hl = hereMapped && !Layer.SURFACE.equals(here.getLayer()) ? repo.layer(here.getLayer()) : null;
				if (hl != null)
				{
					return fitLayer(hl, rect, in);
				}
				break;
		}
		return fitTrees(MapView.of(repo.surface(), rect), repo.surfaceMarkers(), in);
	}

	/**
	 * Screen space the chrome takes from the map: the top bar and, down the left edge, the
	 * quick-select panel or its tab.
	 *
	 * @param panel the panel's (or tab's) width; 0 when it is off
	 */
	static Insets chromeInsets(int panel)
	{
		return new Insets(ChromePainter.BAR_H, panel > 0 ? 6 + panel : 0, 0, 0);
	}

	/** The chrome's insets on a map this wide, with the quick-select panel as it is now. */
	private Insets insets(Rectangle rect)
	{
		return chromeInsets(!config.quickSelect() ? 0 : isPanelOpen(rect.width) ? overlay.panelWidth(rect.width) : ChromePainter.TAB_W);
	}

	/** A layer's view fitted to its bounds, clear of the chrome. */
	static MapView fitLayer(Layer l, Rectangle rect, Insets in)
	{
		MapView v = MapView.of(l, rect);
		return v.fit(v.getBx0(), v.getBy0(), v.getBx1(), v.getBy1(), 12, in);
	}

	/** A view fitting the given marker points (tree centres), or the layer bounds when there are none. */
	static MapView fitTrees(MapView v, List<Point2D> points, Insets in)
	{
		if (points.isEmpty())
		{
			return v.fit(v.getBx0(), v.getBy0(), v.getBx1(), v.getBy1(), 12, in);
		}
		double x0 = Double.MAX_VALUE;
		double y0 = Double.MAX_VALUE;
		double x1 = -Double.MAX_VALUE;
		double y1 = -Double.MAX_VALUE;
		for (Point2D p : points)
		{
			x0 = Math.min(x0, p.getX());
			y0 = Math.min(y0, p.getY());
			x1 = Math.max(x1, p.getX() + 1);
			y1 = Math.max(y1, p.getY() + 1);
		}
		return v.fit(x0, y0, x1, y1, 40, in);
	}

	void setView(MapView v)
	{
		synchronized (viewLock)
		{
			animTo = null;
			view = v;
		}
	}

	void animateTo(MapView target)
	{
		synchronized (viewLock)
		{
			if (view == null)
			{
				view = target;
				return;
			}
			animFrom = view;
			animTo = target.withRect(view.rect());
			animStart = System.currentTimeMillis();
		}
	}

	/** Input thread: zoom about the cursor. */
	void zoomAt(int x, int y, double factor)
	{
		synchronized (viewLock)
		{
			if (view != null)
			{
				animTo = null;
				view = view.zoomAbout(x, y, factor);
			}
		}
	}

	/** Input thread: drag the map. */
	void panBy(int dx, int dy)
	{
		synchronized (viewLock)
		{
			if (view != null)
			{
				animTo = null;
				view = view.panBy(dx, dy);
			}
		}
	}

	/** Zoom about the centre, from where a running animation is heading so quick clicks compound evenly. */
	void zoomCentre(double factor)
	{
		synchronized (viewLock)
		{
			MapView v = view;
			if (v != null)
			{
				MapView from = animTo != null ? animTo : v;
				animateTo(from.zoomAbout(from.getX() + from.getW() / 2.0, from.getY() + from.getH() / 2.0, factor));
			}
		}
	}

	void zoomTo(Tree t)
	{
		if (t == null || !t.isMapped() || view == null)
		{
			return;
		}
		openLayer(t.getLayer());
		MapView v = view;
		animateTo(v.focusOn(t.getX() + 0.5, t.getY() + 0.5, Math.max(v.getPpt() * 2, 4), insets(v.rect())));
	}

	/**
	 * A quick-select row: select the tree, exactly as a click on its marker does, and when its
	 * marker (on the surface, a Prifddinas tree's stand-in) is not in view clear of the chrome,
	 * pan there at the current zoom. A tree on another layer than the one shown, with no stand-in
	 * here, opens its layer.
	 */
	void showTree(String id)
	{
		Tree t = repo.tree(id);
		if (t == null)
		{
			return;
		}
		select(id);
		MapView v = view;
		if (v == null || !t.isMapped())
		{
			return;
		}
		Point2D at = onScreen(v, t);
		if (at == null)
		{
			openLayer(t.getLayer());
			v = view;
			at = onScreen(v, t);
			if (at == null)
			{
				return;
			}
		}
		Insets in = insets(v.rect());
		Rectangle free = new Rectangle(v.getX() + in.left + IN_VIEW_MARGIN, v.getY() + in.top + IN_VIEW_MARGIN,
			v.getW() - in.left - in.right - 2 * IN_VIEW_MARGIN, v.getH() - in.top - in.bottom - 2 * IN_VIEW_MARGIN);
		if (!free.contains(at))
		{
			animateTo(v.focusOn(v.worldX(at.getX()), v.worldY(at.getY()), v.getPpt(), in));
		}
	}

	/** Where a tree shows on this view: its marker on its own layer, its stand-in on the surface; else null. */
	private Point2D onScreen(MapView v, Tree t)
	{
		if (v.getLayer().equals(t.getLayer()))
		{
			return new Point2D.Double(v.screenX(t.getX() + 0.5), v.screenY(t.getY() + 0.5));
		}
		if (Layer.SURFACE.equals(v.getLayer()))
		{
			for (TreeRepository.StandIn si : repo.standIns())
			{
				if (si.getTree() == t)
				{
					return new Point2D.Double(si.screenX(v), si.screenY(v));
				}
			}
		}
		return null;
	}

	/** Fit: every surface marker on the surface, the layer's bounds elsewhere. */
	void fit()
	{
		MapView v = view;
		if (v != null)
		{
			Insets in = insets(v.rect());
			animateTo(Layer.SURFACE.equals(v.getLayer()) ? fitTrees(v, repo.surfaceMarkers(), in)
				: v.fit(v.getBx0(), v.getBy0(), v.getBx1(), v.getBy1(), 12, in));
		}
	}

	/** A portal or a stand-in: open its layer fitted, or fit it again when it is already open. */
	void openLayerFitted(String id)
	{
		Layer l = repo.layer(id);
		MapView v = view;
		if (l != null && v != null && !l.isSurface() && l.getId().equals(v.getLayer()))
		{
			animateTo(fitLayer(l, v.rect(), insets(v.rect())));
			return;
		}
		openLayer(id);
	}

	/** Switch to a layer: the surface comes back where it was left, other layers open fitted. */
	void openLayer(String id)
	{
		Layer l = repo.layer(id);
		MapView v = view;
		if (l == null || v == null || l.getId().equals(v.getLayer()))
		{
			return;
		}
		layerViews.put(v.getLayer(), v);
		MapView saved = Layer.SURFACE.equals(id) ? layerViews.get(id) : null;
		setView(saved != null ? saved.withRect(v.rect()) : fitLayer(l, v.rect(), insets(v.rect())));
	}

	/**
	 * Input thread: a left click (pressed and released without dragging) on a marker or portal;
	 * does what its left-click menu entry does. A press there that turns into a drag pans instead.
	 */
	void clickHit(Hit hit)
	{
		clientThread.invoke(() ->
		{
			if (!open || hit == null)
			{
				return;
			}
			if (hit.getKind() == Hit.Kind.PORTAL)
			{
				openLayerFitted(hit.getId());
			}
			else if (hit.getTree() != null)
			{
				select(hit.getTree().getId());
			}
		});
	}

	// ------------------------------------------------------------------ hit testing (input thread safe)

	/**
	 * The overlay's results of a frame: hits and holes in Map mode, the Map button in List mode,
	 * the Travel hole, and how far the quick-select panel can scroll.
	 */
	void publish(List<Hit> newHits, List<Rectangle> newHoles, Rectangle newMapButton, Rectangle newTravelHole, int newPanelScrollMax)
	{
		hits = newHits;
		holes = newHoles;
		mapButton = newMapButton;
		travelHole = newTravelHole;
		panelScrollMax = newPanelScrollMax;
		if (panelScroll > newPanelScrollMax)
		{
			panelScroll = newPanelScrollMax;
		}
	}

	// ------------------------------------------------------------------ quick-select panel

	/** Whether the quick-select panel's rows overflow it, so the wheel scrolls them rather than zooming. */
	boolean canScrollPanel()
	{
		return panelScrollMax > 0;
	}

	/** Input thread: scroll the quick-select panel. */
	void scrollPanel(int dy)
	{
		panelScroll = Math.max(0, Math.min(panelScrollMax, panelScroll + dy));
	}

	/**
	 * Whether the quick-select panel is open on a map this wide: as the player last left it, but
	 * closed on a narrow (fixed mode) map until they open it there; a choice made this session
	 * holds at any size.
	 */
	boolean isPanelOpen(int mapWidth)
	{
		panelOpenNow = panelChoice != null ? panelChoice : panelOpenSaved && mapWidth >= NARROW_MAP;
		return panelOpenNow;
	}

	/** Client thread: the player opened or closed the panel; kept for the session and saved. */
	void setPanelOpen(boolean show)
	{
		panelChoice = show;
		panelOpenNow = show;
		if (panelOpenSaved != show)
		{
			panelOpenSaved = show;
			saveHiddenSetting(KEY_PANEL_OPEN, String.valueOf(show));
		}
	}

	/** One of our hidden settings (not in the config panel), or null. */
	String hiddenSetting(String key)
	{
		return configManager.getConfiguration(SpiritTreeAtlasConfig.GROUP, key);
	}

	void saveHiddenSetting(String key, String value)
	{
		configManager.setConfiguration(SpiritTreeAtlasConfig.GROUP, key, value);
	}

	Hit hitAt(int x, int y)
	{
		return Hit.at(hits, x, y);
	}

	boolean inHole(int x, int y)
	{
		for (Rectangle h : holes)
		{
			if (h.contains(x, y))
			{
				return true;
			}
		}
		return false;
	}

	/** Whether the map owns input at this point: menu open, Map mode, no menu, inside, not in a hole. */
	boolean isMapInput(int x, int y)
	{
		MapView v = view;
		return open && notice == null && mode == Mode.MAP && !menuOpen && v != null && v.contains(x, y) && !inHole(x, y);
	}

	/**
	 * Whether the point is on the map in Map mode, holes and an open menu included. The classic
	 * list scrolls (240 px of rows in 232), so no mouse wheel event there may reach the game: over
	 * the Travel row it would scroll the row out of its place until the next tick.
	 */
	boolean isOverMap(int x, int y)
	{
		MapView v = view;
		return open && notice == null && mode == Mode.MAP && v != null && v.contains(x, y);
	}

	/** Whether the point is on List mode's Map button (and no menu is open). */
	boolean isMapButton(int x, int y)
	{
		Rectangle b = mapButton;
		return open && notice == null && mode == Mode.LIST && !menuOpen && b != null && b.contains(x, y);
	}

	/** Whether the game's current menu is the Map button's (its "Show Map" entry on top). */
	boolean isMenuForMapButton()
	{
		return MAP_BUTTON.equals(menuFor);
	}

	// ------------------------------------------------------------------ menu ownership

	@Subscribe
	public void onPostMenuSort(PostMenuSort e)
	{
		if (client.isMenuOpen())
		{
			return;
		}
		menuFor = null;
		if (!open || notice != null)
		{
			return;
		}
		Point mp = client.getMouseCanvasPosition();
		int x = mp.getX();
		int y = mp.getY();
		if (mode == Mode.LIST)
		{
			Rectangle b = mapButton;
			if (b != null && b.contains(x, y))
			{
				add(stripMenu(), "Show", "Map", m -> setMode(Mode.MAP));
				menuFor = MAP_BUTTON;
			}
			return;
		}
		MapView v = view;
		if (v == null || !v.contains(x, y) || inHole(x, y))
		{
			return;
		}
		Menu entries = stripMenu();
		Hit hit = hitAt(x, y);
		boolean clear = selected != null;
		if (hit == null || !hit.isActionable())
		{
			if (clear)
			{
				// below Cancel: a left press here pans or is absorbed, so Clear is right-click only
				add(entries, 0, "Clear selection", "", m -> clearSelection());
			}
			return;
		}
		switch (hit.getKind())
		{
			case MARKER:
				Tree t = hit.getTree();
				if (clear)
				{
					add(entries, "Clear selection", "", m -> clearSelection());
				}
				if (hit.isStandIn())
				{
					Layer l = repo.layer(t.getLayer());
					add(entries, "Open", (l == null ? t.getLayer() : l.getName()) + " map", m -> openLayerFitted(t.getLayer()));
					add(entries, "Zoom to", hit.getTarget(), m -> openLayerFitted(t.getLayer()));
				}
				else
				{
					add(entries, "Zoom to", hit.getTarget(), m -> zoomTo(t));
				}
				add(entries, "Select", hit.getTarget(), m -> select(t.getId()));
				break;
			case ROW:
				Tree rt = hit.getTree();
				if (clear)
				{
					add(entries, "Clear selection", "", m -> clearSelection());
				}
				if (rt.isMapped())
				{
					add(entries, "Zoom to", hit.getTarget(), m -> zoomTo(rt));
				}
				// the left-click entry: a left press on the row is let through only for this menu
				add(entries, "Select", hit.getTarget(), m -> showTree(rt.getId()));
				break;
			case PORTAL:
				add(entries, hit.getOption(), hit.getTarget(), m -> openLayerFitted(hit.getId()));
				break;
			case BUTTON:
				add(entries, hit.getOption(), hit.getTarget(), m -> button(hit.getId()));
				break;
			default:
				break;
		}
		menuFor = menuKey(hit);
	}

	/**
	 * A stable name for what a hit does, the same from frame to frame. A left press on an
	 * actionable hit is let through only when the game's menu was built for that same hit:
	 * the game runs the menu it built at the last frame's mouse position, which may hold
	 * stale entries from outside the map or from a hole.
	 */
	static String menuKey(Hit hit)
	{
		return hit == null ? null : hit.getKind() + "|" + hit.getId() + "|" + (hit.getTree() == null ? null : hit.getTree().getId());
	}

	/** Whether the game's current menu holds our entries for this hit. */
	boolean isMenuFor(Hit hit)
	{
		String k = menuFor;
		return k != null && k.equals(menuKey(hit));
	}

	/** Leaves only Cancel: no Walk here, no hidden rows, no examine under the map. */
	private Menu stripMenu()
	{
		Menu entries = client.getMenu();
		List<MenuEntry> keep = new ArrayList<>();
		for (MenuEntry m : entries.getMenuEntries())
		{
			if (m.getType() == MenuAction.CANCEL)
			{
				keep.add(m);
			}
		}
		entries.setMenuEntries(keep.toArray(new MenuEntry[0]));
		return entries;
	}

	private static void add(Menu entries, String option, String target, Consumer<MenuEntry> onClick)
	{
		add(entries, -1, option, target, onClick);
	}

	private static void add(Menu entries, int index, String option, String target, Consumer<MenuEntry> onClick)
	{
		entries.createMenuEntry(index)
			.setOption(option)
			.setTarget(target == null || target.isEmpty() ? "" : ColorUtil.wrapWithColorTag(target, JagexColors.MENU_TARGET))
			.setType(MenuAction.RUNELITE)
			.onClick(onClick);
	}

	private void button(String id)
	{
		switch (id)
		{
			case Hit.ZOOM_IN:
				zoomCentre(1.5);
				break;
			case Hit.ZOOM_OUT:
				zoomCentre(1 / 1.5);
				break;
			case Hit.FIT:
				fit();
				break;
			case Hit.LIST:
				setMode(Mode.LIST);
				break;
			case Hit.SHOW_MAP:
				setMode(Mode.MAP);
				break;
			case Hit.CLEAR:
				clearSelection();
				break;
			case Hit.BACK:
				openLayer(Layer.SURFACE);
				break;
			case Hit.TOGGLE_PANEL:
				setPanelOpen(!panelOpenNow);
				break;
			default:
				break;
		}
	}
}
