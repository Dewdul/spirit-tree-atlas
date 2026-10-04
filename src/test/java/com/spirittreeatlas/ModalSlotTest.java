/*
 * Copyright (c) 2026, Dewdul
 * All rights reserved.
 * Licensed under the BSD 2-Clause License. See LICENSE.
 */
package com.spirittreeatlas;

import java.awt.Point;
import java.awt.Rectangle;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetPositionMode;
import net.runelite.api.widgets.WidgetSizeMode;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;

/**
 * "Use free space": where the dials' slot goes ({@link MapLayout#slotCorner}), the guards that keep
 * us off a slot that is not ours, and the move / restore paths against a fake toplevel.
 */
public class ModalSlotTest
{
	private static final int CLASSIC = InterfaceID.ToplevelOsrsStretch.MAINMODAL;
	private static final int MODERN = InterfaceID.ToplevelPreEoc.MAINMODAL;
	private static final int[] PRISTINE = ModalSlot.PRISTINE;

	// ------------------------------------------------------------------ slotCorner

	/** Toplevel 161 at 1600x900: HUD area is the canvas minus 250x165, chat bottom-left, side panel bottom-right. */
	private static final Rectangle CANVAS = new Rectangle(0, 0, 1600, 900);
	private static final Rectangle HUD = new Rectangle(0, 0, 1350, 735);
	private static final List<Rectangle> CLASSIC_OBSTACLES = Arrays.asList(
		new Rectangle(0, 735, 519, 165), new Rectangle(1359, 565, 241, 335), new Rectangle(1384, 602, 190, 261));

	private static Rectangle dialsAt(Point corner)
	{
		return new Rectangle(corner.x - 512, corner.y - 334, 512, 334);
	}

	@Test
	public void uncappedMapFillsTheFreeSpace()
	{
		Point c = MapLayout.slotCorner(CANVAS, HUD, 512, 334, CLASSIC_OBSTACLES, 2000, 1400);
		assertEquals(new Point(1344, 729), c);
		Rectangle dials = dialsAt(c);
		assertTrue(HUD.contains(dials));
		Rectangle map = MapLayout.compute(CANVAS, dials, CLASSIC_OBSTACLES, 2000, 1400);
		// all of the HUD area but its 6 px inset: before the move it was 1100x601 at most
		assertEquals(new Rectangle(6, 6, 1338, 723), map);
		for (Rectangle o : CLASSIC_OBSTACLES)
		{
			assertFalse(map.intersects(o));
		}
	}

	@Test
	public void cappedMapIsCentredInTheFreeSpace()
	{
		Point c = MapLayout.slotCorner(CANVAS, HUD, 512, 334, CLASSIC_OBSTACLES, 1100, 720);
		assertNotNull(c);
		Rectangle map = MapLayout.compute(CANVAS, dialsAt(c), CLASSIC_OBSTACLES, 1100, 720);
		assertEquals(1100, map.width);
		assertEquals(720, map.height);
		int leftGap = map.x - 6;
		int rightGap = 1344 - (map.x + map.width);
		int topGap = map.y - 6;
		int bottomGap = 729 - (map.y + map.height);
		assertTrue(leftGap + " / " + rightGap, Math.abs(leftGap - rightGap) <= 1);
		assertTrue(topGap + " / " + bottomGap, Math.abs(topGap - bottomGap) <= 1);
		// the dials, with Teleport and close in their corner, are the map's bottom-right
		assertEquals(c.x, map.x + map.width);
		assertEquals(c.y, map.y + map.height);
	}

	@Test
	public void noMoveWhenTheDialsDoNotFit()
	{
		// the smallest resizable window: the HUD area is barely larger than the dials
		Rectangle canvas = new Rectangle(0, 0, 765, 503);
		Rectangle hud = new Rectangle(0, 0, 515, 338);
		assertNull(MapLayout.slotCorner(canvas, hud, 512, 334, Collections.emptyList(), 2000, 1400));
	}

