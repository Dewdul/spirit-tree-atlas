/*
 * Copyright (c) 2026, Dewdul
 * All rights reserved.
 * Licensed under the BSD 2-Clause License. See LICENSE.
 */
package com.spirittreeatlas;

import com.google.gson.Gson;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.swing.JPanel;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.WidgetPositionMode;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.PluginChanged;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * The plugin's event handling (DESIGN 3.3, 4.1-4.3, 4.10) against the fake menus: open, rebuild,
 * close and reopen, another menu on the same interface, an interface move, logout and hop, List
 * mode, stepping aside while the menu is open, the per-tick row read, and the mouse wheel over
 * the Travel row. Everything is put back exactly, and never anything we did not hide.
 */
public class PluginEventsTest
{
	private static final int MODERN_SCRIPT = 9142;
	private static final int CLASSIC_SCRIPT = 217;

	/** The plugin with the other plugins' ownership under the test's control. */
	private static final class Plugin extends SpiritTreeAtlasPlugin
	{
		String owner;

		@Override
		String stepAside(TreeMenu.Style s)
		{
			return owner;
		}
	}

	private final Plugin plugin = new Plugin();
	private FakeMenu f;
	private List<String> pristine;
	private AtlasInput input;

	private void start(FakeMenu menu) throws Exception
	{
		f = menu;
		pristine = snapshot(f);
		TreeRepository repo = TreeRepository.load(new Gson(), "/fixtures/");
		ClientThread clientThread = new ClientThread();
		set(ClientThread.class, clientThread, "client", f.client);
		input = new AtlasInput(plugin);
		SpiritTreeAtlasConfig config = new SpiritTreeAtlasConfig()
		{
		};
		set(SpiritTreeAtlasPlugin.class, plugin, "client", f.client);
		set(SpiritTreeAtlasPlugin.class, plugin, "clientThread", clientThread);
		set(SpiritTreeAtlasPlugin.class, plugin, "config", config);
		set(SpiritTreeAtlasPlugin.class, plugin, "input", input);
		set(SpiritTreeAtlasPlugin.class, plugin, "repo", repo);
		set(SpiritTreeAtlasPlugin.class, plugin, "menu", new TreeMenu(f.client));
		set(SpiritTreeAtlasPlugin.class, plugin, "tiles", new TileStore(repo.getIndex(), repo.getLayers(), "/fixtures/", Runnable::run));
		set(SpiritTreeAtlasPlugin.class, plugin, "overlay", new AtlasOverlay(f.client, plugin, config, null));
	}

	private static void set(Class<?> type, Object target, String field, Object value) throws Exception
	{
		Field fd = type.getDeclaredField(field);
		fd.setAccessible(true);
		fd.set(target, value);
	}

	/** Snapshot of every widget's hidden flag and position fields. */
	private static List<String> snapshot(FakeMenu f)
	{
		List<String> out = new ArrayList<>();
		for (FakeMenu.W w : f.all())
		{
			out.add(w.id + "/" + w.index + " " + w.hidden + " " + Arrays.toString(w.position()));
		}
		Collections.sort(out);
		return out;
	}

	private void script(int id)
	{
		plugin.onScriptPreFired(new ScriptPreFired(id));
		plugin.onScriptPostFired(new ScriptPostFired(id));
	}

	private void tick()
	{
		f.tick++;
		plugin.onGameTick(new GameTick());
	}

	private void gameState(GameState s)
	{
		GameStateChanged e = new GameStateChanged();
		e.setGameState(s);
		plugin.onGameStateChanged(e);
	}

	private FakeMenu.W universe()
	{
		return f.get(InterfaceID.MenuNew.UNIVERSE);
	}

	/** Map mode is in place on the modern menu, with this row index as the Travel row (or none). */
	private void assertModernMap(int shown)
	{
		assertArrayEquals(new int[]{WidgetPositionMode.ABSOLUTE_LEFT, WidgetPositionMode.ABSOLUTE_TOP, 174, 256}, universe().position());
		FakeMenu.W[] text = f.get(InterfaceID.MenuNew.TEXT).children;
		for (int i = 0; i < text.length; i++)
		{
			assertEquals("row " + i, i != shown, text[i].hidden);
		}
		assertFalse(f.mouseover);
	}

