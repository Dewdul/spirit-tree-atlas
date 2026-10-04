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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
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
import net.runelite.api.events.PostMenuSort;
import net.runelite.api.events.ResizeableChanged;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.ui.JagexColors;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ColorUtil;

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
	private static final long ANIM_MS = 250;
	private static final String TELEPORT_MAPS = "com.mjhylkema.TeleportMaps.TeleportMapsPlugin";
	private static final String SPIRIT_TREE_MENU = "com.spirit.SpiritTreeMenuPlugin";
	static final String TELEPORT_MAPS_NOTICE = "Teleport Maps is showing its spirit tree map - turn that off in Teleport Maps to use Spirit Tree Atlas";
	static final String SPIRIT_TREE_MENU_NOTICE = "Spirit Tree Menu is rearranging this menu - turn it off to use Spirit Tree Atlas";

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

	// --- client thread state
	private boolean needsInitialView;
	private int playerX;
	private int playerY;
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

	@Provides
	SpiritTreeAtlasConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(SpiritTreeAtlasConfig.class);
	}

	@Override
	protected void startUp()
	{
		repo = TreeRepository.load(gson, RESOURCES);
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
			Thread t = new Thread(r, "spirit-tree-atlas-tiles");
			t.setDaemon(true);
			t.setPriority(Thread.MIN_PRIORITY);
			return t;
		});
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
			m.close();
			modalSlot.restore(client);
			restoreMouseoverText();
			overlay.reset();
			view = null;
			selected = null;
			hits = Collections.emptyList();
			holes = Collections.emptyList();
			travelHole = null;
			ex.shutdownNow();
			store.close();
		});
	}

	// ------------------------------------------------------------------ open / close

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
		if (modalSlot.holds() && TreeMenu.Style.forGroup(e.getGroupId()) == null)
		{
			// before the new interface's first frame: if it took the menu's slot (or the toplevel
			// changed), put the slot back now rather than at the next tick
			updateSlot();
		}
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed e)
	{
		if (open && TreeMenu.Style.forGroup(e.getGroupId()) == menu.getStyle())
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
		mode = reopen ? closedMode : config.openInMapMode() ? Mode.MAP : Mode.LIST;
		if (!reopen)
		{
			selected = null;
			needsInitialView = true;
		}
		menu.open(s);
		repo.placeHouse(client.getVarbitValue(VarbitID.POH_HOUSE_LOCATION));
		repo.setLast(client.getVarbitValue(VarbitID.SPIRIT_TREE_PREVIOUS));
		readRows();
		locatePlayer();
		notice = stepAside(s);
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
		hits = Collections.emptyList();
		holes = Collections.emptyList();
		travelHole = null;
		mapButton = null;
	}

	/** Client thread: releases the decoded tiles and the map-sized buffers while the menu is closed. */
	private void trimCaches()
	{
		trimmed = true;
		tiles.trim();
		overlay.releaseCaches();
	}

	private void locatePlayer()
	{
		Player p = client.getLocalPlayer();
		if (p == null)
		{
			repo.locate(Integer.MIN_VALUE / 2, Integer.MIN_VALUE / 2, -1, false);
			return;
		}
		WorldPoint wp = p.getWorldLocation();
		playerX = wp.getX();
		playerY = wp.getY();
		WorldView wv = client.getTopLevelWorldView();
		repo.locate(wp.getX(), wp.getY(), wp.getPlane(), wv != null && wv.isInstance());
	}

	/**
	 * DESIGN 4.10: the notice when another plugin owns this menu (Teleport Maps showing its own
	 * spirit tree map; Spirit Tree Menu rearranging the classic one), else null. We then change
	 * nothing. Never declared as a conflict: that would turn off all of Teleport Maps' maps.
	 */
	private String stepAside(TreeMenu.Style s)
	{
		try
		{
			for (Plugin p : pluginManager.getPlugins())
			{
				String name = p.getClass().getName();
				if (TELEPORT_MAPS.equals(name) && pluginManager.isPluginEnabled(p)
					&& !"false".equals(configManager.getConfiguration("teleportmaps", "showSpiritTreeMap")))
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
			// the interface tree is gone: drop our records rather than touch orphaned widgets
			open = false;
			closedTick = client.getTickCount();
			closedMode = mode;
			if (view != null)
			{
				sessionView = view;
			}
			menu.forget();
			notice = null;
			restoreMouseoverText();
			hits = Collections.emptyList();
			holes = Collections.emptyList();
			travelHole = null;
			mapButton = null;
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
		if (open && !menu.isOpen(menu.getStyle()))
		{
			closeMenu();
		}
		else if (open)
		{
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
			int tick = client.getTickCount();
			if (!trimmed && (tick - closedTick >= TRIM_TICKS || tick < closedTick))
			{
				trimCaches();
			}
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
		if (SpiritTreeAtlasConfig.GROUP.equals(e.getGroup()))
		{
			clientThread.invoke(() ->
			{
				if (open)
				{
					applyMenu();
				}
				else if (modalSlot.holds())
				{
					updateSlot();
				}
			});
		}
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

	/** DESIGN 4.7: fit every surface marker, centre on where you are, or the session's last view. */
	private MapView initialView(Rectangle rect)
	{
		Insets in = chromeInsets();
		Tree here = repo.tree(repo.getHere());
		boolean hereMapped = here != null && here.isMapped();
		switch (config.openAt())
		{
			case REMEMBER:
				Layer l = sessionView == null ? null : repo.layer(sessionView.getLayer());
				if (l != null)
				{
					return sessionView.withRect(rect).withLayer(l);
				}
				break;
			case AROUND_YOU:
				double x = hereMapped ? here.getX() : playerX;
				double y = hereMapped ? here.getY() : playerY;
				Layer at = hereMapped ? repo.layer(here.getLayer()) : repo.layerAt(playerX + 0.5, playerY + 0.5);
				if (at != null)
				{
					return MapView.of(at, rect).focusOn(x + 0.5, y + 0.5, 2, in);
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

	/** Screen space the chrome takes from the map: the top bar. */
	static Insets chromeInsets()
	{
		return new Insets(ChromePainter.BAR_H, 0, 0, 0);
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
		animateTo(v.focusOn(t.getX() + 0.5, t.getY() + 0.5, Math.max(v.getPpt() * 2, 4), chromeInsets()));
	}

	/** Fit: every surface marker on the surface, the layer's bounds elsewhere. */
	void fit()
	{
		MapView v = view;
		if (v != null)
		{
			animateTo(Layer.SURFACE.equals(v.getLayer()) ? fitTrees(v, repo.surfaceMarkers(), chromeInsets())
				: v.fit(v.getBx0(), v.getBy0(), v.getBx1(), v.getBy1(), 12, chromeInsets()));
		}
	}

	/** A portal or a stand-in: open its layer fitted, or fit it again when it is already open. */
	void openLayerFitted(String id)
	{
		Layer l = repo.layer(id);
		MapView v = view;
		if (l != null && v != null && !l.isSurface() && l.getId().equals(v.getLayer()))
		{
			animateTo(fitLayer(l, v.rect(), chromeInsets()));
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
		setView(saved != null ? saved.withRect(v.rect()) : fitLayer(l, v.rect(), chromeInsets()));
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

	/** The overlay's results of a frame: hits and holes in Map mode, the Map button in List mode, the Travel hole. */
	void publish(List<Hit> newHits, List<Rectangle> newHoles, Rectangle newMapButton, Rectangle newTravelHole)
	{
		hits = newHits;
		holes = newHoles;
		mapButton = newMapButton;
		travelHole = newTravelHole;
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

	// ------------------------------------------------------------------ menu ownership

	@Subscribe
	public void onPostMenuSort(PostMenuSort e)
	{
		if (!open || notice != null || client.isMenuOpen())
		{
			return;
		}
		menuFor = null;
		Point mp = client.getMouseCanvasPosition();
		int x = mp.getX();
		int y = mp.getY();
		if (mode == Mode.LIST)
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
			default:
				break;
		}
	}
}