	@Test
	public void noMoveForASmallGain()
	{
		// the free corner is within a few pixels of where the game centres the dials
		Rectangle canvas = new Rectangle(0, 0, 2000, 2000);
		Rectangle hud = new Rectangle(0, 0, 530, 350);
		assertNull(MapLayout.slotCorner(canvas, hud, 512, 334, Collections.emptyList(), 2000, 1400));
		// and a real gain is taken
		assertNotNull(MapLayout.slotCorner(canvas, new Rectangle(0, 0, 560, 350), 512, 334, Collections.emptyList(), 2000, 1400));
	}

	@Test
	public void modernLayoutKeepsTheDialsOffTheSidePanel()
	{
		// toplevel 164, wide window: the side panel and one-row tab bar reach into the HUD area
		Rectangle canvas = new Rectangle(0, 0, 1920, 1080);
		Rectangle hud = new Rectangle(0, 0, 1705, 915);
		List<Rectangle> obstacles = Arrays.asList(new Rectangle(0, 915, 519, 165), new Rectangle(1480, 740, 440, 340));
		Point c = MapLayout.slotCorner(canvas, hud, 512, 334, obstacles, 2000, 1400);
		assertNotNull(c);
		Rectangle dials = dialsAt(c);
		assertTrue(hud.contains(dials));
		Rectangle map = MapLayout.compute(canvas, dials, obstacles, 2000, 1400);
		for (Rectangle o : obstacles)
		{
			assertFalse(o + " under the dials", dials.intersects(o));
			assertFalse(o + " under the map", map.intersects(o));
		}
		// the cut keeps the most room: left of the side panel, full height
		assertEquals(1472, c.x);
		assertEquals(909, c.y);
	}

	@Test
	public void manyWindowSizesKeepTheDialsInsideAndClear()
	{
		int[][] sizes = {{765, 503}, {800, 600}, {1024, 768}, {1280, 720}, {1600, 900}, {1920, 1080}, {2560, 1440}, {1000, 1400}};
		for (int[] s : sizes)
		{
			int w = s[0];
			int h = s[1];
			Rectangle canvas = new Rectangle(0, 0, w, h);
			Rectangle hud = new Rectangle(0, 0, w - 250, h - 165);
			List<Rectangle> obstacles = Arrays.asList(new Rectangle(0, h - 165, 519, 165), new Rectangle(w - 241, h - 335, 241, 335),
				new Rectangle(w - 216, h - 298, 190, 261));
			Point c = MapLayout.slotCorner(canvas, hud, 512, 334, obstacles, 2000, 1400);
			if (c == null)
			{
				continue;
			}
			String at = w + "x" + h + " gave " + c;
			Rectangle dials = dialsAt(c);
			assertTrue(at, hud.contains(dials));
			Rectangle map = MapLayout.compute(canvas, dials, obstacles, 2000, 1400);
			assertTrue(at, map.contains(dials));
			assertTrue(at, canvas.contains(map));
			for (Rectangle o : obstacles)
			{
				assertFalse(at, map.intersects(o));
			}
		}
	}

	// ------------------------------------------------------------------ pure guards

	@Test
	public void fitsOnlyTheDefinedSlotFilledByTheDials()
	{
		int a = WidgetSizeMode.ABSOLUTE;
		Rectangle filled = new Rectangle(0, 0, 512, 334);
		assertTrue(ModalSlot.fits(a, a, 512, 334, 512, 334, filled));
		// a modal that sized the slot to its parent (toplevel_mainmodal_bg_trans -2/-3)
		assertFalse(ModalSlot.fits(WidgetSizeMode.MINUS, a, 512, 334, 1350, 334, filled));
		assertFalse(ModalSlot.fits(a, WidgetSizeMode.MINUS, 512, 334, 512, 735, filled));
		// another plugin resized it
		assertFalse(ModalSlot.fits(a, a, 512, 500, 512, 500, filled));
		assertFalse(ModalSlot.fits(a, a, 512, 334, 512, 500, filled));
		// the dials are not exactly the slot
		assertFalse(ModalSlot.fits(a, a, 512, 334, 512, 334, new Rectangle(1, 0, 512, 334)));
		assertFalse(ModalSlot.fits(a, a, 512, 334, 512, 334, new Rectangle(0, 0, 0, 0)));
	}