	private void assertPristine()
	{
		assertEquals(pristine, snapshot(f));
		assertTrue(f.mouseover);
	}

	@Test
	public void opensOnItsTitleOnlyAndRebuildsOnTheGamesOwnState() throws Exception
	{
		start(FakeMenu.modern(FakeMenu.OPTIONS));
		// another menu of the same interface is left alone
		f.title().text = "Where would you like to teleport to?";
		script(MODERN_SCRIPT);
		assertFalse(plugin.isOpen());
		assertPristine();

		f.title().text = "<col=ff981f>" + FakeMenu.TITLE;
		script(MODERN_SCRIPT);
		assertTrue(plugin.isOpen());
		assertEquals(SpiritTreeAtlasPlugin.Mode.MAP, plugin.getMode());
		assertModernMap(-1);
		plugin.select("GRAND_EXCHANGE");
		assertModernMap(3);

		// the setup script runs again: put back first, so it works on the game's own state...
		plugin.onScriptPreFired(new ScriptPreFired(MODERN_SCRIPT));
		assertEquals(pristine, snapshot(f));
		// ...then the new rows get Map mode, the selection kept
		f.rebuild(FakeMenu.OPTIONS);
		plugin.onScriptPostFired(new ScriptPostFired(MODERN_SCRIPT));
		assertModernMap(3);
		assertEquals("GRAND_EXCHANGE", plugin.getSelected());

		// the same interface now holds a different menu: a close, everything put back
		plugin.onScriptPreFired(new ScriptPreFired(MODERN_SCRIPT));
		f.title().text = "Select Obelisk destination";
		plugin.onScriptPostFired(new ScriptPostFired(MODERN_SCRIPT));
		assertFalse(plugin.isOpen());
		assertPristine();
	}

	@Test
	public void aClassicRebuildNeverLeavesUsHoldingWhatTheGameHid() throws Exception
	{
		start(FakeMenu.classic(FakeMenu.OPTIONS));
		script(CLASSIC_SCRIPT);
		plugin.select("GRAND_EXCHANGE");
		FakeMenu.W bar = f.get(InterfaceID.Menu.LJ_SCROLL_BAR);
		assertTrue(bar.hidden);
		// 217 runs again with 12 rows: they fit, so the game hides the scrollbar itself
		plugin.onScriptPreFired(new ScriptPreFired(CLASSIC_SCRIPT));
		assertFalse(bar.hidden);
		f.rebuild(Arrays.copyOf(FakeMenu.OPTIONS, 12));
		bar.hidden = true;
		plugin.onScriptPostFired(new ScriptPostFired(CLASSIC_SCRIPT));
		assertTrue(f.get(InterfaceID.Menu.LJ_LAYER2).hidden);
		assertFalse(f.get(InterfaceID.Menu.LJ_LAYER1).children[3].hidden);
		plugin.setMode(SpiritTreeAtlasPlugin.Mode.LIST);
		assertTrue(bar.hidden);
		assertFalse(f.get(InterfaceID.Menu.LJ_LAYER2).hidden);
		plugin.onWidgetClosed(new WidgetClosed(InterfaceID.MENU, 0, true));
		assertTrue(bar.hidden);
	}

	@Test
	public void aMenuBuiltByAScriptWeDoNotHookIsCaughtAtTheNextTick() throws Exception
	{
		start(FakeMenu.classic(FakeMenu.OPTIONS));
		script(CLASSIC_SCRIPT);
		assertTrue(plugin.isOpen());
		assertTrue(f.get(InterfaceID.Menu.LJ_LAYER2).hidden);
		f.title().text = "Minecart rides";
		tick();
		assertFalse(plugin.isOpen());
		assertPristine();
		// and it is not opened again on its own
		tick();
		assertFalse(plugin.isOpen());
	}

	@Test
	public void aMissedSetupScriptIsCaughtAtTheNextTick() throws Exception
	{
		start(FakeMenu.classic(FakeMenu.OPTIONS));
		tick();
		assertTrue(plugin.isOpen());
		assertTrue(f.get(InterfaceID.Menu.LJ_LAYER2).hidden);
	}

