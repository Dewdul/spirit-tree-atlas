/*
 * Copyright (c) 2026, Dewdul
 * All rights reserved.
 * Licensed under the BSD 2-Clause License. See LICENSE.
 */
package com.spirittreeatlas;

import com.google.gson.Gson;
import java.awt.Color;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.ObjectID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.callback.ClientThread;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * DESIGN 4.9: a click on a spirit tree's "Travel" starts loading the map before the menu opens,
 * and the overview tiles the menu opens on outlast the rest of the caches.
 */
public class PrewarmTest
{
	private final SpiritTreeAtlasPlugin plugin = new SpiritTreeAtlasPlugin()
	{
		@Override
		String hiddenSetting(String key)
		{
			return null;
		}
	};
	private final FakeMenu f = FakeMenu.modern(FakeMenu.OPTIONS);
	private final TileStore tiles;

	public PrewarmTest() throws Exception
	{
		tiles = wire(plugin, f, "/fixtures/");
		// the interface holds another menu, so the ticks below do not open ours
		f.title().text = "Minecart rides";
	}

	/** Gives the plugin the fake client, the data and a store (decoding on the caller) under base. */
	private static TileStore wire(SpiritTreeAtlasPlugin plugin, FakeMenu f, String base) throws Exception
	{
		TreeRepository repo = TreeRepository.load(new Gson(), base);
		ClientThread clientThread = new ClientThread();
		set(ClientThread.class, clientThread, "client", f.client);
		SpiritTreeAtlasConfig config = new SpiritTreeAtlasConfig()
		{
		};
		TileStore store = new TileStore(repo.getIndex(), repo.getLayers(), base, Runnable::run);
		set(SpiritTreeAtlasPlugin.class, plugin, "client", f.client);
		set(SpiritTreeAtlasPlugin.class, plugin, "clientThread", clientThread);
		set(SpiritTreeAtlasPlugin.class, plugin, "config", config);
		set(SpiritTreeAtlasPlugin.class, plugin, "input", new AtlasInput(plugin));
		set(SpiritTreeAtlasPlugin.class, plugin, "repo", repo);
		set(SpiritTreeAtlasPlugin.class, plugin, "menu", new TreeMenu(f.client));
		set(SpiritTreeAtlasPlugin.class, plugin, "tiles", store);
		set(SpiritTreeAtlasPlugin.class, plugin, "overlay", new AtlasOverlay(f.client, plugin, config, null));
		return store;
	}

	private static void set(Class<?> type, Object target, String field, Object value) throws Exception
	{
		Field fd = type.getDeclaredField(field);
		fd.setAccessible(true);
		fd.set(target, value);
	}

	private static MenuOptionClicked click(MenuAction action, int id, String option, String target)
	{
		return click(action, id, option, target, 0, 0);
	}

	/** A click on an object at this scene tile (a game object entry's param0 and param1). */
	private static MenuOptionClicked click(MenuAction action, int id, String option, String target, int sceneX, int sceneY)
	{
		MenuEntry entry = (MenuEntry) Proxy.newProxyInstance(MenuEntry.class.getClassLoader(), new Class<?>[]{MenuEntry.class}, (p, m, args) ->
		{
			switch (m.getName())
			{
				case "getType":
					return action;
				case "getIdentifier":
					return id;
				case "getOption":
					return option;
				case "getTarget":
					return target;
				case "getParam0":
					return sceneX;
				case "getParam1":
					return sceneY;
				case "getItemId":
				case "getItemOp":
					return 0;
				case "isItemOp":
				case "isDeprioritized":
				case "isForceLeftClick":
					return false;
				default:
					return null;
			}
		});
		return new MenuOptionClicked(entry);
	}

	private void ticks(int n)
	{
		for (int i = 0; i < n; i++)
		{
			f.tick++;
			plugin.onGameTick(new GameTick());
		}
	}

