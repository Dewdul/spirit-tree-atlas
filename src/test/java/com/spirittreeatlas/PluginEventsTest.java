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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.JPanel;
import net.runelite.api.GameState;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.WidgetPositionMode;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.PluginChanged;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
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

		/** The hidden settings, in place of the config manager. */
		final Map<String, String> settings = new HashMap<>();

		@Override
		String hiddenSetting(String key)
		{
			return settings.get(key);
		}

		@Override
		void saveHiddenSetting(String key, String value)
		{
			settings.put(key, value);
		}
	}

	private final Plugin plugin = new Plugin();
	/** The "Open at" setting; null for its default. */
	private SpiritTreeAtlasConfig.OpenAt openOn;
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
			@Override
			public OpenAt openOn()
			{
				return openOn != null ? openOn : SpiritTreeAtlasConfig.super.openOn();
			}
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
		assertArrayEquals(new int[]{WidgetPositionMode.ABSOLUTE_LEFT, WidgetPositionMode.ABSOLUTE_TOP, 184, 244}, universe().position());
		FakeMenu.W[] text = f.get(InterfaceID.MenuNew.TEXT).children;
		for (int i = 0; i < text.length; i++)
		{
			assertEquals("row " + i, i != shown, text[i].hidden);
			// the Travel row is the 200x32 button, the others keep the game's 161x20
			assertEquals("row " + i, i == shown ? 200 : 161, text[i].w);
			assertEquals("row " + i, i == shown ? 32 : 20, text[i].h);
		}
		assertNull(f.hotkeysBlocked());
		assertFalse(f.mouseover);
	}

	/** Map mode is in place on the classic menu: the parchment model hidden, never the layer holding the key listeners. */
	private void assertClassicMap()
	{
		assertTrue(f.parchmentModel().hidden);
		assertNull(f.hotkeysBlocked());
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
	public void aClassicRebuildNeverLeavesUsHoldingWhatOthersHid() throws Exception
	{
		start(FakeMenu.classic(FakeMenu.OPTIONS));
		script(CLASSIC_SCRIPT);
		plugin.select("GRAND_EXCHANGE");
		FakeMenu.W bar = f.get(InterfaceID.Menu.LJ_SCROLL_BAR);
		assertTrue(bar.hidden);
		// 217 runs again; at its ScriptPostFired, before ours, Better Teleport Menu's "Expand scroll
		// menu" makes the list taller and hides the scrollbar it no longer needs
		plugin.onScriptPreFired(new ScriptPreFired(CLASSIC_SCRIPT));
		assertFalse(bar.hidden);
		f.rebuild(Arrays.copyOf(FakeMenu.OPTIONS, 12));
		bar.hidden = true;
		plugin.onScriptPostFired(new ScriptPostFired(CLASSIC_SCRIPT));
		assertClassicMap();
		assertFalse(f.get(InterfaceID.Menu.LJ_LAYER1).children[3].hidden);
		plugin.setMode(SpiritTreeAtlasPlugin.Mode.LIST);
		assertTrue(bar.hidden);
		assertFalse(f.parchmentModel().hidden);
		plugin.onWidgetClosed(new WidgetClosed(InterfaceID.MENU, 0, true));
		assertTrue(bar.hidden);
	}

	@Test
	public void aMenuBuiltByAScriptWeDoNotHookIsCaughtAtTheNextTick() throws Exception
	{
		start(FakeMenu.classic(FakeMenu.OPTIONS));
		script(CLASSIC_SCRIPT);
		assertTrue(plugin.isOpen());
		assertClassicMap();
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
		assertClassicMap();
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
			assertTrue(s.name(), f.parchmentModel().hidden);
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
	public void theWheelOnTheMapNeverReachesTheGame() throws Exception
	{
		start(FakeMenu.classic(FakeMenu.OPTIONS));
		script(CLASSIC_SCRIPT);
		plugin.select("GRAND_EXCHANGE");
		MapView v = plugin.frameView(FakeMenu.SLOT);
		Rectangle hole = new Rectangle(100 + 304, 200 + 296, 200, 32);
		plugin.publish(Collections.emptyList(), Collections.singletonList(hole), null, hole, 0);
		// on the map: zoom
		assertTrue(input.mouseWheelMoved(wheel(300, 300)).isConsumed());
		MapView zoomed = plugin.frameView(FakeMenu.SLOT);
		assertNotEquals(v.getPpt(), zoomed.getPpt(), 1e-9);
		// over the Travel row: kept from the game, which would scroll the list; no zoom
		assertTrue(input.mouseWheelMoved(wheel(hole.x + 5, hole.y + 5)).isConsumed());
		assertEquals(zoomed.getPpt(), plugin.frameView(FakeMenu.SLOT).getPpt(), 1e-9);
		// while a right-click menu is open too
		set(SpiritTreeAtlasPlugin.class, plugin, "menuOpen", true);
		assertTrue(input.mouseWheelMoved(wheel(300, 300)).isConsumed());
		assertEquals(zoomed.getPpt(), plugin.frameView(FakeMenu.SLOT).getPpt(), 1e-9);
		set(SpiritTreeAtlasPlugin.class, plugin, "menuOpen", false);
		// off the map the game keeps it
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
		plugin.publish(Collections.emptyList(), Collections.emptyList(), button, null, 0);
		// the game's menu was built elsewhere: a left press there must not run its stale entry
		assertTrue(input.mousePressed(press(200, 240)).isConsumed());
		assertTrue(input.mouseReleased(press(200, 240)).isConsumed());
		// built for the button: the press goes to the game, which runs our "Show Map"
		set(SpiritTreeAtlasPlugin.class, plugin, "menuFor", "mapButton");
		assertFalse(input.mousePressed(press(200, 240)).isConsumed());
		// off the button nothing is ours
		assertFalse(input.mousePressed(press(20, 20)).isConsumed());
	}

	// ------------------------------------------------------------------ quick-select panel (DESIGN 4.6, 4.8)

	private static Object get(Class<?> type, Object target, String field) throws Exception
	{
		Field fd = type.getDeclaredField(field);
		fd.setAccessible(true);
		return fd.get(target);
	}

	private static Hit row(String id, TreeRepository repo, Rectangle area)
	{
		Tree t = repo.tree(id);
		return new Hit(Hit.Kind.ROW, area, t, null, "Select", t.getLabel());
	}

	/**
	 * A left press on a quick-select row goes to the game only when the game's menu was built for
	 * that row (its "Select" on top); otherwise, as on the panel's body, it is swallowed and never pans.
	 */
	@Test
	public void aQuickSelectRowRunsOnlyItsOwnEntry() throws Exception
	{
		start(FakeMenu.modern(FakeMenu.OPTIONS));
		script(MODERN_SCRIPT);
		MapView v = plugin.frameView(FakeMenu.SLOT);
		Rectangle panel = new Rectangle(106, 228, 150, 200);
		Hit ge = row("GRAND_EXCHANGE", plugin.getRepo(), new Rectangle(110, 250, 142, 18));
		Hit stronghold = row("GNOME_STRONGHOLD", plugin.getRepo(), new Rectangle(110, 269, 142, 18));
		plugin.publish(Arrays.asList(new Hit(Hit.Kind.PANEL, panel, null, null, null, null), ge, stronghold), Collections.emptyList(), null, null, 0);
		// no menu built for it (a stale entry from elsewhere): swallowed, nothing selected
		assertTrue(input.mousePressed(press(120, 255)).isConsumed());
		assertTrue(input.mouseReleased(press(120, 255)).isConsumed());
		assertNull(plugin.getSelected());
		// a menu built for another row: swallowed too
		set(SpiritTreeAtlasPlugin.class, plugin, "menuFor", SpiritTreeAtlasPlugin.menuKey(stronghold));
		assertTrue(input.mousePressed(press(120, 255)).isConsumed());
		input.mouseReleased(press(120, 255));
		// built for this row: the press goes to the game, which runs our Select
		set(SpiritTreeAtlasPlugin.class, plugin, "menuFor", SpiritTreeAtlasPlugin.menuKey(ge));
		assertFalse(input.mousePressed(press(120, 255)).isConsumed());
		assertNotEquals(SpiritTreeAtlasPlugin.menuKey(ge), SpiritTreeAtlasPlugin.menuKey(new Hit(Hit.Kind.MARKER, ge.getArea(), ge.getTree(), null, "Select", "")));
		// the panel's body absorbs a press, and a drag from it does not pan
		assertTrue(input.mousePressed(press(200, 420)).isConsumed());
		input.mouseDragged(new MouseEvent(new JPanel(), MouseEvent.MOUSE_DRAGGED, 0, MouseEvent.BUTTON1_DOWN_MASK, 300, 480, 0, false));
		assertTrue(input.mouseReleased(press(300, 480)).isConsumed());
		assertEquals(v, plugin.frameView(FakeMenu.SLOT));
	}

	/** The wheel over the panel scrolls it while its rows overflow; otherwise it zooms the map as elsewhere. */
	@Test
	public void theWheelOverThePanelScrollsOnlyWhenItOverflows() throws Exception
	{
		start(FakeMenu.modern(FakeMenu.OPTIONS));
		script(MODERN_SCRIPT);
		MapView v = plugin.frameView(FakeMenu.SLOT);
		Rectangle panel = new Rectangle(106, 228, 150, 200);
		List<Hit> hits = Arrays.asList(new Hit(Hit.Kind.PANEL, panel, null, null, null, null),
			row("GRAND_EXCHANGE", plugin.getRepo(), new Rectangle(110, 250, 142, 18)));
		// everything fits: zoom
		plugin.publish(hits, Collections.emptyList(), null, null, 0);
		assertTrue(input.mouseWheelMoved(wheel(120, 255)).isConsumed());
		MapView zoomed = plugin.frameView(FakeMenu.SLOT);
		assertNotEquals(v.getPpt(), zoomed.getPpt(), 1e-9);
		assertEquals(0, plugin.getPanelScroll());
		// the rows overflow by 50 px: the wheel scrolls them (clamped), over a row or the body, and does not zoom
		plugin.publish(hits, Collections.emptyList(), null, null, 50);
		assertTrue(input.mouseWheelMoved(wheel(120, 255)).isConsumed());
		assertEquals(SpiritTreeAtlasPlugin.PANEL_WHEEL_STEP, plugin.getPanelScroll());
		assertTrue(input.mouseWheelMoved(wheel(200, 420)).isConsumed());
		assertEquals(50, plugin.getPanelScroll());
		assertEquals(zoomed.getPpt(), plugin.frameView(FakeMenu.SLOT).getPpt(), 1e-9);
		input.mouseWheelMoved(new MouseWheelEvent(new JPanel(), MouseEvent.MOUSE_WHEEL, 0, 0, 120, 255, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, -3));
		assertEquals(0, plugin.getPanelScroll());
		// a frame where the rows fit again brings the scroll back within range
		plugin.scrollPanel(50);
		plugin.publish(hits, Collections.emptyList(), null, null, 10);
		assertEquals(10, plugin.getPanelScroll());
		// off the panel the wheel zooms
		assertTrue(input.mouseWheelMoved(wheel(400, 300)).isConsumed());
		assertNotEquals(zoomed.getPpt(), plugin.frameView(FakeMenu.SLOT).getPpt(), 1e-9);
	}

	/**
	 * Selecting from a row selects exactly as the marker does (the Travel row moves in at once) and
	 * pans at the current zoom only when the tree's marker, or its surface stand-in, is out of view.
	 */
	@Test
	public void aQuickSelectRowSelectsAndPansOnlyWhenOutOfView() throws Exception
	{
		start(FakeMenu.modern(FakeMenu.OPTIONS));
		script(MODERN_SCRIPT);
		MapView v = plugin.frameView(FakeMenu.SLOT);
		Tree village = plugin.getRepo().tree("TREE_GNOME_VILLAGE");
		MapView close = v.focusOn(village.getX() + 0.5, village.getY() + 0.5, 16, SpiritTreeAtlasPlugin.chromeInsets(ChromePainter.TAB_W));
		plugin.setView(close);
		// in view: selected, the Travel row in the corner, no pan
		plugin.showTree("TREE_GNOME_VILLAGE");
		assertEquals("TREE_GNOME_VILLAGE", plugin.getSelected());
		assertFalse(f.get(InterfaceID.MenuNew.TEXT).children[0].hidden);
		assertNull(get(SpiritTreeAtlasPlugin.class, plugin, "animTo"));
		// out of view: a pan to it at the same zoom
		plugin.showTree("BATTLEFIELD_OF_KHAZARD");
		assertEquals("BATTLEFIELD_OF_KHAZARD", plugin.getSelected());
		assertFalse(f.get(InterfaceID.MenuNew.TEXT).children[2].hidden);
		assertTrue(f.get(InterfaceID.MenuNew.TEXT).children[0].hidden);
		MapView to = (MapView) get(SpiritTreeAtlasPlugin.class, plugin, "animTo");
		assertEquals(16, to.getPpt(), 1e-9);
		assertEquals(Layer.SURFACE, to.getLayer());
		Tree khazard = plugin.getRepo().tree("BATTLEFIELD_OF_KHAZARD");
		assertTrue(to.rect().contains((int) to.screenX(khazard.getX() + 0.5), (int) to.screenY(khazard.getY() + 0.5)));
		// a Prifddinas tree on the surface: to its stand-in, staying on the surface
		plugin.setView(close);
		plugin.showTree("PRIFDDINAS");
		to = (MapView) get(SpiritTreeAtlasPlugin.class, plugin, "animTo");
		assertNotNull(to);
		assertEquals(Layer.SURFACE, to.getLayer());
		assertEquals(Layer.SURFACE, plugin.frameView(FakeMenu.SLOT).getLayer());
		// a surface tree from the Prifddinas map: back to the surface
		plugin.openLayer(Layer.PRIFDDINAS);
		plugin.showTree("TREE_GNOME_VILLAGE");
		assertEquals(Layer.SURFACE, plugin.frameView(FakeMenu.SLOT).getLayer());
	}

	/**
	 * The panel is open by default but starts closed on a narrow (fixed mode) map; the player's
	 * choice holds at any size for the session and is saved in a hidden key.
	 */
	@Test
	public void thePanelStartsClosedOnNarrowMapsAndKeepsThePlayersChoice() throws Exception
	{
		start(FakeMenu.modern(FakeMenu.OPTIONS));
		script(MODERN_SCRIPT);
		assertFalse(plugin.isPanelOpen(512));
		assertTrue(plugin.isPanelOpen(SpiritTreeAtlasPlugin.NARROW_MAP));
		// opened in fixed mode: open at any size, nothing to save (open is the default)
		plugin.setPanelOpen(true);
		assertTrue(plugin.isPanelOpen(512));
		assertNull(plugin.settings.get(SpiritTreeAtlasPlugin.KEY_PANEL_OPEN));
		// closed: closed everywhere, and saved
		plugin.setPanelOpen(false);
		assertFalse(plugin.isPanelOpen(1600));
		assertEquals("false", plugin.settings.get(SpiritTreeAtlasPlugin.KEY_PANEL_OPEN));
		plugin.setPanelOpen(true);
		assertEquals("true", plugin.settings.get(SpiritTreeAtlasPlugin.KEY_PANEL_OPEN));

		// a new session with the panel saved closed: closed on a wide map too
		Plugin next = new Plugin();
		next.settings.put(SpiritTreeAtlasPlugin.KEY_PANEL_OPEN, "false");
		set(SpiritTreeAtlasPlugin.class, next, "client", f.client);
		set(SpiritTreeAtlasPlugin.class, next, "clientThread", get(SpiritTreeAtlasPlugin.class, plugin, "clientThread"));
		set(SpiritTreeAtlasPlugin.class, next, "config", get(SpiritTreeAtlasPlugin.class, plugin, "config"));
		set(SpiritTreeAtlasPlugin.class, next, "input", new AtlasInput(next));
		set(SpiritTreeAtlasPlugin.class, next, "repo", plugin.getRepo());
		set(SpiritTreeAtlasPlugin.class, next, "menu", new TreeMenu(f.client));
		f.tick += 10;
		next.onScriptPostFired(new ScriptPostFired(MODERN_SCRIPT));
		assertTrue(next.isOpen());
		assertFalse(next.isPanelOpen(1600));
	}

	/** DESIGN 4.11: the quick-select list is on by default, in the Map section. */
	@Test
	public void theQuickSelectListIsOnByDefault() throws Exception
	{
		SpiritTreeAtlasConfig config = new SpiritTreeAtlasConfig()
		{
		};
		assertTrue(config.quickSelect());
		net.runelite.client.config.ConfigItem item = SpiritTreeAtlasConfig.class.getMethod("quickSelect")
			.getAnnotation(net.runelite.client.config.ConfigItem.class);
		assertEquals("quickSelect", item.keyName());
		assertEquals("Quick select list", item.name());
		assertEquals(SpiritTreeAtlasConfig.mapSection, item.section());
		assertFalse(item.hidden());
	}

	/** The menu opens with the player on this tile; returns the view it opens on (fixed mode: the tab, not the panel). */
	private MapView openAt(int x, int y, int plane) throws Exception
	{
		if (f == null)
		{
			start(FakeMenu.modern(FakeMenu.OPTIONS));
		}
		f.player = new WorldPoint(x, y, plane);
		f.tick += 10;
		script(MODERN_SCRIPT);
		assertTrue(plugin.isOpen());
		return plugin.frameView(FakeMenu.SLOT);
	}

	private void close()
	{
		plugin.onWidgetClosed(new WidgetClosed(InterfaceID.MENU_NEW, 0, true));
		assertFalse(plugin.isOpen());
	}

	/** At 2 ppt on this layer, with this world point in the middle of the map clear of the top bar and the tab. */
	private static void assertAround(MapView v, String layer, double wx, double wy)
	{
		java.awt.Insets in = SpiritTreeAtlasPlugin.chromeInsets(ChromePainter.TAB_W);
		assertEquals(layer, v.getLayer());
		assertEquals(SpiritTreeAtlasPlugin.AROUND_PPT, v.getPpt(), 1e-9);
		assertEquals(v.getX() + in.left + (v.getW() - in.left) / 2.0, v.screenX(wx), 1e-6);
		assertEquals(v.getY() + in.top + (v.getH() - in.top) / 2.0, v.screenY(wy), 1e-6);
	}

	/** Every surface marker fitted, clear of the chrome: Fit all, and where around you cannot apply. */
	private MapView fitAll()
	{
		return SpiritTreeAtlasPlugin.fitTrees(MapView.of(plugin.getRepo().surface(), FakeMenu.SLOT), plugin.getRepo().surfaceMarkers(),
			SpiritTreeAtlasPlugin.chromeInsets(ChromePainter.TAB_W));
	}

	/** DESIGN 4.7 (the user, 2026-10-04): the map opens around the tree you are at. */
	@Test
	public void opensAroundTheTreeYouAreAt() throws Exception
	{
		// a few tiles from the Tree Gnome Village tree: centred on the tree, not on the player
		MapView v = openAt(2541, 3172, 0);
		assertEquals("TREE_GNOME_VILLAGE", plugin.getRepo().getHere());
		Tree village = plugin.getRepo().tree("TREE_GNOME_VILLAGE");
		assertAround(v, Layer.SURFACE, village.getX() + 0.5, village.getY() + 0.5);
		// on a wide map the open panel is kept clear too
		Rectangle wide = new Rectangle(0, 0, 1600, 900);
		close();
		f.tick += 10;
		script(MODERN_SCRIPT);
		v = plugin.frameView(wide);
		java.awt.Insets in = SpiritTreeAtlasPlugin.chromeInsets(((AtlasOverlay) get(SpiritTreeAtlasPlugin.class, plugin, "overlay")).panelWidth(1600));
		assertTrue(in.left > ChromePainter.TAB_W + 6);
		assertEquals(in.left + (1600 - in.left) / 2.0, v.screenX(village.getX() + 0.5), 1e-6);
		assertEquals(in.top + (900 - in.top) / 2.0, v.screenY(village.getY() + 0.5), 1e-6);
	}

	@Test
	public void opensOnThePrifddinasMapAtItsTree() throws Exception
	{
		MapView v = openAt(3272, 6121, 0);
		assertEquals("PRIFDDINAS", plugin.getRepo().getHere());
		Tree prif = plugin.getRepo().tree("PRIFDDINAS");
		assertAround(v, Layer.PRIFDDINAS, prif.getX() + 0.5, prif.getY() + 0.5);
		// elsewhere in the city: centred on the player, still on its map
		close();
		v = openAt(3200, 6000, 0);
		assertNull(plugin.getRepo().getHere());
		assertAround(v, Layer.PRIFDDINAS, 3200.5, 6000.5);
	}

	@Test
	public void opensAroundThePlayerAwayFromEveryTree() throws Exception
	{
		MapView v = openAt(2700, 3300, 0);
		assertNull(plugin.getRepo().getHere());
		assertAround(v, Layer.SURFACE, 2700.5, 3300.5);
		// off every map (a dungeon): every tree fitted
		close();
		assertEquals(fitAll(), openAt(2700, 9700, 0));
	}

	/**
	 * In an instance (your house, a friend's, any other) the tiles are not the world's: the map
	 * opens on your house's portal when the house is placed, as a view only (the house is never
	 * "here", so no "You" pin), else fitted.
	 */
	@Test
	public void inAnInstanceItOpensOnYourHouse() throws Exception
	{
		start(FakeMenu.modern(FakeMenu.OPTIONS));
		f.instance = true;
		// a house in Prifddinas: its map, centred on the portal; the instance tile would be "here" outside one
		f.varbits.put(VarbitID.POH_HOUSE_LOCATION, 9);
		MapView v = openAt(2544, 3169, 0);
		assertNull(plugin.getRepo().getHere());
		Tree house = plugin.getRepo().house();
		assertEquals(3239, house.getX(), 1e-9);
		assertAround(v, Layer.PRIFDDINAS, house.getX() + 0.5, house.getY() + 0.5);
		// a house on the surface (Rimmington, outside the fixture's land: the view is clamped to it)
		close();
		f.varbits.put(VarbitID.POH_HOUSE_LOCATION, 1);
		v = openAt(2544, 3169, 0);
		assertNull(plugin.getRepo().getHere());
		assertEquals(Layer.SURFACE, v.getLayer());
		assertEquals(SpiritTreeAtlasPlugin.AROUND_PPT, v.getPpt(), 1e-9);
		// no house placed: every tree fitted
		close();
		f.varbits.put(VarbitID.POH_HOUSE_LOCATION, 0);
		assertEquals(fitAll(), openAt(2544, 3169, 0));
		// the other settings still apply in a house
		close();
		openOn = SpiritTreeAtlasConfig.OpenAt.FIT_ALL;
		f.varbits.put(VarbitID.POH_HOUSE_LOCATION, 9);
		assertEquals(fitAll(), openAt(2544, 3169, 0));
	}

	@Test
	public void fitAllAndRememberStayAvailable() throws Exception
	{
		start(FakeMenu.modern(FakeMenu.OPTIONS));
		openOn = SpiritTreeAtlasConfig.OpenAt.FIT_ALL;
		assertEquals(fitAll(), openAt(2541, 3172, 0));
		close();
		// at the Prifddinas tree, Fit all opens that layer fitted
		MapView v = openAt(3272, 6121, 0);
		assertEquals(SpiritTreeAtlasPlugin.fitLayer(plugin.getRepo().layer(Layer.PRIFDDINAS), FakeMenu.SLOT,
			SpiritTreeAtlasPlugin.chromeInsets(ChromePainter.TAB_W)), v);
		// Remember: the view the menu last closed on
		MapView left = fitAll().focusOn(2600, 3200, 8, null);
		plugin.setView(left);
		close();
		openOn = SpiritTreeAtlasConfig.OpenAt.REMEMBER;
		assertEquals(left, openAt(2541, 3172, 0));
	}

	/** DESIGN 4.1: a reopen within 3 ticks keeps the view; a later one opens around you again. */
	@Test
	public void aQuickReopenKeepsTheViewALaterOneOpensAroundYou() throws Exception
	{
		openAt(2541, 3172, 0);
		MapView moved = fitAll().focusOn(2700, 3300, 8, null);
		plugin.setView(moved);
		close();
		f.player = new WorldPoint(2700, 3200, 0);
		f.tick += 3;
		script(MODERN_SCRIPT);
		assertEquals(moved, plugin.frameView(FakeMenu.SLOT));
		close();
		f.tick += 4;
		script(MODERN_SCRIPT);
		assertAround(plugin.frameView(FakeMenu.SLOT), Layer.SURFACE, 2700.5, 3200.5);
	}

	/**
	 * DESIGN 4.11: "Open at" defaults to around you under a new key: RuneLite had already written
	 * the old key's FIT_ALL into every profile, which a new default would never have reached.
	 */
	@Test
	public void openAtDefaultsToAroundYouUnderANewKey() throws Exception
	{
		SpiritTreeAtlasConfig config = new SpiritTreeAtlasConfig()
		{
		};
		assertEquals(SpiritTreeAtlasConfig.OpenAt.AROUND_YOU, config.openOn());
		net.runelite.client.config.ConfigItem item = SpiritTreeAtlasConfig.class.getMethod("openOn")
			.getAnnotation(net.runelite.client.config.ConfigItem.class);
		assertEquals("openOn", item.keyName());
		assertEquals("Open at", item.name());
		assertEquals(SpiritTreeAtlasConfig.mapSection, item.section());
		assertFalse(item.hidden());
		for (java.lang.reflect.Method m : SpiritTreeAtlasConfig.class.getMethods())
		{
			net.runelite.client.config.ConfigItem ci = m.getAnnotation(net.runelite.client.config.ConfigItem.class);
			assertTrue(ci == null || !"openAt".equals(ci.keyName()));
		}
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