	@Test
	public void closeAndReopenWithinThreeTicksKeepsModeAndSelection() throws Exception
	{
		start(FakeMenu.modern(FakeMenu.OPTIONS));
		script(MODERN_SCRIPT);
		plugin.select("FELDIP_HILLS");
		plugin.setMode(SpiritTreeAtlasPlugin.Mode.LIST);
		assertPristine();
		plugin.onWidgetClosed(new WidgetClosed(InterfaceID.MENU_NEW, 0, true));
		assertFalse(plugin.isOpen());
		assertPristine();

		f.tick += 3;
		script(MODERN_SCRIPT);
		assertEquals(SpiritTreeAtlasPlugin.Mode.LIST, plugin.getMode());
		assertEquals("FELDIP_HILLS", plugin.getSelected());
		assertPristine();
		plugin.setMode(SpiritTreeAtlasPlugin.Mode.MAP);
		assertModernMap(4);

		plugin.onWidgetClosed(new WidgetClosed(InterfaceID.MENU_NEW, 0, true));
		f.tick += 4;
		script(MODERN_SCRIPT);
		assertEquals(SpiritTreeAtlasPlugin.Mode.MAP, plugin.getMode());
		assertNull(plugin.getSelected());
		assertModernMap(-1);
	}

	@Test
	public void movingTheInterfaceIsNotAClose() throws Exception
	{
		start(FakeMenu.modern(FakeMenu.OPTIONS));
		script(MODERN_SCRIPT);
		plugin.select("GRAND_EXCHANGE");
		// fixed <-> resizable: the interface moves to the other toplevel without being unloaded
		plugin.onWidgetClosed(new WidgetClosed(InterfaceID.MENU_NEW, 0, false));
		assertTrue(plugin.isOpen());
		assertModernMap(3);
		tick();
		assertModernMap(3);
	}

	@Test
	public void logoutAndHopPutEverythingBackButALoadDoesNot() throws Exception
	{
		start(FakeMenu.classic(FakeMenu.OPTIONS));
		script(CLASSIC_SCRIPT);
		for (GameState s : new GameState[]{GameState.LOADING, GameState.CONNECTION_LOST, GameState.LOGGED_IN})
		{
			gameState(s);
			assertTrue(s.name(), plugin.isOpen());
			assertTrue(s.name(), f.get(InterfaceID.Menu.LJ_LAYER2).hidden);
		}
		gameState(GameState.HOPPING);
		assertFalse(plugin.isOpen());
		assertPristine();

		script(CLASSIC_SCRIPT);
		gameState(GameState.LOGIN_SCREEN);
		assertFalse(plugin.isOpen());
		assertPristine();
	}

	@Test
	public void steppingAsideWhileOpen() throws Exception
	{
		start(FakeMenu.modern(FakeMenu.OPTIONS));
		script(MODERN_SCRIPT);
		plugin.select("GRAND_EXCHANGE");
		assertModernMap(3);

		// Teleport Maps turned on while the menu is open: everything put back at once
		plugin.owner = SpiritTreeAtlasPlugin.TELEPORT_MAPS_NOTICE;
		plugin.onPluginChanged(new PluginChanged(plugin, true));
		assertEquals(SpiritTreeAtlasPlugin.TELEPORT_MAPS_NOTICE, plugin.getNotice());
		assertPristine();
		tick();
		assertPristine();
		assertTrue(plugin.isOpen());
		// and off again: it never had the menu, so the map comes back
		plugin.owner = null;
		ConfigChanged c = new ConfigChanged();
		c.setGroup("teleportmaps");
		c.setKey("showSpiritTreeMap");
		plugin.onConfigChanged(c);
		assertNull(plugin.getNotice());
		assertModernMap(3);

		// it had the menu when it opened: turned off, the menu may still hold its changes
		plugin.onWidgetClosed(new WidgetClosed(InterfaceID.MENU_NEW, 0, true));
		f.tick += 10;
		plugin.owner = SpiritTreeAtlasPlugin.TELEPORT_MAPS_NOTICE;
		script(MODERN_SCRIPT);
		assertEquals(SpiritTreeAtlasPlugin.TELEPORT_MAPS_NOTICE, plugin.getNotice());
		assertPristine();
		plugin.owner = null;
		plugin.onPluginChanged(new PluginChanged(plugin, false));
		assertEquals(SpiritTreeAtlasPlugin.REOPEN_NOTICE, plugin.getNotice());
		// while stepping aside the title is not checked (Teleport Maps deletes the classic one)
		f.title().text = "";
		tick();
		assertTrue(plugin.isOpen());
		assertPristine();
		// the next open is ours again
		f.title().text = FakeMenu.TITLE;
		plugin.onWidgetClosed(new WidgetClosed(InterfaceID.MENU_NEW, 0, true));
		f.tick += 10;
		script(MODERN_SCRIPT);
		assertNull(plugin.getNotice());
		assertModernMap(-1);
	}