	@Test
	public void layoutMustBeDrawnAndInsideTheCanvas()
	{
		assertTrue(ModalSlot.laidOut(CANVAS, HUD));
		// a new toplevel not drawn yet
		assertFalse(ModalSlot.laidOut(CANVAS, new Rectangle(-1, -1, 1350, 735)));
		// mid-shrink: the canvas is new, the HUD area still the old, larger one
		assertFalse(ModalSlot.laidOut(new Rectangle(0, 0, 1280, 720), new Rectangle(0, 0, 1750, 917)));
		assertFalse(ModalSlot.laidOut(CANVAS, null));
	}

	@Test
	public void oursOnlyWhileItHoldsWhatWeWrote()
	{
		int[] written = {WidgetPositionMode.ABSOLUTE_RIGHT, WidgetPositionMode.ABSOLUTE_BOTTOM, 6, 6};
		assertTrue(ModalSlot.isOurs(new int[]{WidgetPositionMode.ABSOLUTE_RIGHT, WidgetPositionMode.ABSOLUTE_BOTTOM, 6, 6}, written));
		// Fixed Resizable Hybrid (or anyone) re-anchored it since
		assertFalse(ModalSlot.isOurs(new int[]{WidgetPositionMode.ABSOLUTE_RIGHT, WidgetPositionMode.ABSOLUTE_TOP, 6, 6}, written));
		assertFalse(ModalSlot.isOurs(new int[]{WidgetPositionMode.ABSOLUTE_RIGHT, WidgetPositionMode.ABSOLUTE_BOTTOM, 6, 40}, written));
		assertFalse(ModalSlot.isOurs(PRISTINE, written));
		assertFalse(ModalSlot.isOurs(PRISTINE, null));
	}

	@Test
	public void nextStep()
	{
		// not held: move only a pristine slot that fits
		assertEquals(ModalSlot.Step.MOVE, ModalSlot.next(false, false, true, true, true));
		assertEquals(ModalSlot.Step.NOTHING, ModalSlot.next(false, false, true, false, true));
		assertEquals(ModalSlot.Step.NOTHING, ModalSlot.next(false, false, false, true, true));
		assertEquals(ModalSlot.Step.NOTHING, ModalSlot.next(false, false, true, true, false));
		// held and still ours: keep it moved while wanted, else put it back
		assertEquals(ModalSlot.Step.MOVE, ModalSlot.next(true, true, true, false, true));
		assertEquals(ModalSlot.Step.RESTORE, ModalSlot.next(true, true, true, false, false));
		assertEquals(ModalSlot.Step.RESTORE, ModalSlot.next(true, true, false, false, true));
		// held but changed by someone else: never written again
		assertEquals(ModalSlot.Step.LET_GO, ModalSlot.next(true, false, true, false, true));
		assertEquals(ModalSlot.Step.LET_GO, ModalSlot.next(true, false, true, true, false));
	}

	@Test
	public void targetOffsetsAnchorTheCornerToTheContainer()
	{
		assertArrayEquals(new int[]{WidgetPositionMode.ABSOLUTE_RIGHT, WidgetPositionMode.ABSOLUTE_BOTTOM, 6, 6},
			ModalSlot.target(HUD, new Point(1344, 729)));
		assertArrayEquals(new int[]{WidgetPositionMode.ABSOLUTE_RIGHT, WidgetPositionMode.ABSOLUTE_BOTTOM, 125, 2},
			ModalSlot.target(new Rectangle(10, 20, 1350, 735), new Point(1235, 753)));
	}

	@Test
	public void onlyTheResizableModalSlots()
	{
		assertTrue(ModalSlot.isResizableModal(CLASSIC));
		assertTrue(ModalSlot.isResizableModal(MODERN));
		assertFalse(ModalSlot.isResizableModal(InterfaceID.Toplevel.MAINMODAL));
	}

	// ------------------------------------------------------------------ against a fake toplevel

	/** A widget backed by plain fields; the slot is laid out in its container like the game does on revalidate. */
	private static final class Fake
	{
		final int id;
		Fake parent;
		Rectangle bounds;
		int xMode = WidgetPositionMode.ABSOLUTE_CENTER;
		int yMode = WidgetPositionMode.ABSOLUTE_CENTER;
		int x;
		int y;
		int wMode = WidgetSizeMode.ABSOLUTE;
		int hMode = WidgetSizeMode.ABSOLUTE;
		int w = 512;
		int h = 334;
		int revalidations;
		Widget proxy;