	@Test
	public void onlyTheTreesTravelOpensTheMenu()
	{
		MenuAction op1 = MenuAction.GAME_OBJECT_FIRST_OPTION;
		// the travel locs, and the world trees by their multiloc parent, which their entries carry
		assertTrue(SpiritTreeAtlasPlugin.opensTreeMenu(op1, ObjectID.SPIRITTREE_SMALL, "Travel", "<col=ffff>Spirit tree"));
		assertTrue(SpiritTreeAtlasPlugin.opensTreeMenu(op1, ObjectID.SPIRITTREE_SMALL_2OPS, "Travel", ""));
		assertTrue(SpiritTreeAtlasPlugin.opensTreeMenu(op1, ObjectID.FARMING_SPIRIT_TREE_PATCH_5, "Travel", ""));
		assertTrue(SpiritTreeAtlasPlugin.opensTreeMenu(op1, ObjectID.POH_SPIRIT_TREE, "Travel", ""));
		assertTrue(SpiritTreeAtlasPlugin.opensTreeMenu(op1, ObjectID.POH_SPIRIT_RING, "Tree", ""));
		// by name, should an id change
		assertTrue(SpiritTreeAtlasPlugin.opensTreeMenu(MenuAction.GAME_OBJECT_THIRD_OPTION, 1, "Travel", "<col=ffff>Spirit Tree"));
		assertTrue(SpiritTreeAtlasPlugin.opensTreeMenu(op1, 1, "Tree", "<col=ffff>Spiritual Fairy Tree"));
		// other ops, other objects, and the same words elsewhere
		assertFalse(SpiritTreeAtlasPlugin.opensTreeMenu(op1, ObjectID.SPIRITTREE_SMALL, "Talk-to", "Spirit tree"));
		assertFalse(SpiritTreeAtlasPlugin.opensTreeMenu(op1, ObjectID.SPIRITTREE_SMALL, "Last-destination", "Spirit tree"));
		assertFalse(SpiritTreeAtlasPlugin.opensTreeMenu(op1, ObjectID.POH_SPIRIT_RING, "Ring-last-destination", ""));
		assertFalse(SpiritTreeAtlasPlugin.opensTreeMenu(op1, 1, "Travel", "Charter ship"));
		assertFalse(SpiritTreeAtlasPlugin.opensTreeMenu(op1, ObjectID.SPIRITTREE_SMALL, "Tree", "Spirit tree"));
		assertFalse(SpiritTreeAtlasPlugin.opensTreeMenu(MenuAction.NPC_FIRST_OPTION, ObjectID.SPIRITTREE_SMALL, "Travel", "Spirit tree"));
		assertFalse(SpiritTreeAtlasPlugin.opensTreeMenu(MenuAction.CC_OP, 1, "Travel", "Spirit tree"));
	}

	@Test
	public void travelLoadsTheMapItWillOpenOn()
	{
		plugin.onMenuOptionClicked(click(MenuAction.GAME_OBJECT_FIRST_OPTION, ObjectID.SPIRITTREE_SMALL, "Talk-to", "Spirit tree"));
		assertEquals(0, tiles.cachedBytes());
		MenuOptionClicked travel = click(MenuAction.GAME_OBJECT_FIRST_OPTION, ObjectID.SPIRITTREE_SMALL, "Travel", "<col=ffff>Spirit tree");
		plugin.onMenuOptionClicked(travel);
		// the click itself is the game's
		assertFalse(travel.isConsumed());
		assertTrue(tiles.cachedBytes() > 0);
		assertTrue(tiles.idle());
		assertTrue(tiles.fullTiles() > 0);
	}

	@Test
	public void theOverviewOutlastsTheRestOfTheCaches()
	{
		// a Travel click (or a close: the same clock) with fine tiles and the overview in the cache
		plugin.onMenuOptionClicked(click(MenuAction.GAME_OBJECT_FIRST_OPTION, ObjectID.SPIRITTREE_SMALL, "Travel", "Spirit tree"));
		assertNotNull(tiles.request(2, 40, 50));
		assertNotNull(tiles.request(0, 10, 12));
		ticks(49);
		assertNotNull(tiles.get(2, 40, 50));
		// about 30 s later the fine tiles go, the overview stays
		ticks(1);
		assertNull(tiles.get(2, 40, 50));
		assertNotNull(tiles.get(0, 10, 12));
		assertTrue(tiles.consistent());
		// a Travel click meanwhile restarts the clock (and loads what the next open needs)
		ticks(400);
		plugin.onMenuOptionClicked(click(MenuAction.GAME_OBJECT_FIRST_OPTION, ObjectID.SPIRITTREE_SMALL, "Travel", "Spirit tree"));
		ticks(499);
		assertNotNull(tiles.get(0, 10, 12));
		// about 5 minutes after, everything goes
		ticks(1);
		assertEquals(0, tiles.cachedBytes());
	}

