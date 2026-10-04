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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
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
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.PostMenuSort;
import net.runelite.api.events.ResizeableChanged;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.VarClientStrChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetUtil;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.chatbox.ChatboxPanelManager;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.cluescrolls.ClueScrollPlugin;
import net.runelite.client.ui.JagexColors;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.Text;

@Slf4j
@PluginDescriptor(
	name = "Fairy Ring Atlas",
	description = "Turns the fairy ring dials into a high-resolution, zoomable map of every destination: click a ring, then use its travel log entry or follow the dial guide",
	tags = {"fairy", "ring", "map", "teleport", "travel", "zanaris", "code", "dial", "transport"},
	conflicts = "Fairy Ring Map"
)
@PluginDependency(ClueScrollPlugin.class)
public class SpiritTreeAtlasPlugin extends Plugin
{
	static final String RESOURCES = "/com/spirittreeatlas/";
	private static final int SCRIPT_LOG_REBUILD = 8080;
	private static final int SCRIPT_CONFIRM = 399;
	private static final int SCRIPT_CONFIRM_EXPIRE = 400;
	private static final int REOPEN_TICKS = 3;
	/** About 30 s after the interface closes, its caches are released. */
	private static final int TRIM_TICKS = 50;
	private static final long ANIM_MS = 250;
	/** The game's fairy ring search keeps at most this many characters. */
	private static final int SEARCH_MAX = 30;
	/** A favourite picked from the side panel is shown at no less than this zoom. */
	private static final double FAVOURITE_PPT = 2;
	private static final String FRM_CLASS = "com.fairyringmap.FairyRingMapPlugin";

	/** Components of 398 hidden in map mode; CONFIRM, the close button, the backdrop and its ornament stay. */
	private static final int[] HIDDEN_IN_MAP = {
		InterfaceID.Fairyrings.ROOT_MODEL1, InterfaceID.Fairyrings.ROOT_TEXT2,
		InterfaceID.Fairyrings.ROOT_MODEL3, InterfaceID.Fairyrings.ROOT_MODEL4, InterfaceID.Fairyrings.ROOT_MODEL5,
		InterfaceID.Fairyrings.A, InterfaceID.Fairyrings.B, InterfaceID.Fairyrings.C, InterfaceID.Fairyrings.D,
		InterfaceID.Fairyrings.I, InterfaceID.Fairyrings.J, InterfaceID.Fairyrings.K, InterfaceID.Fairyrings.L,
		InterfaceID.Fairyrings.P, InterfaceID.Fairyrings.Q, InterfaceID.Fairyrings.R, InterfaceID.Fairyrings.S,
		InterfaceID.Fairyrings.ROOT_MODEL18,
		InterfaceID.Fairyrings._1_CLOCKWISE, InterfaceID.Fairyrings._1_ANTICLOCKWISE,
		InterfaceID.Fairyrings._2_CLOCKWISE, InterfaceID.Fairyrings._2_ANTICLOCKWISE,
		InterfaceID.Fairyrings._3_CLOCKWISE, InterfaceID.Fairyrings._3_ANTICLOCKWISE,
	};
	/**
	 * Components of 398 moved in map mode, with their place inside the dials (x, y, width,
	 * height). The map's bottom-right corner is the dials' own, so Teleport (with the parchment
	 * plaque drawn behind it) goes in that corner and the close button just above it, instead of
	 * sitting in the middle of the map. The game draws and clicks them only inside the dials.
	 * Teleport keeps at least {@link #CONFIRM_MIN_H} px of height: below that the game draws its
	 * q8_full text on one line, and core Fairy Rings' longer destination names would be cut off.
	 */
	static final int[][] MOVED_IN_MAP = {
		{InterfaceID.Fairyrings.CONFIRM, 335, 290, 169, 36},
		{InterfaceID.Fairyrings.ROOT_MODEL25, 404, 292, 32, 32},
		{InterfaceID.Fairyrings.ROOT_GRAPHIC27, 478, 259, 26, 23},
	};
	/** q8_full: ascent 15 + max ascent 15 + max descent 5; the game wraps text only at this height. */
	static final int CONFIRM_MIN_H = 35;
	/** Rotate zones per dial: clockwise, anticlockwise. */
	static final int[][] ZONES = {
		{InterfaceID.Fairyrings._1_CLOCKWISE, InterfaceID.Fairyrings._1_ANTICLOCKWISE},
		{InterfaceID.Fairyrings._2_CLOCKWISE, InterfaceID.Fairyrings._2_ANTICLOCKWISE},
		{InterfaceID.Fairyrings._3_CLOCKWISE, InterfaceID.Fairyrings._3_ANTICLOCKWISE},
	};

	enum Mode
	{
		MAP,
		DIAL,
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
	private EventBus eventBus;
	/** Names in the fairy ring's own right-click menu; a subscriber of its own (see RingMenuNames). */
	private RingMenuNames menuNames;
	@Inject
	private PluginManager pluginManager;
	@Inject
	private Gson gson;
	@Inject
	private AtlasOverlay overlay;
	@Inject
	private AtlasInput input;
	@Inject
	private ClueHelper clueHelper;
	/** Names for new and renamed groups are typed in RuneLite's own chatbox input, as core Fairy Rings does for tags. */
	@Inject
	private ChatboxPanelManager chatboxPanelManager;

	@Getter
	private RingRepository repo;
	@Getter
	private TileStore tiles;
	private ExecutorService executor;
	@Getter
	private TravelLogController travelLog;

	// --- shared with the input thread
	@Getter
	private volatile boolean open;
	@Getter
	private volatile Mode mode = Mode.MAP;
	private volatile boolean menuOpen;
	private final Object viewLock = new Object();
	private volatile MapView view;
	private MapView animFrom;
	private MapView animTo;
	private long animStart;
	private volatile List<Hit> hits = Collections.emptyList();
	private volatile List<Rectangle> holes = Collections.emptyList();
	/** An unlock check is queued for the client thread (client thread only). */
	private boolean unlockQueued;
	private volatile Rectangle panel;
	@Getter
	private volatile Rectangle mapButton;
	@Getter
	private volatile int panelScroll;
	private volatile int panelScrollMax;
	/** What the game's current menu was built for (see {@link #menuKey(Hit)}); null when not ours. */
	private volatile String menuFor;

