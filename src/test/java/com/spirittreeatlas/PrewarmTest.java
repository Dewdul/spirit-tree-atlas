/*
 * Copyright (c) 2026, Dewdul
 * All rights reserved.
 * Licensed under the BSD 2-Clause License. See LICENSE.
 */
package com.spirittreeatlas;

import com.google.gson.Gson;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.ObjectID;
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
	private static final int MODERN_SCRIPT = 9142;

	private final SpiritTreeAtlasPlugin plugin = new SpiritTreeAtlasPlugin();
	private final FakeMenu f = FakeMenu.modern(FakeMenu.OPTIONS);
	private final TileStore tiles;

	public PrewarmTest() throws Exception
	{
		TreeRepository repo = TreeRepository.load(new Gson(), "/fixtures/");
		ClientThread clientThread = new ClientThread();
		set(ClientThread.class, clientThread, "client", f.client);
		SpiritTreeAtlasConfig config = new SpiritTreeAtlasConfig()
		{
		};
		tiles = new TileStore(repo.getIndex(), repo.getLayers(), "/fixtures/", Runnable::run);
		set(SpiritTreeAtlasPlugin.class, plugin, "client", f.client);
		set(SpiritTreeAtlasPlugin.class, plugin, "clientThread", clientThread);
		set(SpiritTreeAtlasPlugin.class, plugin, "config", config);
		set(SpiritTreeAtlasPlugin.class, plugin, "input", new AtlasInput(plugin));
		set(SpiritTreeAtlasPlugin.class, plugin, "repo", repo);
		set(SpiritTreeAtlasPlugin.class, plugin, "menu", new TreeMenu(f.client));
		set(SpiritTreeAtlasPlugin.class, plugin, "tiles", tiles);
		set(SpiritTreeAtlasPlugin.class, plugin, "overlay", new AtlasOverlay(f.client, plugin, config, null));
		// the interface holds another menu, so the ticks below do not open ours
		f.title().text = "Minecart rides";
	}

	private static void set(Class<?> type, Object target, String field, Object value) throws Exception
	{
		Field fd = type.getDeclaredField(field);
		fd.setAccessible(true);
		fd.set(target, value);
	}

	private static MenuOptionClicked click(MenuAction action, int id, String option, String target)
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
				case "getParam1":
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
		// a menu opened and closed, its fine tiles and its overview in the cache
		f.title().text = FakeMenu.TITLE;
		plugin.onScriptPreFired(new ScriptPreFired(MODERN_SCRIPT));
		plugin.onScriptPostFired(new ScriptPostFired(MODERN_SCRIPT));
		assertTrue(plugin.isOpen());
		assertNotNull(tiles.request(2, 40, 50));
		assertNotNull(tiles.request(0, 10, 12));
		plugin.onWidgetClosed(new WidgetClosed(InterfaceID.MENU_NEW, 0, true));
		f.title().text = "Minecart rides";
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
	public void nothingLoadsAheadWhileTheMenuIsOpen()
	{
		f.title().text = FakeMenu.TITLE;
		plugin.onScriptPreFired(new ScriptPreFired(MODERN_SCRIPT));
		plugin.onScriptPostFired(new ScriptPostFired(MODERN_SCRIPT));
		assertTrue(plugin.isOpen());
		plugin.onMenuOptionClicked(click(MenuAction.GAME_OBJECT_FIRST_OPTION, ObjectID.SPIRITTREE_SMALL, "Travel", "Spirit tree"));
		assertEquals(0, tiles.cachedBytes());
	}
}