	/** Travel on the tree at this world tile, the player still elsewhere (the scene starts at the surface's corner). */
	private void travelTo(double treeX, double treeY)
	{
		f.baseX = 2496;
		f.baseY = 3136;
		plugin.onMenuOptionClicked(click(MenuAction.GAME_OBJECT_FIRST_OPTION, ObjectID.SPIRITTREE_SMALL, "Travel", "Spirit tree",
			(int) treeX - f.baseX, (int) treeY - f.baseY));
	}

	/** The player arrives here and the menu opens: whether its first frame needed any tile not loaded ahead. */
	private boolean opensWarm(int x, int y)
	{
		f.player = new WorldPoint(x, y, 0);
		f.title().text = FakeMenu.TITLE;
		plugin.onScriptPreFired(new ScriptPreFired(9142));
		plugin.onScriptPostFired(new ScriptPostFired(9142));
		assertTrue(plugin.isOpen());
		MapView v = plugin.frameView(FakeMenu.SLOT);
		int decodes = tiles.decodes.get();
		int derived = tiles.derived.get();
		BufferedImage img = new BufferedImage(v.getW(), v.getH(), BufferedImage.TYPE_INT_RGB);
		boolean complete = MapRenderer.render(img, v, tiles, Color.BLACK);
		return complete && decodes == tiles.decodes.get() && derived == tiles.derived.get();
	}

	/**
	 * DESIGN 4.9: the click loads the view the menu will open on, around the clicked tree (where
	 * the player will be), not around where the player clicked from.
	 */
	@Test
	public void travelLoadsTheViewAroundTheClickedTree()
	{
		f.player = new WorldPoint(2700, 3300, 0);
		travelTo(2544.5, 3169.5);
		assertTrue(tiles.fullTiles() > 0);
		// around you opens at 2 ppt: z=1, derived from z=2
		assertNotNull(tiles.get(1, 20, 25));
		assertTrue(opensWarm(2545, 3171));
		assertEquals("TREE_GNOME_VILLAGE", plugin.getRepo().getHere());
	}

	@Test
	public void travelInAHouseLoadsTheViewAroundItsPortal()
	{
		f.instance = true;
		// a house in Yanille, just south of the fixture's land: the view is clamped to its edge
		f.varbits.put(VarbitID.POH_HOUSE_LOCATION, 6);
		plugin.onMenuOptionClicked(click(MenuAction.GAME_OBJECT_FIRST_OPTION, ObjectID.POH_SPIRIT_TREE, "Travel", "Spirit tree", 30, 40));
		assertNotNull(tiles.get(1, 20, 25));
		assertTrue(opensWarm(1900, 5700));
		MapView v = plugin.frameView(FakeMenu.SLOT);
		assertEquals(Layer.SURFACE, v.getLayer());
		assertEquals(SpiritTreeAtlasPlugin.AROUND_PPT, v.getPpt(), 1e-9);
	}

	/** The tiles of the level the menu opened on (z=1 around you) and coarser outlast the rest. */
	@Test
	public void theOpenViewsLevelOutlastsTheRest()
	{
		travelTo(2544.5, 3169.5);
		assertNotNull(tiles.request(2, 40, 50));
		assertNotNull(tiles.get(1, 20, 25));
		ticks(50);
		assertNull(tiles.get(2, 40, 50));
		assertNotNull(tiles.get(1, 20, 25));
		assertTrue(tiles.consistent());
		ticks(449);
		assertNotNull(tiles.get(1, 20, 25));
		ticks(1);
		assertEquals(0, tiles.cachedBytes());
	}