	// --- client thread state
	@Getter
	private String selected;
	@Getter
	private String here;
	@Getter
	private String search = "";
	@Getter
	private long flashUntil;
	/** The player's panel choice; null until they toggle it (then small maps start collapsed). */
	private Boolean panelChoice;
	private boolean panelCollapsedNow;
	@Getter
	private String notice;
	@Getter
	private WorldPoint clue;
	private boolean needsInitialView;
	private int playerX;
	private int playerY;
	private int closedTick = -100;
	private Mode closedMode = Mode.MAP;
	private MapView sessionView;
	private final Map<String, MapView> layerViews = new HashMap<>();
	private final Set<Integer> hiddenByUs = new HashSet<>();
	/** Original x, y, width and height of the components we moved, by component id. */
	private final Map<Integer, int[]> movedByUs = new HashMap<>();
	/**
	 * Whether we turned off the game's mouse-over text (top-left of the screen) for the map, which
	 * shows its own hover card; it is turned back on as soon as the map is not showing.
	 */
	private boolean mouseoverHiddenByUs;
	/** The dials' slot, moved into the free space while the map shows in resizable mode. */
	private final ModalSlot modalSlot = new ModalSlot();
	/** The code the player picked in the travel log itself; the log is not filtered for it. */
	private String logPicked;
	/** Whether the decoded tiles and map-sized caches were released since the interface closed. */
	private boolean trimmed = true;

	@Provides
	SpiritTreeAtlasConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(SpiritTreeAtlasConfig.class);
	}