	@Test
	public void keysAreReadAgainEachTick() throws Exception
	{
		start(FakeMenu.modern(FakeMenu.OPTIONS));
		script(MODERN_SCRIPT);
		assertEquals("4", plugin.getRepo().key("GRAND_EXCHANGE"));
		int state = plugin.getRepo().getStateHash();
		f.get(InterfaceID.MenuNew.TEXT).children[3].text = "<col=ffffff>G</col>: Grand Exchange";
		tick();
		assertEquals("G", plugin.getRepo().key("GRAND_EXCHANGE"));
		assertNotEquals(state, plugin.getRepo().getStateHash());
		// unbound: no key at all
		f.get(InterfaceID.MenuNew.TEXT).children[3].text = "Grand Exchange";
		tick();
		assertNull(plugin.getRepo().key("GRAND_EXCHANGE"));
		assertEquals(Tree.Status.AVAILABLE, plugin.getRepo().status("GRAND_EXCHANGE"));
	}

	@Test
	public void theWheelOverTheTravelRowNeverReachesTheGame() throws Exception
	{
		start(FakeMenu.classic(FakeMenu.OPTIONS));
		script(CLASSIC_SCRIPT);
		plugin.select("GRAND_EXCHANGE");
		Rectangle hole = new Rectangle(100 + 334, 200 + 312, 170, 16);
		plugin.publish(Collections.emptyList(), Collections.singletonList(hole), null, hole);
		assertTrue(input.mouseWheelMoved(wheel(hole.x + 5, hole.y + 5)).isConsumed());
		// elsewhere with no map drawn yet the game keeps it
		assertFalse(input.mouseWheelMoved(wheel(5, 5)).isConsumed());
		// in List mode the list is the game's
		plugin.setMode(SpiritTreeAtlasPlugin.Mode.LIST);
		assertFalse(input.mouseWheelMoved(wheel(hole.x + 5, hole.y + 5)).isConsumed());
	}

	@Test
	public void theMapButtonRunsOnlyItsOwnEntry() throws Exception
	{
		start(FakeMenu.modern(FakeMenu.OPTIONS));
		script(MODERN_SCRIPT);
		plugin.setMode(SpiritTreeAtlasPlugin.Mode.LIST);
		Rectangle button = new Rectangle(187, 232, 50, 20);
		plugin.publish(Collections.emptyList(), Collections.emptyList(), button, null);
		// the game's menu was built elsewhere: a left press there must not run its stale entry
		assertTrue(input.mousePressed(press(200, 240)).isConsumed());
		assertTrue(input.mouseReleased(press(200, 240)).isConsumed());
		// built for the button: the press goes to the game, which runs our "Show Map"
		set(SpiritTreeAtlasPlugin.class, plugin, "menuFor", "mapButton");
		assertFalse(input.mousePressed(press(200, 240)).isConsumed());
		// off the button nothing is ours
		assertFalse(input.mousePressed(press(20, 20)).isConsumed());
	}

	private static MouseWheelEvent wheel(int x, int y)
	{
		return new MouseWheelEvent(new JPanel(), MouseEvent.MOUSE_WHEEL, 0, 0, x, y, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, 1);
	}

	private static MouseEvent press(int x, int y)
	{
		return new MouseEvent(new JPanel(), MouseEvent.MOUSE_PRESSED, 0, MouseEvent.BUTTON1_DOWN_MASK, x, y, 1, false, MouseEvent.BUTTON1);
	}
}