	/**
	 * On a wide map the quick-select panel's width moves the view's centre, and the tree you are
	 * at widens it (its "You" tag): the click must see the panel as the open will, with "here" the
	 * clicked tree, not the last one, and the panel open or folded as saved (these widths put the
	 * difference across a tile edge on the real map).
	 */
	@Test
	public void travelSeesThePanelAsTheOpenWill() throws Exception
	{
		String[][] trips = {{"BATTLEFIELD_OF_KHAZARD", "GRAND_EXCHANGE", "1253"}, {"GRAND_EXCHANGE", "BATTLEFIELD_OF_KHAZARD", "1345"},
			{"GRAND_EXCHANGE", "BATTLEFIELD_OF_KHAZARD", "1851"}, {null, "GRAND_EXCHANGE", "1253"}, {null, "BATTLEFIELD_OF_KHAZARD", "1345"}};
		for (String[] trip : trips)
		{
			// the panel folded as saved: read before the first open too
			String panelSaved = trip[0] == null ? "false" : null;
			SpiritTreeAtlasPlugin p = new SpiritTreeAtlasPlugin()
			{
				@Override
				String hiddenSetting(String key)
				{
					return SpiritTreeAtlasPlugin.KEY_PANEL_OPEN.equals(key) ? panelSaved : null;
				}
			};
			FakeMenu m = FakeMenu.modern(FakeMenu.OPTIONS);
			TileStore store = wire(p, m, SpiritTreeAtlasPlugin.RESOURCES);
			Rectangle wide = new Rectangle(0, 0, Integer.parseInt(trip[2]), 905);
			Tree to = p.getRepo().tree(trip[1]);
			m.title().text = FakeMenu.TITLE;
			if (trip[0] != null)
			{
				// opened (and the map laid out) at the first tree, then closed
				Tree from = p.getRepo().tree(trip[0]);
				m.player = new WorldPoint((int) from.getX(), (int) from.getY(), 0);
				p.onScriptPreFired(new ScriptPreFired(9142));
				p.onScriptPostFired(new ScriptPostFired(9142));
				p.frameView(wide);
				p.onWidgetClosed(new WidgetClosed(InterfaceID.MENU_NEW, 0, true));
			}
			else
			{
				// not opened yet this session: the layout gives this rect
				set(SpiritTreeAtlasPlugin.class, p, "view", MapView.of(p.getRepo().surface(), wide));
			}
			m.tick += 20;
			m.baseX = (int) to.getX() - 52;
			m.baseY = (int) to.getY() - 52;
			p.onMenuOptionClicked(click(MenuAction.GAME_OBJECT_FIRST_OPTION, ObjectID.SPIRITTREE_SMALL, "Travel", "Spirit tree", 52, 52));
			m.player = new WorldPoint((int) to.getX(), (int) to.getY(), 0);
			p.onScriptPreFired(new ScriptPreFired(9142));
			p.onScriptPostFired(new ScriptPostFired(9142));
			assertEquals(trip[1], p.getRepo().getHere());
			MapView v = p.frameView(wide);
			int decodes = store.decodes.get();
			int derived = store.derived.get();
			assertTrue(MapRenderer.render(new BufferedImage(v.getW(), v.getH(), BufferedImage.TYPE_INT_RGB), v, store, Color.BLACK));
			assertEquals(String.join(" ", trip), decodes, store.decodes.get());
			assertEquals(String.join(" ", trip), derived, store.derived.get());
		}
	}

	@Test
	public void theLoginScreenReleasesEverything()
	{
		plugin.onMenuOptionClicked(click(MenuAction.GAME_OBJECT_FIRST_OPTION, ObjectID.SPIRITTREE_SMALL, "Travel", "Spirit tree"));
		assertTrue(tiles.cachedBytes() > 0);
		GameStateChanged e = new GameStateChanged();
		e.setGameState(GameState.LOGIN_SCREEN);
		plugin.onGameStateChanged(e);
		assertEquals(0, tiles.cachedBytes());
	}

	@Test
	public void nothingLoadsAheadWhileTheMenuIsOpen() throws Exception
	{
		set(SpiritTreeAtlasPlugin.class, plugin, "open", true);
		plugin.onMenuOptionClicked(click(MenuAction.GAME_OBJECT_FIRST_OPTION, ObjectID.SPIRITTREE_SMALL, "Travel", "Spirit tree"));
		assertEquals(0, tiles.cachedBytes());
	}
}