	@Override
	protected void startUp()
	{
		repo = RingRepository.load(gson, RESOURCES);
		// the prebuilt groups seed the panel; without the file there are only the player's own
		groupDefaults = RingGroups.defaults(RingRepository.read(gson, RESOURCES + "groups.json", RingGroups.DefFile.class));
		groups = null;
		loadGroups();
		// one worker, newest request first: the tiles of the current view are decoded before the
		// leftovers of views the player has already zoomed or panned past
		executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingDeque<Runnable>()
		{
			@Override
			public boolean offer(Runnable r)
			{
				return offerFirst(r);
			}
		}, r ->
		{
			Thread t = new Thread(r, "fairy-ring-atlas-tiles");
			t.setDaemon(true);
			t.setPriority(Thread.MIN_PRIORITY);
			return t;
		});
		tiles = new TileStore(repo.getIndex(), repo.getLayers(), RESOURCES, executor);
		travelLog = new TravelLogController(client);
		overlayManager.add(overlay);
		menuNames = new RingMenuNames(client, config, () -> repo);
		eventBus.register(menuNames);
		mouseManager.registerMouseListener(input);
		mouseManager.registerMouseWheelListener(input);
		log.debug("started: rings={} index={} imagery={}", repo.isRingsLoaded(), repo.isIndexLoaded(), tiles.hasImagery());
		clientThread.invokeLater(() ->
		{
			if (client.getGameState() == GameState.LOGGED_IN)
			{
				repo.placeHouse(client.getVarbitValue(VarbitID.POH_HOUSE_LOCATION));
			}
			if (client.getGameState() == GameState.LOGGED_IN && isDialsVisible() && !open)
			{
				onDialsOpened();
			}
		});
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		if (menuNames != null)
		{
			eventBus.unregister(menuNames);
			menuNames = null;
		}
		mouseManager.unregisterMouseListener(input);
		mouseManager.unregisterMouseWheelListener(input);
		input.reset();
		open = false;
		// the rest is client-thread state (the overlay may be mid-frame on it right now); the
		// overlay is already removed, so nothing renders after this runs
		TravelLogController logController = travelLog;
		ExecutorService ex = executor;
		TileStore store = tiles;
		clientThread.invoke(() ->
		{
			restoreHides();
			logController.restore();
			overlay.reset();
			view = null;
			selected = null;
			hits = Collections.emptyList();
			ex.shutdownNow();
			store.close();
		});
	}

	// ------------------------------------------------------------------ open / close

	private boolean isDialsVisible()
	{
		Widget confirm = client.getWidget(InterfaceID.Fairyrings.CONFIRM);
		return confirm != null && !confirm.isHidden();
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded e)
	{
		if (e.getGroupId() == InterfaceID.FAIRYRINGS && !open)
		{
			onDialsOpened();
		}
		else if (e.getGroupId() != InterfaceID.FAIRYRINGS && modalSlot.holds())
		{
			// before the new interface's first frame: if it took the dials' slot (or the toplevel
			// changed), put the slot back now rather than at the next tick
			updateSlot();
		}
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed e)
	{
		if (e.getGroupId() == InterfaceID.FAIRYRINGS && open)
		{
			onDialsClosed();
		}
		else if (e.getGroupId() == InterfaceID.FAIRYRINGS_LOG)
		{
			travelLog.forget();
		}
	}

	/** The player's own order for the side panel's Favourites list, saved per account. */
	@Getter
	private volatile List<String> faveOrder = Collections.emptyList();
	/** The row being dragged (input thread): its list (Hit.FAVE or a group row id) and ring, or null; and the pointer's y. */
	@Getter
	private volatile String dragList;
	@Getter
	private volatile String dragCode;
	@Getter
	private volatile int dragY;
	private static final String KEY_FAVE_ORDER = "favouriteOrder";
	/** The Groups panel's groups (global, not per account) and whether it is open; hidden config keys. */
	private static final String KEY_GROUPS = "groups";
	private static final String KEY_GROUPS_OPEN = "groupsOpen";
	/** Maps narrower than this (fixed mode) start with the side panels closed. */
	private static final int NARROW_MAP = 700;
	/** Client thread; {@link #groupsView} is what the painter and the input thread read. */
	private RingGroups groups;
	private List<RingGroups.Def> groupDefaults = Collections.emptyList();
	/** The saved groups as last read or written here; anything else (another profile) is read again on open. */
	private String groupsJson;
	@Getter
	private volatile List<RingGroups.Group> groupsView = Collections.emptyList();
	/** Whether the Groups panel is open on the map as last laid out; see {@link #isGroupsOpen(int)}. */
	@Getter
	private volatile boolean groupsOpen;
	private boolean groupsOpenSaved;
	/** The player's open or close this session; null until they choose (then narrow maps start closed). */
	private Boolean groupsChoice;
	@Getter
	private volatile int groupsScroll;
	private volatile int groupsScrollMax;
	private volatile Rectangle groupsPanel;

	private void onDialsOpened()
	{
		int tick = client.getTickCount();
		boolean reopen = tick >= closedTick && tick - closedTick <= REOPEN_TICKS;
		open = true;
		trimmed = false;
		mode = reopen ? closedMode : config.openInMapMode() ? Mode.MAP : Mode.DIAL;
		if (!reopen)
		{
			selected = null;
			logPicked = null;
			panelScroll = 0;
			needsInitialView = true;
		}
		faveOrder = FavouriteOrder.parse(configManager.getRSProfileConfiguration(SpiritTreeAtlasConfig.GROUP, KEY_FAVE_ORDER));
		loadGroups();
		repo.readDbTable(client);
		repo.refreshState(client);
		queueUnlockCheck();
		search = currentSearch();
		locatePlayer();
		notice = fairyRingMapEnabled() ? "Fairy Ring Map is also enabled - disable one of them" : null;
		clue = null;
		if (config.clueHelper())
		{
			clue = clueHelper.location();
			String code = clueHelper.fairyRingCode();
			if (code != null && repo.ring(code) != null && !reopen)
			{
				selected = code;
			}
		}
		if (mode == Mode.MAP)
		{
			applyHides();
		}
	}

	private void onDialsClosed()
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
		dragCode = null;
		restoreHides();
		travelLog.restore();
		hits = Collections.emptyList();
	}

	/** Client thread: releases the decoded tiles and the map-sized buffers while the dials are closed. */
	private void trimCaches()
	{
		trimmed = true;
		tiles.trim();
		overlay.releaseCaches();
	}

	private void locatePlayer()
	{
		here = null;
		Player p = client.getLocalPlayer();
		if (p == null)
		{
			return;
		}
		WorldPoint wp = p.getWorldLocation();
		playerX = wp.getX();
		playerY = wp.getY();
		int best = Integer.MAX_VALUE;
		for (Ring r : repo.dialable())
		{
			int d = Math.max(Math.abs(r.getX() - wp.getX()), Math.abs(r.getY() - wp.getY()));
			if (r.isMapped() && r.getPlane() == wp.getPlane() && d <= 4 && d < best)
			{
				best = d;
				here = r.getCode();
			}
		}
	}

	private boolean fairyRingMapEnabled()
	{
		try
		{
			for (Plugin p : pluginManager.getPlugins())
			{
				if (FRM_CLASS.equals(p.getClass().getName()) && pluginManager.isPluginEnabled(p))
				{
					return true;
				}
			}
		}
		catch (RuntimeException e)
		{
			log.debug("plugin scan failed", e);
		}
		return false;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged e)
	{
		GameState state = e.getGameState();
		// a map reload or a brief reconnect keeps the interfaces (and our hides) alive; a real
		// close is still caught by WidgetClosed or the per-tick visibility check
		if (state == GameState.LOGGED_IN || state == GameState.LOADING || state == GameState.CONNECTION_LOST)
		{
			return;
		}
		// the slot is a toplevel component the game may keep or reload: put back what we wrote to it
		modalSlot.restore(client);
		if (open)
		{
			// the interface tree is gone: drop our records rather than touch orphaned widgets
			open = false;
			closedTick = client.getTickCount();
			closedMode = mode;
			if (view != null)
			{
				sessionView = view;
			}
			hiddenByUs.clear();
			movedByUs.clear();
			restoreMouseoverText();
			travelLog.forget();
			hits = Collections.emptyList();
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
		boolean visible = isDialsVisible();
		if (open && !visible)
		{
			onDialsClosed();
		}
		else if (!open && visible)
		{
			onDialsOpened();
		}
		if (!open)
		{
			if (modalSlot.holds())
			{
				modalSlot.restore(client);
			}
			// free the decoded tiles and map-sized buffers once the ring has been left alone for a
			// while; a quick reopen (a favourite toggle, the next trip) stays warm
			int tick = client.getTickCount();
			if (!trimmed && (tick - closedTick >= TRIM_TICKS || tick < closedTick))
			{
				trimCaches();
			}
			return;
		}
		repo.refreshState(client);
		if (mode == Mode.MAP)
		{
			applyHides();
		}
		if (config.clueHelper())
		{
			clue = clueHelper.location();
		}
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
	public void onScriptPostFired(ScriptPostFired e)
	{
		if (!open)
		{
			return;
		}
		switch (e.getScriptId())
		{
			case SCRIPT_LOG_REBUILD:
				travelLog.forget();
				applyLogFilter();
				break;
			case SCRIPT_CONFIRM:
				// the game hid the rotate zones itself; they are no longer ours to restore
				for (int[] zone : ZONES)
				{
					hiddenByUs.remove(zone[0]);
					hiddenByUs.remove(zone[1]);
				}
				break;
			case SCRIPT_CONFIRM_EXPIRE:
				if (mode == Mode.MAP)
				{
					applyHides();
				}
				break;
			default:
				break;
		}
	}

	@Subscribe
	public void onVarClientStrChanged(VarClientStrChanged e)
	{
		if (e.getIndex() != VarClientID.FAIRYRINGS_SEARCHSTRING || !open)
		{
			return;
		}
		String q = currentSearch();
		if (q.equals(search))
		{
			return;
		}
		search = q;
		if (q.isEmpty())
		{
			applyLogFilter();
			return;
		}
		travelLog.restore();
		MapView v = view;
		if (v == null)
		{
			return;
		}
		List<Ring> onLayer = new ArrayList<>();
		boolean elsewhere = false;
		for (Ring r : repo.matching(q))
		{
			if (v.getLayer().equals(r.getLayer()))
			{
				onLayer.add(r);
			}
			else
			{
				elsewhere = true;
			}
		}
		if (onLayer.isEmpty() && elsewhere)
		{
			flashUntil = System.currentTimeMillis() + 1600;
		}
		if (!onLayer.isEmpty() && config.autoFitSearch())
		{
			animateTo(fitRings(v, onLayer, 2, insets(v.rect())));
		}
	}

	/** The search as the game's log filter sees it: lower case, at most 30 characters, not trimmed. */
	private String currentSearch()
	{
		String s = client.getVarcStrValue(VarClientID.FAIRYRINGS_SEARCHSTRING);
		if (s == null)
		{
			return "";
		}
		s = s.toLowerCase(Locale.ROOT);
		return s.length() > SEARCH_MAX ? s.substring(0, SEARCH_MAX) : s;
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked e)
	{
		if (!open || !"Use code".equals(e.getMenuOption())
			|| WidgetUtil.componentToInterface(e.getParam1()) != InterfaceID.FAIRYRINGS_LOG)
		{
			return;
		}
		String code = DialMath.normalize(Text.removeTags(e.getMenuTarget()));
		Ring r = repo.ring(code);
		if (r != null)
		{
			// the player is using the log directly: follow on the map, but leave the log as it is
			selected = code;
			logPicked = code;
			panTo(r);
		}
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged e)
	{
		int id = e.getVarbitId();
		if (id == VarbitID.POH_HOUSE_LOCATION)
		{
			// so the ring menu names the house's town before the dials are first opened
			repo.placeHouse(e.getValue());
		}
		if (open && (id == VarbitID.FAIRYRING_1 || id == VarbitID.FAIRYRING_2 || id == VarbitID.FAIRYRING_3))
		{
			// once the dials show the selection the filter has done its job
			applyLogFilter();
		}
		if (open && repo.watchesUnlock(id, e.getVarpId()))
		{
			queueUnlockCheck();
		}
	}

	/**
	 * Re-checks the locked rings' unlock conditions once, after the current event: the quest
	 * checks run the client's own quest status script, which must not run inside another script.
	 * Done when the dials open and when a var a check reads changes, never per frame.
	 */
	private void queueUnlockCheck()
	{
		if (unlockQueued)
		{
			return;
		}
		unlockQueued = true;
		clientThread.invokeLater(() ->
		{
			unlockQueued = false;
			if (open && client.getGameState() == GameState.LOGGED_IN)
			{
				repo.checkUnlocks(clientVars());
			}
		});
	}

	/** The client's vars for one unlock check; each quest is asked once. */
	private UnlockCheck.Vars clientVars()
	{
		Map<String, QuestState> quests = new HashMap<>();
		return new UnlockCheck.Vars()
		{
			@Override
			public int varbit(int id)
			{
				return client.getVarbitValue(id);
			}

			@Override
			public int varp(int id)
			{
				return client.getVarpValue(id);
			}

			@Override
			public QuestState quest(String name)
			{
				if (!quests.containsKey(name))
				{
					QuestState state = null;
					try
					{
						state = Quest.valueOf(name).getState(client);
					}
					catch (RuntimeException ex)
					{
						// an unknown quest name (a newer rings.json) only makes the check a hint
						log.debug("quest {} unreadable", name, ex);
					}
					quests.put(name, state);
				}
				return quests.get(name);
			}
		};
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged e)
	{
		if (SpiritTreeAtlasConfig.GROUP.equals(e.getGroup()) && !KEY_GROUPS.equals(e.getKey()) && !KEY_GROUPS_OPEN.equals(e.getKey()))
		{
			clientThread.invoke(() ->
			{
				if (open)
				{
					applyLogFilter();
				}
				if (open || modalSlot.holds())
				{
					updateSlot();
				}
			});
		}
	}

	// ------------------------------------------------------------------ widget hiding

	private void applyHides()
	{
		for (int id : HIDDEN_IN_MAP)
		{
			Widget w = client.getWidget(id);
			if (w != null && !w.isSelfHidden())
			{
				w.setHidden(true);
				hiddenByUs.add(id);
			}
		}
		for (int[] m : MOVED_IN_MAP)
		{
			Widget w = client.getWidget(m[0]);
			if (w == null || (w.getOriginalX() == m[1] && w.getOriginalY() == m[2]
				&& w.getOriginalWidth() == m[3] && w.getOriginalHeight() == m[4]))
			{
				continue;
			}
			movedByUs.putIfAbsent(m[0], new int[]{w.getOriginalX(), w.getOriginalY(), w.getOriginalWidth(), w.getOriginalHeight()});
			place(w, m[1], m[2], m[3], m[4]);
		}
		if (client.isMouseoverTextEnabled())
		{
			client.setMouseoverTextEnabled(false);
			mouseoverHiddenByUs = true;
		}
		updateSlot();
	}

	/**
	 * Resizable mode, "Use free space": while the map shows, the dials' slot sits in the corner of
	 * the free space (so the map fills it); otherwise it is where the game put it.
	 */
	private void updateSlot()
	{
		boolean wanted = open && mode == Mode.MAP && config.useFreeSpace() && client.isResized();
		modalSlot.update(client, wanted, config.maxWidth(), config.maxHeight());
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

	/** Un-hide only what we hid, and only if it is still hidden; put back what we moved. */
	private void restoreHides()
	{
		for (int id : hiddenByUs)
		{
			Widget w = client.getWidget(id);
			if (w != null && w.isSelfHidden())
			{
				w.setHidden(false);
			}
		}
		hiddenByUs.clear();
		for (Map.Entry<Integer, int[]> e : movedByUs.entrySet())
		{
			Widget w = client.getWidget(e.getKey());
			int[] o = e.getValue();
			if (w != null)
			{
				place(w, o[0], o[1], o[2], o[3]);
			}
		}
		movedByUs.clear();
		modalSlot.restore(client);
		restoreMouseoverText();
	}

	private static void place(Widget w, int x, int y, int width, int height)
	{
		w.setOriginalX(x);
		w.setOriginalY(y);
		w.setOriginalWidth(width);
		w.setOriginalHeight(height);
		w.revalidate();
	}

	void setMode(Mode m)
	{
		mode = m;
		input.reset();
		dragCode = null;
		if (m == Mode.MAP)
		{
			applyHides();
		}
		else
		{
			restoreHides();
			hits = Collections.emptyList();
		}
	}

	// ------------------------------------------------------------------ selection

	void select(String code, boolean pan)
	{
		Ring r = repo.ring(code);
		if (r == null)
		{
			return;
		}
		selected = code;
		logPicked = null;
		if (pan)
		{
			panTo(r);
		}
		applyLogFilter();
	}

	void clearSelection()
	{
		selected = null;
		logPicked = null;
		travelLog.restore();
	}

	/**
	 * Filter the travel log to the selection when allowed; otherwise leave it as the game drew it.
	 * Not when the dials already show the code (the next step is Teleport), nor for a code the
	 * player picked in the log itself.
	 */
	private void applyLogFilter()
	{
		Ring r = repo.ring(selected);
		// read the search live: the log rebuild can run before the varc change is announced
		if (!config.filterTravelLog() || r == null || !currentSearch().isEmpty() || !repo.hasLogRow(r.getCode())
			|| r.getCode().equals(logPicked) || Arrays.equals(DialMath.values(r.getCode()), dials())
			|| !travelLog.apply(r, repo.favouriteSlot(r.getCode()), repo.getLogComponents(), config.selectedColor().getRGB() & 0xFFFFFF))
		{
			travelLog.restore();
		}
	}

	int[] dials()
	{
		return new int[]{
			client.getVarbitValue(VarbitID.FAIRYRING_1),
			client.getVarbitValue(VarbitID.FAIRYRING_2),
			client.getVarbitValue(VarbitID.FAIRYRING_3),
		};
	}

	// ------------------------------------------------------------------ view (input thread safe)

	MapView getView()
	{
		return view;
	}

	/** Called by the overlay each frame: lays the view onto the live map rectangle and steps the animation. */
	MapView frameView(Rectangle rect)
	{
		synchronized (viewLock)
		{
			if (view == null || needsInitialView)
			{
				view = initialView(rect);
				needsInitialView = false;
				Ring sel = repo.ring(selected);
				if (sel != null && sel.isMapped() && !view.getLayer().equals(sel.getLayer()))
				{
					Layer l = repo.layer(sel.getLayer());
					view = l == null ? view : fitLayer(l, rect, insets(rect));
				}
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

	private MapView initialView(Rectangle rect)
	{
		Layer surface = repo.surface();
		Ring hereRing = repo.ring(here);
		if (hereRing != null && hereRing.isMapped() && !Layer.SURFACE.equals(hereRing.getLayer()))
		{
			Layer l = repo.layer(hereRing.getLayer());
			if (l != null)
			{
				return fitLayer(l, rect, insets(rect));
			}
		}
		Insets in = insets(rect);
		MapView base = MapView.of(surface, rect);
		switch (config.startView())
		{
			case REMEMBER:
				Layer l = sessionView == null ? null : repo.layer(sessionView.getLayer());
				if (l != null)
				{
					return sessionView.withRect(rect).withLayer(l);
				}
				break;
			case AROUND_YOU:
				if (hereRing != null)
				{
					return base.focusOn(hereRing.getX() + 0.5, hereRing.getY() + 0.5, 2, in);
				}
				if (surface.contains(playerX, playerY))
				{
					return base.focusOn(playerX + 0.5, playerY + 0.5, 2, in);
				}
				break;
			default:
				break;
		}
		return fitRings(base, repo.ringsIn(Layer.SURFACE), 0, in);
	}

	/**
	 * Screen space the chrome takes from the map: the top bar (and notice line), the Elsewhere
	 * panel and an open Groups panel (as wide as on a map of this width).
	 */
	static Insets chromeInsets(int mapWidth, boolean panelCollapsed, boolean groupsOpen, boolean notice)
	{
		return new Insets(ChromePainter.BAR_H + (notice ? 18 : 0), 6 + (panelCollapsed ? 20 : ChromePainter.PANEL_W), 0,
			groupsOpen ? 6 + ChromePainter.groupsWidth(mapWidth) : 0);
	}

	private Insets insets(Rectangle rect)
	{
		return chromeInsets(rect.width, isPanelCollapsed(rect.width), isGroupsOpen(rect.width), notice != null);
	}

	/** A layer's view fitted to its bounds, clear of the chrome. */
	static MapView fitLayer(Layer l, Rectangle rect, Insets in)
	{
		MapView v = MapView.of(l, rect);
		return v.fit(v.getBx0(), v.getBy0(), v.getBx1(), v.getBy1(), 12, in);
	}

	/** A view fitting the given rings (or the layer bounds when none), zoomed in no further than maxPpt when set. */
	static MapView fitRings(MapView v, List<Ring> rings, double maxPpt, Insets in)
	{
		if (rings.isEmpty())
		{
			return v.fit(v.getBx0(), v.getBy0(), v.getBx1(), v.getBy1(), 12, in);
		}
		int x0 = Integer.MAX_VALUE;
		int y0 = Integer.MAX_VALUE;
		int x1 = Integer.MIN_VALUE;
		int y1 = Integer.MIN_VALUE;
		for (Ring r : rings)
		{
			x0 = Math.min(x0, r.getX());
			y0 = Math.min(y0, r.getY());
			x1 = Math.max(x1, r.getX() + 1);
			y1 = Math.max(y1, r.getY() + 1);
		}
		MapView f = v.fit(x0, y0, x1, y1, 40, in);
		if (maxPpt > 0 && f.getPpt() > maxPpt)
		{
			f = f.focusOn((x0 + x1) / 2.0, (y0 + y1) / 2.0, maxPpt, in);
		}
		return f;
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

	/** Input thread: scroll the Elsewhere panel. */
	void scrollPanel(int dy)
	{
		panelScroll = Math.max(0, Math.min(panelScrollMax, panelScroll + dy));
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

	void panTo(Ring r)
	{
		if (r == null || !r.isMapped() || view == null)
		{
			return;
		}
		openLayer(r.getLayer());
		MapView v = view;
		animateTo(v.focusOn(r.getX() + 0.5, r.getY() + 0.5, v.getPpt(), insets(v.rect())));
	}

	void zoomTo(Ring r)
	{
		if (r == null || !r.isMapped() || view == null)
		{
			return;
		}
		openLayer(r.getLayer());
		MapView v = view;
		animateTo(v.focusOn(r.getX() + 0.5, r.getY() + 0.5, Math.max(v.getPpt() * 2, 4), insets(v.rect())));
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
				openCard(hit.getId());
				return;
			}
			Ring r = hit.getRing();
			if (r == null)
			{
				return;
			}
			if (r.isDialable())
			{
				select(r.getCode(), false);
			}
			else if (r.isMapped())
			{
				zoomTo(r);
			}
		});
	}

	/** Input thread: a row of a list (Hit.FAVE or a group row id) is being dragged to y. */
	void rowDrag(String list, String code, int y)
	{
		dragY = y;
		dragList = list;
		dragCode = code;
	}

	/** Input thread: a favourite's or a group's row was clicked (not dragged); select it. */
	void rowClick(String code)
	{
		clientThread.invoke(() ->
		{
			Ring r = repo.ring(code);
			if (r != null && open)
			{
				showFavourite(r);
			}
		});
	}

	/** Input thread: a dragged row was let go; move it where the drop line showed and save the order. */
	void rowDrop(String list, String code)
	{
		clientThread.invoke(() ->
		{
			dragCode = null;
			ChromePainter.Drop drop = overlay.drop();
			if (drop == null || !drop.getList().equals(list) || !drop.getCode().equals(code))
			{
				// let go before a frame showed the drag: nothing moves
				return;
			}
			String group = Hit.groupOf(list);
			if (group != null)
			{
				if (groups.move(group, code, drop.getBefore()))
				{
					saveGroups();
				}
				return;
			}
			List<String> order = FavouriteOrder.move(FavouriteOrder.sort(repo.favouriteRings(), faveOrder), code, drop.getBefore());
			faveOrder = order;
			configManager.setRSProfileConfiguration(SpiritTreeAtlasConfig.GROUP, KEY_FAVE_ORDER, FavouriteOrder.format(order));
		});
	}

	// ------------------------------------------------------------------ Groups panel

	/** Client thread: shows the groups as changed and saves them (global config, as compact JSON). */
	private void saveGroups()
	{
		groupsView = groups.view();
		groupsJson = groups.format(gson);
		configManager.setConfiguration(SpiritTreeAtlasConfig.GROUP, KEY_GROUPS, groupsJson);
	}

	/**
	 * The groups and the panel's open state as saved. The plugin stays running across a profile
	 * switch, so this runs again on each open and rereads groups saved by anything else.
	 */
	private void loadGroups()
	{
		String json = configManager.getConfiguration(SpiritTreeAtlasConfig.GROUP, KEY_GROUPS);
		if (groups == null || !Objects.equals(json, groupsJson))
		{
			groups = RingGroups.parse(gson, groupDefaults, json);
			groupsJson = json;
			groupsView = groups.view();
		}
		groupsOpenSaved = Boolean.parseBoolean(configManager.getConfiguration(SpiritTreeAtlasConfig.GROUP, KEY_GROUPS_OPEN));
	}

	private RingGroups.Group group(String id)
	{
		for (RingGroups.Group g : groupsView)
		{
			if (g.getId().equals(id))
			{
				return g;
			}
		}
		return null;
	}

	/** Client thread: the hint for a ring hovered in a group's row, e.g. "Slayer: Kalphites"; else null. */
	String groupNote(String hitId, String code)
	{
		String id = Hit.groupOf(hitId);
		RingGroups.Group g = id == null ? null : group(id);
		String note = g == null ? null : groups.note(id, code);
		return note == null ? null : g.getName() + ": " + note;
	}

	/** Client thread: what is at a ring hovered in a prebuilt group's row, e.g. "Patches: bush, spirit tree"; else null. */
	String groupDetails(String hitId, String code)
	{
		String id = Hit.groupOf(hitId);
		return id == null || group(id) == null ? null : groups.details(id, code);
	}

	/** Whether the Groups panel is open for a map of this width: as saved, but closed on a narrow map until opened there. */
	boolean isGroupsOpen(int mapWidth)
	{
		groupsOpen = groupsChoice != null ? groupsChoice : groupsOpenSaved && mapWidth >= NARROW_MAP;
		return groupsOpen;
	}

	private void setGroupsOpen(boolean show)
	{
		groupsChoice = show;
		groupsOpen = show;
		if (groupsOpenSaved != show)
		{
			groupsOpenSaved = show;
			configManager.setConfiguration(SpiritTreeAtlasConfig.GROUP, KEY_GROUPS_OPEN, show);
		}
	}

	/** Client thread: applies an edit to the groups, saving them when it changed anything. */
	private void editGroups(BooleanSupplier edit)
	{
		if (edit.getAsBoolean())
		{
			saveGroups();
		}
	}

	/** Asks for a name in the chatbox, then makes a group (holding the ring, when one is given) and shows it. */
	private void newGroup(String code)
	{
		if (groups.isFull())
		{
			chatboxPanelManager.openTextMenuInput("There can be " + RingGroups.GROUPS_MAX + " groups at most. Delete one first.")
				.option("OK", () -> { })
				.build();
			return;
		}
		chatboxPanelManager.openTextInput("New group name")
			.addCharValidator(c -> c != '<' && c != '>')
			.onDone((Consumer<String>) name -> clientThread.invoke(() ->
			{
				if (groups.create(name, code) != null)
				{
					saveGroups();
					setGroupsOpen(true);
				}
			}))
			.build();
	}

	private void renameGroup(String id)
	{
		RingGroups.Group g = group(id);
		if (g == null)
		{
			return;
		}
		chatboxPanelManager.openTextInput("Rename group")
			.value(g.getName())
			.addCharValidator(c -> c != '<' && c != '>')
			.onDone((Consumer<String>) name -> clientThread.invoke(() -> editGroups(() -> groups.rename(id, name))))
			.build();
	}

	/** Asks for a group row's new label in the chatbox, prefilled with the one it shows. */
	private void renameRow(String id, Ring r)
	{
		RingGroups.Group g = group(id);
		if (g == null || !g.getCodes().contains(r.getCode()))
		{
			return;
		}
		String shown = g.getLabels().get(r.getCode());
		chatboxPanelManager.openTextInput("Rename " + r.getCode() + " in " + g.getName())
			.value(shown != null ? shown : repo.displayName(r))
			.addCharValidator(c -> c != '<' && c != '>')
			.onDone((Consumer<String>) label -> clientThread.invoke(() ->
				editGroups(() -> groups.renameRow(id, r.getCode(), label, repo.displayName(r)))))
			.build();
	}

	/** Deletes a group; one that holds rings is confirmed in the chatbox first. */
	private void deleteGroup(String id)
	{
		RingGroups.Group g = group(id);
		if (g == null)
		{
			return;
		}
		Runnable delete = () -> clientThread.invoke(() -> editGroups(() -> groups.delete(id)));
		if (g.getCodes().isEmpty())
		{
			delete.run();
			return;
		}
		chatboxPanelManager.openTextMenuInput("Delete the group " + g.getName() + "?")
			.option("Yes, delete it", delete)
			.option("No", () -> { })
			.build();
	}

	/** Input thread: scroll the Groups panel. */
	void scrollGroups(int dy)
	{
		groupsScroll = Math.max(0, Math.min(groupsScrollMax, groupsScroll + dy));
	}

	void publishGroups(Rectangle panel, int scrollMax)
	{
		groupsPanel = panel;
		groupsScrollMax = scrollMax;
		if (groupsScroll > scrollMax)
		{
			groupsScroll = scrollMax;
		}
	}

	boolean inGroups(int x, int y)
	{
		Rectangle p = groupsPanel;
		return p != null && p.contains(x, y);
	}

	/** The "Add to group" entry, with a submenu of every group (those holding the ring marked) and "New group...". */
	private void addGroupMenu(Menu menu, Ring r, String target)
	{
		Menu sub = menu.createMenuEntry(-1)
			.setOption("Add to group")
			.setTarget(ColorUtil.wrapWithColorTag(target, JagexColors.MENU_TARGET))
			.setType(MenuAction.RUNELITE)
			.createSubMenu();
		// like the main menu, the last entry is at the top
		add(sub, "New group...", "", m -> newGroup(r.getCode()));
		List<RingGroups.Group> all = groupsView;
		for (int i = all.size() - 1; i >= 0; i--)
		{
			RingGroups.Group g = all.get(i);
			if (g.getCodes().contains(r.getCode()))
			{
				add(sub, g.getName(), "(added)", m -> { });
			}
			else
			{
				add(sub, g.getName(), "", m -> editGroups(() -> groups.add(g.getId(), r.getCode())));
			}
		}
	}

	/** A row in the Favourites list: select the ring and bring it into view, close enough to read. */
	void showFavourite(Ring r)
	{
		select(r.getCode(), false);
		if (r.isMapped() && view != null)
		{
			openLayer(r.getLayer());
			MapView v = view;
			animateTo(v.focusOn(r.getX() + 0.5, r.getY() + 0.5, Math.max(v.getPpt(), FAVOURITE_PPT), insets(v.rect())));
		}
	}

	void fit()
	{
		MapView v = view;
		if (v != null)
		{
			Insets in = insets(v.rect());
			animateTo(Layer.SURFACE.equals(v.getLayer()) ? fitRings(v, repo.ringsIn(Layer.SURFACE), 0, in)
				: v.fit(v.getBx0(), v.getBy0(), v.getBx1(), v.getBy1(), 12, in));
		}
	}

	/** An Elsewhere card or a portal: open its layer, or fit the layer again when it is already open. */
	void openCard(String id)
	{
		// most of these areas have a single ring: opening one selects its (first usable) ring
		Ring first = repo.defaultRing(id);
		if (first != null)
		{
			select(first.getCode(), false);
		}
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

	// ------------------------------------------------------------------ hit testing (input thread safe)

	void publish(List<Hit> newHits, List<Rectangle> newHoles, Rectangle newPanel, int newPanelScrollMax, Rectangle newMapButton)
	{
		hits = newHits;
		holes = newHoles;
		panel = newPanel;
		panelScrollMax = newPanelScrollMax;
		mapButton = newMapButton;
		if (panelScroll > newPanelScrollMax)
		{
			panelScroll = newPanelScrollMax;
		}
	}

	Hit hitAt(int x, int y)
	{
		return Hit.at(hits, x, y);
	}

	boolean inPanel(int x, int y)
	{
		Rectangle p = panel;
		return p != null && p.contains(x, y);
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

	/** Whether the Elsewhere panel is collapsed for a map of this width. */
	boolean isPanelCollapsed(int mapWidth)
	{
		panelCollapsedNow = panelChoice != null ? panelChoice : mapWidth < NARROW_MAP;
		return panelCollapsedNow;
	}

	/** Whether the map owns input at this point: dials open, map mode, no menu, inside, not in a hole. */
	boolean isMapInput(int x, int y)
	{
		MapView v = view;
		return open && mode == Mode.MAP && !menuOpen && v != null && v.contains(x, y) && !inHole(x, y);
	}

	// ------------------------------------------------------------------ menu ownership

	@Subscribe
	public void onPostMenuSort(PostMenuSort e)
	{
		if (!open || client.isMenuOpen())
		{
			return;
		}
		menuFor = null;
		Point mp = client.getMouseCanvasPosition();
		int x = mp.getX();
		int y = mp.getY();
		if (mode == Mode.DIAL)
		{
			Rectangle b = mapButton;
			if (b != null && b.contains(x, y))
			{
				add(stripMenu(), "Show", "Map", m -> setMode(Mode.MAP));
			}
			return;
		}
		MapView v = view;
		if (v == null || !v.contains(x, y) || inHole(x, y))
		{
			return;
		}
		Menu menu = stripMenu();
		Hit hit = hitAt(x, y);
		boolean clear = selected != null;
		if (hit == null || !hit.isActionable())
		{
			if (clear)
			{
				// below Cancel: a left press here pans or is absorbed, so Clear is right-click only
				add(menu, 0, "Clear selection", "", m -> clearSelection());
			}
			return;
		}
		switch (hit.getKind())
		{
			case MARKER:
			case CHIP:
				Ring r = hit.getRing();
				String inGroup = Hit.groupOf(hit.getId());
				RingGroups.Group group = group(inGroup);
				if (clear)
				{
					add(menu, "Clear selection", "", m -> clearSelection());
				}
				if (r.isDialable())
				{
					addGroupMenu(menu, r, hit.getTarget());
				}
				if (group != null)
				{
					add(menu, "Remove from", group.getName(), m -> editGroups(() -> groups.remove(inGroup, r.getCode())));
					if (group.getRenamed().contains(r.getCode()))
					{
						add(menu, "Reset name", hit.getTarget(), m -> editGroups(() -> groups.resetRow(inGroup, r.getCode())));
					}
					add(menu, "Rename", hit.getTarget(), m -> renameRow(inGroup, r));
				}
				if (r.isMapped())
				{
					add(menu, "Zoom to", hit.getTarget(), m -> zoomTo(r));
				}
				if (r.isDialable())
				{
					add(menu, "Select", hit.getTarget(), Hit.FAVE.equals(hit.getId()) || Hit.HOUSE.equals(hit.getId()) || group != null
						? m -> showFavourite(r) : m -> select(r.getCode(), hit.getKind() == Hit.Kind.CHIP));
				}
				break;
			case GROUP:
				String id = hit.getId();
				RingGroups.Group g = group(id);
				if (g == null)
				{
					break;
				}
				add(menu, "Delete group", g.getName(), m -> deleteGroup(id));
				if (g.isModified())
				{
					add(menu, "Reset to default", g.getName(), m -> editGroups(() -> groups.reset(id)));
				}
				add(menu, "Rename", g.getName(), m -> renameGroup(id));
				add(menu, hit.getOption(), hit.getTarget(), m -> editGroups(() -> groups.toggleCollapsed(id)));
				break;
			case CARD:
			case PORTAL:
				add(menu, hit.getOption(), hit.getTarget(), m -> openCard(hit.getId()));
				break;
			case BUTTON:
				if (Hit.NEW_GROUP.equals(hit.getId()))
				{
					// prebuilt groups the player deleted come back from here, while there is room
					for (RingGroups.Def d : groups.isFull() ? Collections.<RingGroups.Def>emptyList() : groups.deleted())
					{
						add(menu, "Restore group", d.name, m -> editGroups(() -> groups.restore(d.id)));
					}
				}
				add(menu, hit.getOption(), hit.getTarget(), m -> button(hit.getId()));
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
		return hit == null ? null : hit.getKind() + "|" + hit.getId() + "|" + (hit.getRing() == null ? null : hit.getRing().getCode() + " " + hit.getRing().getName());
	}

	/** Whether the game's current menu holds our entries for this hit. */
	boolean isMenuFor(Hit hit)
	{
		String k = menuFor;
		return k != null && k.equals(menuKey(hit));
	}

	/** Leaves only Cancel: no Walk here, no hidden rotate zones, no examine under the map. */
	private Menu stripMenu()
	{
		Menu menu = client.getMenu();
		List<MenuEntry> keep = new ArrayList<>();
		for (MenuEntry m : menu.getMenuEntries())
		{
			if (m.getType() == MenuAction.CANCEL)
			{
				keep.add(m);
			}
		}
		menu.setMenuEntries(keep.toArray(new MenuEntry[0]));
		return menu;
	}

	private static void add(Menu menu, String option, String target, Consumer<MenuEntry> onClick)
	{
		add(menu, -1, option, target, onClick);
	}

	private static void add(Menu menu, int index, String option, String target, Consumer<MenuEntry> onClick)
	{
		menu.createMenuEntry(index)
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
			case Hit.DIALS:
				setMode(Mode.DIAL);
				break;
			case Hit.CLEAR:
				clearSelection();
				break;
			case Hit.BACK:
				openLayer(Layer.SURFACE);
				break;
			case Hit.TOGGLE_PANEL:
				panelChoice = !panelCollapsedNow;
				break;
			case Hit.TOGGLE_GROUPS:
				setGroupsOpen(!groupsOpen);
				break;
			case Hit.NEW_GROUP:
				newGroup(null);
				break;
			default:
				break;
		}
	}
}