		Fake(int id)
		{
			this.id = id;
		}

		int[] position()
		{
			return new int[]{xMode, yMode, x, y};
		}

		void layOut()
		{
			Rectangle p = parent.bounds;
			int bx = xMode == WidgetPositionMode.ABSOLUTE_RIGHT ? p.x + p.width - w - x : p.x + (p.width - w) / 2 + x;
			int by = yMode == WidgetPositionMode.ABSOLUTE_BOTTOM ? p.y + p.height - h - y
				: yMode == WidgetPositionMode.ABSOLUTE_TOP ? p.y + y : p.y + (p.height - h) / 2 + y;
			bounds = new Rectangle(bx, by, w, h);
		}
	}

	private final Map<Integer, Fake> live = new HashMap<>();
	private Fake hud;
	private Fake slot;
	/** The dials' root rect: it fills whatever slot it is mounted in. */
	private Fake dialsMountedIn;
	private Widget dials;
	private Client client;
	private ModalSlot modal;
	private Rectangle canvas = new Rectangle(CANVAS);
	/** The dials' drawn bounds as the game reports them before their first frame, when set. */
	private Rectangle dialsDrawn;

	private Widget proxy(Fake f)
	{
		f.proxy = (Widget) Proxy.newProxyInstance(Widget.class.getClassLoader(), new Class<?>[]{Widget.class}, (p, m, args) ->
		{
			switch (m.getName())
			{
				case "equals":
					return p == args[0];
				case "hashCode":
					return System.identityHashCode(p);
				case "getId":
					return f.id;
				case "getParent":
					return f.parent == null ? null : f.parent.proxy;
				case "getBounds":
					return f.bounds == null ? null : new Rectangle(f.bounds);
				case "getWidth":
				case "getOriginalWidth":
					return f.w;
				case "getHeight":
				case "getOriginalHeight":
					return f.h;
				case "getWidthMode":
					return f.wMode;
				case "getHeightMode":
					return f.hMode;
				case "getXPositionMode":
					return f.xMode;
				case "getYPositionMode":
					return f.yMode;
				case "getOriginalX":
					return f.x;
				case "getOriginalY":
					return f.y;
				case "setXPositionMode":
					f.xMode = (int) args[0];
					return p;
				case "setYPositionMode":
					f.yMode = (int) args[0];
					return p;
				case "setOriginalX":
					f.x = (int) args[0];
					return p;
				case "setOriginalY":
					f.y = (int) args[0];
					return p;
				case "revalidate":
					f.revalidations++;
					f.layOut();
					return null;
				case "isHidden":
					return false;
				default:
					return m.getReturnType() == Widget.class ? p : null;
			}
		});
		return f.proxy;
	}

	private Fake slot(int id)
	{
		Fake s = new Fake(id);
		s.parent = hud;
		proxy(s);
		s.layOut();
		live.put(id, s);
		return s;
	}

	@Before
	public void setUp()
	{
		hud = new Fake(InterfaceID.ToplevelOsrsStretch.HUD_CONTAINER_FRONT);
		hud.bounds = new Rectangle(HUD);
		proxy(hud);
		live.put(hud.id, hud);
		slot = slot(CLASSIC);
		dialsMountedIn = slot;
		Fake d = new Fake(InterfaceID.Fairyrings.ROOT_RECT0);
		dials = (Widget) Proxy.newProxyInstance(Widget.class.getClassLoader(), new Class<?>[]{Widget.class}, (p, m, args) ->
		{
			switch (m.getName())
			{
				case "getParent":
					return dialsMountedIn == null ? null : dialsMountedIn.proxy;
				case "getBounds":
					if (dialsDrawn != null)
					{
						return new Rectangle(dialsDrawn);
					}
					return dialsMountedIn == null ? null : new Rectangle(dialsMountedIn.bounds);
				case "getRelativeX":
				case "getRelativeY":
					return 0;
				case "getWidth":
					return dialsMountedIn == null ? 0 : dialsMountedIn.w;
				case "getHeight":
					return dialsMountedIn == null ? 0 : dialsMountedIn.h;
				case "isHidden":
					return false;
				case "equals":
					return p == args[0];
				case "hashCode":
					return System.identityHashCode(p);
				default:
					return null;
			}
		});
		client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class}, (p, m, args) ->
		{
			switch (m.getName())
			{
				case "getWidget":
					if (args.length == 1 && (int) args[0] == d.id)
					{
						return dialsMountedIn == null ? null : dials;
					}
					Fake f = args.length == 1 ? live.get((int) args[0]) : null;
					return f == null ? null : f.proxy;
				case "getCanvasWidth":
					return canvas.width;
				case "getCanvasHeight":
					return canvas.height;
				case "isResized":
					return true;
				case "equals":
					return p == args[0];
				case "hashCode":
					return System.identityHashCode(p);
				default:
					return null;
			}
		});
		modal = new ModalSlot();
	}

	@Test
	public void movesAPristineSlotAndPutsItBack()
	{
		modal.update(client, true, 2000, 1400);
		assertTrue(modal.holds());
		assertArrayEquals(new int[]{WidgetPositionMode.ABSOLUTE_RIGHT, WidgetPositionMode.ABSOLUTE_BOTTOM, 6, 6}, slot.position());
		assertEquals(new Rectangle(1344 - 512, 729 - 334, 512, 334), slot.bounds);
		assertEquals(512, slot.w);
		assertEquals(334, slot.h);
		assertEquals(1, slot.revalidations);

		// the per-tick recompute writes nothing while nothing changed
		modal.update(client, true, 2000, 1400);
		assertEquals(1, slot.revalidations);

		modal.restore(client);
		assertFalse(modal.holds());
		assertArrayEquals(PRISTINE, slot.position());
		assertEquals(new Rectangle(419, 200, 512, 334), slot.bounds);
		assertEquals(2, slot.revalidations);
	}

	@Test
	public void movesAtWidgetLoadedBeforeTheDialsAreDrawn()
	{
		// a reopened 398 is a new widget: laid out but not drawn, so its bounds are at -1,-1
		dialsDrawn = new Rectangle(-1, -1, 512, 334);
		modal.update(client, true, 2000, 1400);
		assertTrue(modal.holds());
		assertArrayEquals(new int[]{WidgetPositionMode.ABSOLUTE_RIGHT, WidgetPositionMode.ABSOLUTE_BOTTOM, 6, 6}, slot.position());
	}

	@Test
	public void aTravelLogNotDrawnYetIsNoObstacle()
	{
		// loaded in the same batch as the dials: laid out, but its drawn bounds are still -1,-1
		Fake log = new Fake(InterfaceID.FairyringsLog.UNIVERSE);
		log.bounds = new Rectangle(-1, -1, 200, 300);
		proxy(log);
		live.put(log.id, log);
		modal.update(client, true, 1100, 720);
		assertArrayEquals(ModalSlot.target(HUD, MapLayout.slotCorner(CANVAS, HUD, 512, 334, Collections.emptyList(), 1100, 720)), slot.position());
		// once drawn it is one
		log.bounds = new Rectangle(6, 6, 200, 300);
		modal.update(client, true, 1100, 720);
		assertArrayEquals(ModalSlot.target(HUD, MapLayout.slotCorner(CANVAS, HUD, 512, 334,
			Collections.singletonList(new Rectangle(6, 6, 200, 300)), 1100, 720)), slot.position());
	}

	@Test
	public void keepsTheSlotWhileTheWindowShrinksUntilTheHudIsLaidOutAgain()
	{
		modal.update(client, true, 2000, 1400);
		int[] moved = slot.position();
		int revalidated = slot.revalidations;
		// CanvasSizeChanged / a tick in between: new canvas, old HUD bounds
		canvas = new Rectangle(0, 0, 1000, 600);
		modal.update(client, true, 2000, 1400);
		assertTrue(modal.holds());
		assertArrayEquals(moved, slot.position());
		assertEquals(revalidated, slot.revalidations);
		// still restored when no longer wanted
		modal.update(client, false, 2000, 1400);
		assertFalse(modal.holds());
		assertArrayEquals(PRISTINE, slot.position());
	}

	@Test
	public void followsTheFreeSpaceAndRestoresWhenNoLongerWanted()
	{
		modal.update(client, true, 2000, 1400);
		// a smaller cap: the corner moves to centre the map
		modal.update(client, true, 1100, 720);
		assertArrayEquals(ModalSlot.target(HUD, MapLayout.slotCorner(CANVAS, HUD, 512, 334, Collections.emptyList(), 1100, 720)), slot.position());
		// dial mode, or the option turned off
		modal.update(client, false, 1100, 720);
		assertFalse(modal.holds());
		assertArrayEquals(PRISTINE, slot.position());
	}

	@Test
	public void leavesASlotSomeoneElseChangedAlone()
	{
		// another layout plugin anchored it to the top first: not pristine, never touched
		slot.yMode = WidgetPositionMode.ABSOLUTE_TOP;
		slot.y = 10;
		slot.layOut();
		modal.update(client, true, 2000, 1400);
		assertFalse(modal.holds());
		assertEquals(WidgetPositionMode.ABSOLUTE_CENTER, slot.xMode);
		assertEquals(0, slot.revalidations);
	}

	@Test
	public void doesNotRestoreOverAChangeMadeAfterOurs()
	{
		modal.update(client, true, 2000, 1400);
		slot.yMode = WidgetPositionMode.ABSOLUTE_TOP;
		slot.y = 10;
		int[] theirs = slot.position();
		modal.restore(client);
		assertFalse(modal.holds());
		assertArrayEquals(theirs, slot.position());
		// and the per-tick update lets it go rather than moving it again
		modal.update(client, true, 2000, 1400);
		assertFalse(modal.holds());
		assertArrayEquals(theirs, slot.position());
	}

	@Test
	public void restoresWhenTheSlotIsResizedUnderUs()
	{
		modal.update(client, true, 2000, 1400);
		slot.hMode = WidgetSizeMode.MINUS;
		slot.h = 0;
		modal.update(client, true, 2000, 1400);
		assertFalse(modal.holds());
		assertArrayEquals(PRISTINE, slot.position());
	}

	@Test
	public void toplevelSwitchRestoresTheOldSlotAndMovesTheNewOne()
	{
		modal.update(client, true, 2000, 1400);
		Fake old = slot;
		int revalidated = old.revalidations;
		// classic to modern: the old toplevel is unloaded and the dials are mounted in the new slot
		live.remove(CLASSIC);
		Fake modern = slot(MODERN);
		dialsMountedIn = modern;
		modal.update(client, true, 2000, 1400);
		assertArrayEquals(PRISTINE, old.position());
		// the orphaned slot is written back but not laid out again
		assertEquals(revalidated, old.revalidations);
		assertTrue(modal.holds());
		assertArrayEquals(new int[]{WidgetPositionMode.ABSOLUTE_RIGHT, WidgetPositionMode.ABSOLUTE_BOTTOM, 6, 6}, modern.position());
	}

	@Test
	public void fixedModeSlotIsNeverMoved()
	{
		modal.update(client, true, 2000, 1400);
		Fake fixed = slot(InterfaceID.Toplevel.MAINMODAL);
		dialsMountedIn = fixed;
		modal.update(client, true, 2000, 1400);
		assertFalse(modal.holds());
		assertArrayEquals(PRISTINE, slot.position());
		assertArrayEquals(PRISTINE, fixed.position());
		assertEquals(0, fixed.revalidations);
	}

	@Test
	public void closedDialsPutTheSlotBack()
	{
		modal.update(client, true, 2000, 1400);
		// 398 is gone (a missed close) and another interface loads
		dialsMountedIn = null;
		modal.update(client, true, 2000, 1400);
		assertFalse(modal.holds());
		assertArrayEquals(PRISTINE, slot.position());
	}

	@Test
	public void logoutRestoresTheHeldObject()
	{
		modal.update(client, true, 2000, 1400);
		// at the login screen the toplevel is no longer loaded: write back to the held object only
		live.clear();
		dialsMountedIn = null;
		int revalidated = slot.revalidations;
		modal.restore(client);
		assertArrayEquals(PRISTINE, slot.position());
		assertEquals(revalidated, slot.revalidations);
		assertFalse(modal.holds());
	}
}
