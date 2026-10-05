/*
 * Copyright (c) 2026, Dewdul
 * All rights reserved.
 * Licensed under the BSD 2-Clause License. See LICENSE.
 */
package com.spirittreeatlas;

import com.google.gson.Gson;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Area;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Renders the map offline, without a game client, from the real bundled resources into
 * build/preview/*.png so the drawing can be checked by eye (DESIGN 5). Run with
 * {@code ./gradlew preview}, through MapPreviewTest with STA_PREVIEW=1, or as a main from an IDE.
 * Where the overlay leaves holes, the game's own parts are drawn as they look in game: the
 * Travel row on its backdrop (modern: orange text on translucent black; classic: dark brown text
 * on parchment) and the red close button, so the corner can be judged. Also writes the hub's
 * icon.png to the repo root when run from it.
 */
public class MapPreview
{
	private static final String BASE = SpiritTreeAtlasPlugin.RESOURCES;
	/** Trees the preview menu lists in grey, and one it does not list at all. */
	private static final List<String> GREY = Arrays.asList("PRIFDDINAS", "ETCETERIA", "BRIMHAVEN", "POISON_WASTE", "LAGUNA_AURORAE");
	private static final String ABSENT = "FARMING_GUILD";
	private static final Color BACKGROUND = new Color(0x2a2620);
	/** The game world behind the menu: shows only through the translucent modern row. */
	private static final Color WORLD = new Color(0x4d5a37);

	/** One preview: the view, the selection, hover and where the player stands, the menu style and the card's detail. */
	private static final class Shot
	{
		final String name;
		final MapView view;
		String selected;
		String hovered;
		String here;
		TreeMenu.Style style = TreeMenu.Style.MODERN;
		boolean fullDetails;
		/** The quick-select panel open (as on a first open: on maps 700 px and wider), else its tab. */
		boolean panelOpen;
		/** A tree whose quick-select row the pointer is over, or null. */
		String hoverRow;

		Shot(String name, MapView view)
		{
			this.name = name;
			this.view = view;
			panelOpen = view.getW() >= SpiritTreeAtlasPlugin.NARROW_MAP;
		}
	}

	/** The plugin's chrome insets for a map: the quick-select panel open on wide maps, its tab on narrow ones. */
	private static java.awt.Insets insets(TreeRepository repo, AtlasPainter painter, Rectangle map, boolean open)
	{
		int panel = open ? new ChromePainter(painter.ink()).panelWidth(repo.menuOrder(), repo.getHere(), repo.getLast(), map.width) : ChromePainter.TAB_W;
		return SpiritTreeAtlasPlugin.chromeInsets(panel);
	}

	public static void main(String[] args) throws IOException
	{
		File out = new File(args.length > 0 ? args[0] : "build/preview");
		if (!out.isDirectory() && !out.mkdirs())
		{
			throw new IOException("cannot create " + out);
		}
		TreeRepository repo = TreeRepository.load(new Gson(), BASE);
		menuState(repo);
		AtlasPainter painter = new AtlasPainter();
		Layer surface = repo.surface();
		Rectangle big = new Rectangle(30, 30, 1100, 720);
		Rectangle fixed = new Rectangle(30, 30, 512, 334);
		java.awt.Insets in = insets(repo, painter, big, true);
		java.awt.Insets fixedIn = insets(repo, painter, fixed, false);

		Shot fit = new Shot("1-fit", SpiritTreeAtlasPlugin.fitTrees(MapView.of(surface, big), repo.surfaceMarkers(), in));
		fit.selected = "GRAND_EXCHANGE";
		render(repo, painter, out, fit);

		Tree ge = repo.tree("GRAND_EXCHANGE");
		Shot ge4 = new Shot("2-grand-exchange-4ppt", MapView.of(surface, big).focusOn(ge.getX() + 0.5, ge.getY() + 0.5, 4, in));
		ge4.selected = "GRAND_EXCHANGE";
		ge4.hovered = "GRAND_EXCHANGE";
		render(repo, painter, out, ge4);

		// standing at the Tree Gnome Village tree: the pin, and "You are here" in the Travel cell
		Tree village = repo.tree("TREE_GNOME_VILLAGE");
		Shot close = new Shot("3-16ppt", MapView.of(surface, big).focusOn(village.getX() + 0.5, village.getY() + 0.5, 16, in));
		close.selected = "TREE_GNOME_VILLAGE";
		close.here = "TREE_GNOME_VILLAGE";
		render(repo, painter, out, close);

		// nothing selected: the hint card and "Pick a tree on the map"
		Shot city = new Shot("4-prifddinas", SpiritTreeAtlasPlugin.fitLayer(repo.layer(Layer.PRIFDDINAS), big, in));
		render(repo, painter, out, city);

		Shot modern = new Shot("5-fixed-modern", SpiritTreeAtlasPlugin.fitTrees(MapView.of(surface, fixed), repo.surfaceMarkers(), fixedIn));
		modern.selected = "GRAND_EXCHANGE";
		render(repo, painter, out, modern);

		Shot classic = new Shot("6-fixed-classic", SpiritTreeAtlasPlugin.fitTrees(MapView.of(surface, fixed), repo.surfaceMarkers(), fixedIn));
		classic.selected = "POISON_WASTE";
		classic.style = TreeMenu.Style.CLASSIC;
		render(repo, painter, out, classic);

		Tree waste = repo.tree("POISON_WASTE");
		Shot locked = new Shot("7-locked-card", MapView.of(surface, big).focusOn(waste.getX() + 0.5, waste.getY() + 0.5, 2, in));
		locked.selected = "POISON_WASTE";
		locked.fullDetails = true;
		render(repo, painter, out, locked);

		treeChecks(repo, painter, out);

		// "Use free space" on a 2000x1082 resizable canvas: all of the HUD area but its 6 px inset;
		// the Farming Guild is not in this menu's list
		Rectangle free = new Rectangle(30, 30, 1738, 905);
		Shot space = new Shot("9-free-space", SpiritTreeAtlasPlugin.fitTrees(MapView.of(surface, free), repo.surfaceMarkers(),
			insets(repo, painter, free, true)));
		space.selected = ABSENT;
		space.hovered = "LAGUNA_AURORAE";
		render(repo, painter, out, space);

		listAndNotice(painter, out);

		Shot classicTravel = new Shot("11-fixed-classic-travel",
			SpiritTreeAtlasPlugin.fitTrees(MapView.of(surface, fixed), repo.surfaceMarkers(), fixedIn));
		classicTravel.selected = "HOSIDIUS";
		classicTravel.style = TreeMenu.Style.CLASSIC;
		render(repo, painter, out, classicTravel);

		Tree khazard = repo.tree("BATTLEFIELD_OF_KHAZARD");
		Shot full = new Shot("12-full-card", MapView.of(surface, fixed).focusOn(khazard.getX() + 0.5, khazard.getY() + 0.5, 2, fixedIn));
		full.selected = "BATTLEFIELD_OF_KHAZARD";
		full.fullDetails = true;
		render(repo, painter, out, full);

		markerStates(repo, painter, out);
		icon(repo, out);

		// fixed mode with the quick-select panel opened, the pointer over Hosidius's row: its card
		// and its marker's hover ring; Grand Exchange selected (the last trip), standing at the Gnome Stronghold
		Shot quick = new Shot("14-fixed-quick-select", SpiritTreeAtlasPlugin.fitTrees(MapView.of(surface, fixed), repo.surfaceMarkers(),
			insets(repo, painter, fixed, true)));
		quick.panelOpen = true;
		quick.selected = "GRAND_EXCHANGE";
		quick.here = "GNOME_STRONGHOLD";
		quick.hoverRow = "HOSIDIUS";
		render(repo, painter, out, quick);

		// the default open (around you, DESIGN 4.7): standing at the Grand Exchange tree, nothing
		// selected, the quick-select panel open; the "You" tag widens the panel
		repo.locate((int) ge.getX(), (int) ge.getY(), ge.getPlane());
		Shot around = new Shot("15-around-you", SpiritTreeAtlasPlugin.initialView(SpiritTreeAtlasConfig.OpenAt.AROUND_YOU, repo, ge,
			ge.getX(), ge.getY(), false, null, big, insets(repo, painter, big, true)));
		around.here = "GRAND_EXCHANGE";
		render(repo, painter, out, around);
		repo.locate(0, 0, 0);
	}

	/**
	 * The menu as a mid-game account might see it: most trees listed, five in grey, the Farming
	 * Guild missing; the house in Rimmington; the last trip to the Grand Exchange.
	 */
	private static void menuState(TreeRepository repo)
	{
		repo.placeHouse(1);
		repo.setLast(4);
		repo.locate(0, 0, 0);
		List<TreeMenu.Row> rows = new ArrayList<>();
		for (Tree t : repo.getTrees())
		{
			int i = t.getPreviousValue() - 1;
			if (!ABSENT.equals(t.getId()))
			{
				rows.add(new TreeMenu.Row(i, String.valueOf(FakeMenu.KEYS.charAt(i)), t.getMenuLabel(), GREY.contains(t.getId()), t.getId()));
			}
		}
		repo.applyRows(rows);
	}

	private static Scene scene(TreeRepository repo, MapView v, String selected, String hovered)
	{
		Scene s = new Scene();
		s.fromRepository(repo);
		s.view = v;
		s.selected = selected;
		s.hovered = repo.tree(hovered);
		s.now = 400;
		return s;
	}

	private static void render(TreeRepository repo, AtlasPainter painter, File out, Shot shot) throws IOException
	{
		MapView v = shot.view;
		TileStore tiles = new TileStore(repo.getIndex(), repo.getLayers(), BASE, Runnable::run);
		ChromePainter chrome = new ChromePainter(painter.ink());
		Rectangle rect = v.rect();
		// the map grows from the slot's bottom-right corner, where the Travel row and close button go
		Rectangle slot = new Rectangle(rect.x + rect.width - 512, rect.y + rect.height - 334, 512, 334);
		boolean modern = shot.style == TreeMenu.Style.MODERN;
		TreeMenu.Geometry g = modern ? TreeMenu.modern(512, 334, 8, 52, 322, 160, 320, 0, 0) : TreeMenu.classic(512, 334, 386, 232, 0);
		Rectangle cell = new Rectangle(slot.x + g.getCell().x, slot.y + g.getCell().y, g.getCell().width, g.getCell().height);
		Point c = modern ? new Point(g.getRoot().x + 338 - 44, g.getRoot().y + 17) : g.getClose();
		Rectangle close = new Rectangle(slot.x + c.x, slot.y + c.y, 26, 23);

		Scene s = scene(repo, v, shot.selected, shot.hovered);
		s.fullDetails = shot.fullDetails;
		s.panelOpen = shot.panelOpen;
		if (shot.here != null)
		{
			s.here = shot.here;
		}
		TreeMenu.Row row = repo.row(shot.selected);
		s.rowShown = Scene.rowShown(shot.selected, row, s.here);
		s.rowCell = cell;
		s.closeRect = close;
		s.holes.add(close);
		if (s.rowShown)
		{
			s.holes.add(cell);
		}
		s.standIn = s.rowShown ? null : Scene.standInText(repo.tree(shot.selected), row, s.here);

		BufferedImage canvas = new BufferedImage(rect.x * 2 + rect.width, rect.y * 2 + rect.height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g2 = canvas.createGraphics();
		g2.setColor(BACKGROUND);
		g2.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
		g2.setColor(WORLD);
		g2.fill(slot);
		paintGame(g2, painter.ink(), close, s.rowShown ? cell : null, row, modern);

		long t0 = System.nanoTime();
		BufferedImage base = new BufferedImage(rect.width, rect.height, BufferedImage.TYPE_INT_RGB);
		Layer layer = repo.layer(v.getLayer());
		MapRenderer.render(base, v, tiles, layer.getBackgroundColor());
		long t1 = System.nanoTime();
		// a first frame off screen, as the overlay's previous frame: the card it placed keeps the
		// map's labels from under it
		Graphics2D scratch = new BufferedImage(canvas.getWidth(), canvas.getHeight(), BufferedImage.TYPE_INT_ARGB).createGraphics();
		chrome.layout(s);
		painter.paintMap(scratch, s);
		chrome.paint(scratch, s);
		scratch.dispose();
		for (Hit h : s.hits)
		{
			// the pointer over a quick-select row, as the overlay sees it: the row lit, the tree's card and hover ring
			if (h.getKind() == Hit.Kind.ROW && h.getTree().getId().equals(shot.hoverRow))
			{
				s.mouse = new Point((int) h.getArea().getCenterX(), (int) h.getArea().getCenterY());
				s.hovered = h.getTree();
			}
		}
		s.hits.clear();
		chrome.layout(s);
		chrome.paintShadow(g2, s);
		Shape clip = g2.getClip();
		Area a = new Area(rect);
		for (Rectangle h : s.holes)
		{
			a.subtract(new Area(h));
		}
		g2.clip(a);
		g2.drawImage(base, rect.x, rect.y, null);
		painter.paintMap(g2, s);
		chrome.paint(g2, s);
		g2.setClip(clip);
		chrome.paintHoles(g2, s);
		long t2 = System.nanoTime();
		g2.dispose();
		ImageIO.write(canvas, "png", new File(out, shot.name + ".png"));
		System.out.printf("%-24s ppt=%6.3f z=%d  base=%6.1f ms  paint=%5.1f ms  hits=%d%n",
			shot.name, v.getPpt(), tiles.levelFor(v.getPpt()), (t1 - t0) / 1e6, (t2 - t1) / 1e6, s.hits.size());
		tiles.close();
	}

	/**
	 * The game's own parts that show through the holes, as the game draws them: the close button
	 * and, when the Travel row is shown, the real row over RowBackdrop's fill (DESIGN 2.2, 2.3, 4.3).
	 */
	static void paintGame(Graphics2D g, Ink ink, Rectangle close, Rectangle cell, TreeMenu.Row row, boolean modern)
	{
		closeButton(g, close, modern);
		if (cell == null || row == null)
		{
			return;
		}
		// RowBackdrop, then (modern) the row's own black rectangle at transparency 200 (alpha 55)
		g.setColor(modern ? new Color(0, 0, 0, 200) : new Color(0xD9C9A0));
		g.fill(cell);
		g.setColor(modern ? new Color(0x3E3529) : new Color(0x5A4A2A));
		g.drawRect(cell.x, cell.y, cell.width - 1, cell.height - 1);
		if (modern)
		{
			g.setColor(new Color(0, 0, 0, 55));
			g.fill(cell);
		}
		// p12_full, centred both ways: "<col=ffffff>K</col>: " + option (classic: key in 735a28)
		String key = row.getKey();
		String rest = ": " + row.getLabel();
		int w = ink.width(ink.regular, key) + ink.width(ink.regular, rest);
		int x = cell.x + (cell.width - w) / 2;
		int y = cell.y + (cell.height - ink.height(ink.regular)) / 2;
		Ink.Style st = modern ? Ink.Style.SHADOW : Ink.Style.PLAIN;
		ink.text(g, key, ink.regular, modern ? Color.WHITE : new Color(0x735a28), x, y, st);
		ink.text(g, rest, ink.regular, modern ? new Color(0xff981f) : new Color(0x322805), x + ink.width(ink.regular, key), y, st);
	}

	/** The game's close button (sprites 535 / 537): a small bevelled red-brown square with an X. */
	private static void closeButton(Graphics2D g, Rectangle c, boolean modern)
	{
		g.setColor(new Color(0x14100b));
		g.fillRect(c.x, c.y, c.width, c.height);
		g.setColor(modern ? new Color(0x7a2414) : new Color(0x6e3a1c));
		g.fillRect(c.x + 1, c.y + 1, c.width - 2, c.height - 2);
		g.setColor(modern ? new Color(0xa9432a) : new Color(0x96562c));
		g.fillRect(c.x + 1, c.y + 1, c.width - 2, 1);
		g.fillRect(c.x + 1, c.y + 1, 1, c.height - 2);
		g.setColor(modern ? new Color(0x461008) : new Color(0x3f200e));
		g.fillRect(c.x + 1, c.y + c.height - 2, c.width - 2, 1);
		g.fillRect(c.x + c.width - 2, c.y + 1, 1, c.height - 2);
		int cx = c.x + c.width / 2;
		int cy = c.y + c.height / 2;
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		for (int pass = 0; pass < 2; pass++)
		{
			int o = pass == 0 ? 1 : 0;
			g.setColor(pass == 0 ? new Color(0x1a0a05) : new Color(0xE8D2A8));
			g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g.drawLine(cx - 5 + o, cy - 5 + o, cx + 5 + o, cy + 5 + o);
			g.drawLine(cx + 5 + o, cy - 5 + o, cx - 5 + o, cy + 5 + o);
		}
		g.setStroke(new BasicStroke(1f));
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
	}

	static final int CHECK_SIZE = 240;
	static final double CHECK_PPT = 8;

	/** Writes 8-checks.png: an 8 ppt close-up of every tree (the house at its Rimmington portal), tree at the centre. */
	private static void treeChecks(TreeRepository repo, AtlasPainter painter, File out) throws IOException
	{
		TileStore tiles = new TileStore(repo.getIndex(), repo.getLayers(), BASE, Runnable::run);
		List<Tree> trees = new ArrayList<>();
		for (Tree t : repo.getTrees())
		{
			if (t.isMapped())
			{
				trees.add(t);
			}
		}
		int cols = 5;
		int rows = (trees.size() + cols - 1) / cols;
		BufferedImage sheet = new BufferedImage(cols * (CHECK_SIZE + 8) + 8, rows * (CHECK_SIZE + 8) + 8, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = sheet.createGraphics();
		g.setColor(BACKGROUND);
		g.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
		for (int i = 0; i < trees.size(); i++)
		{
			Tree t = trees.get(i);
			Layer layer = repo.layer(t.getLayer());
			Rectangle rect = new Rectangle(8 + (i % cols) * (CHECK_SIZE + 8), 8 + (i / cols) * (CHECK_SIZE + 8), CHECK_SIZE, CHECK_SIZE);
			MapView v = MapView.of(layer, rect).centerOn(t.getX() + 0.5, t.getY() + 0.5, CHECK_PPT);
			BufferedImage base = new BufferedImage(rect.width, rect.height, BufferedImage.TYPE_INT_RGB);
			MapRenderer.render(base, v, tiles, layer.getBackgroundColor());
			Scene s = scene(repo, v, null, null);
			s.placeLabels = false;
			Shape clip = g.getClip();
			g.clip(rect);
			g.drawImage(base, rect.x, rect.y, null);
			painter.paintMap(g, s);
			g.setClip(clip);
		}
		g.dispose();
		ImageIO.write(sheet, "png", new File(out, "8-checks.png"));
		tiles.close();
	}

	/** Writes 10-list-and-notice.png: List mode's Map button over a plain menu, and the step-aside notice. */
	private static void listAndNotice(AtlasPainter painter, File out) throws IOException
	{
		ChromePainter chrome = new ChromePainter(painter.ink());
		BufferedImage img = new BufferedImage(1160, 420, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.setColor(BACKGROUND);
		g.fillRect(0, 0, img.getWidth(), img.getHeight());
		Rectangle canvas = new Rectangle(0, 0, img.getWidth(), img.getHeight());
		Rectangle[] slots = {new Rectangle(30, 60, 512, 334), new Rectangle(610, 60, 512, 334)};
		for (Rectangle slot : slots)
		{
			g.setColor(WORLD);
			g.fill(slot);
		}
		// the modern menu's UNIVERSE, centred in its slot, with the Map button above its top-left corner
		Rectangle universe = new Rectangle(slots[0].x + 87, slots[0].y + 58, 338, 218);
		g.setColor(new Color(0x1c1a17));
		g.fill(universe);
		chrome.paintMapButton(g, chrome.mapButton(universe.getLocation()), false);
		chrome.paintNotice(g, slots[1], canvas, SpiritTreeAtlasPlugin.TELEPORT_MAPS_NOTICE);
		g.dispose();
		ImageIO.write(img, "png", new File(out, "10-list-and-notice.png"));
	}

	/** One row of the marker sheet: a name and how the scene shows the tree. */
	private static final class State
	{
		final String name;
		final Tree.Status status;
		final boolean selected;
		final boolean hovered;
		final boolean here;
		final boolean last;
		final boolean key;
		final boolean dim;

		State(String name, Tree.Status status, String flags)
		{
			this.name = name;
			this.status = status;
			this.selected = flags.contains("s");
			this.hovered = flags.contains("h");
			this.here = flags.contains("y");
			this.last = flags.contains("l");
			this.key = flags.contains("k");
			this.dim = flags.contains("d");
		}
	}

	/**
	 * Writes 13-marker-states.png: every marker state of DESIGN 4.7 at 1, 4, 8 and 16 ppt over the
	 * real map, then the Travel cell's stand-in for every case in both menu styles at their real
	 * cell sizes, next to the shown row.
	 */
	private static void markerStates(TreeRepository repo, AtlasPainter painter, File out) throws IOException
	{
		State[] states = {
			new State("Available", Tree.Status.AVAILABLE, ""),
			new State("Locked", Tree.Status.LOCKED, ""),
			new State("Locked, dimLocked", Tree.Status.LOCKED, "d"),
			new State("Not in the list", Tree.Status.ABSENT, ""),
			new State("Hover", Tree.Status.AVAILABLE, "h"),
			new State("Selected", Tree.Status.AVAILABLE, "s"),
			new State("You are here", Tree.Status.AVAILABLE, "y"),
			new State("Last trip", Tree.Status.AVAILABLE, "l"),
			new State("Key", Tree.Status.AVAILABLE, "k"),
			new State("Locked, selected, key", Tree.Status.LOCKED, "sk"),
			new State("All of them", Tree.Status.AVAILABLE, "hsylk"),
		};
		double[] ppts = {1, 4, 8, 16};
		int cw = 170;
		int ch = 64;
		int lw = 140;
		Ink ink = painter.ink();
		int stripH = 2 * STAND_IN_ROW + 30;
		BufferedImage img = new BufferedImage(Math.max(lw + ppts.length * (cw + 6) + 6, 148 + 5 * STAND_IN_STEP), 28 + states.length * (ch + 6) + 6 + stripH, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.setColor(BACKGROUND);
		g.fillRect(0, 0, img.getWidth(), img.getHeight());
		TileStore tiles = new TileStore(repo.getIndex(), repo.getLayers(), BASE, Runnable::run);
		Tree t = repo.tree("FELDIP_HILLS");
		Layer layer = repo.layer(t.getLayer());
		for (int c = 0; c < ppts.length; c++)
		{
			ink.text(g, ppts[c] + " ppt", ink.small, ChromePainter.GREY, lw + c * (cw + 6) + 4, 8, Ink.Style.SHADOW);
		}
		for (int r = 0; r < states.length; r++)
		{
			State st = states[r];
			int y = 28 + r * (ch + 6);
			ink.text(g, st.name, ink.small, ChromePainter.CREAM, 8, y + ch / 2 - 6, Ink.Style.SHADOW);
			for (int c = 0; c < ppts.length; c++)
			{
				Rectangle rect = new Rectangle(lw + c * (cw + 6), y, cw, ch);
				MapView v = MapView.of(layer, rect).centerOn(t.getX() + 0.5 + 18 / ppts[c], t.getY() + 0.5 - 4 / ppts[c], ppts[c]);
				BufferedImage base = new BufferedImage(rect.width, rect.height, BufferedImage.TYPE_INT_RGB);
				MapRenderer.render(base, v, tiles, layer.getBackgroundColor());
				Scene s = new Scene();
				s.repo = repo;
				s.view = v;
				s.trees = Collections.singletonList(t);
				s.states.put(t.getId(), st.status);
				if (st.key)
				{
					s.keys.put(t.getId(), "5");
				}
				s.selected = st.selected ? t.getId() : null;
				s.hovered = st.hovered ? t : null;
				s.here = st.here ? t.getId() : null;
				s.last = st.last ? t.getId() : null;
				s.dimLocked = st.dim;
				s.placeLabels = false;
				s.now = 400;
				Shape clip = g.getClip();
				g.clip(rect);
				g.drawImage(base, rect.x, rect.y, null);
				painter.paintMap(g, s);
				g.setClip(clip);
			}
		}
		tiles.close();
		standIns(repo, painter, g, 28 + states.length * (ch + 6) + 10);
		g.dispose();
		ImageIO.write(img, "png", new File(out, "13-marker-states.png"));
	}

	/** The stand-in strip: one cell per case across, one menu style per row down. */
	private static final int STAND_IN_STEP = TreeMenu.TRAVEL_W + 26;
	private static final int STAND_IN_ROW = TreeMenu.TRAVEL_H + 40;

	/** The stand-in for each case, and the shown row, in both menu styles at their real cell sizes. */
	private static void standIns(TreeRepository repo, AtlasPainter painter, Graphics2D g, int top)
	{
		ChromePainter chrome = new ChromePainter(painter.ink());
		Ink ink = painter.ink();
		Tree house = repo.tree("YOUR_HOUSE");
		Tree village = repo.tree("TREE_GNOME_VILLAGE");
		Tree hosidius = repo.tree("HOSIDIUS");
		TreeMenu.Row grey = new TreeMenu.Row(11, "C", house.getMenuLabel(), true, house.getId());
		TreeMenu.Row usable = repo.row("HOSIDIUS");
		// selected tree, its row, where the player stands
		Object[][] cases = {
			{null, null, null},
			{house, grey, null},
			{village, repo.row(village.getId()), village.getId()},
			{repo.tree(ABSENT), null, null},
			{hosidius, usable, null},
		};
		for (int style = 0; style < 2; style++)
		{
			boolean modern = style == 0;
			// the cells as Map mode makes them (DESIGN 4.3)
			TreeMenu.Geometry geo = modern ? TreeMenu.modern(512, 334, 8, 52, 322, 160, 320, 0, 0) : TreeMenu.classic(512, 334, 386, 232, 0);
			int y = top + 30 + style * STAND_IN_ROW;
			String size = geo.getCell().width + "x" + geo.getCell().height;
			ink.text(g, (modern ? "Modern " : "Classic ") + size, ink.small, ChromePainter.CREAM, 8, y + geo.getCell().height / 2 - 6,
				Ink.Style.SHADOW);
			for (int i = 0; i < cases.length; i++)
			{
				Tree sel = (Tree) cases[i][0];
				TreeMenu.Row row = (TreeMenu.Row) cases[i][1];
				String here = (String) cases[i][2];
				Rectangle cell = new Rectangle(148 + i * STAND_IN_STEP, y, geo.getCell().width, geo.getCell().height);
				Rectangle map = new Rectangle(cell.x - 8, cell.y - 26, cell.width + 16, cell.height + 34);
				Scene s = new Scene();
				s.fromRepository(repo);
				s.view = MapView.of(repo.surface(), map);
				s.selected = sel == null ? null : sel.getId();
				s.here = here;
				s.now = 400;
				s.rowCell = cell;
				s.rowShown = Scene.rowShown(s.selected, row, here);
				s.standIn = s.rowShown ? null : Scene.standInText(sel, row, here);
				if (s.rowShown)
				{
					s.holes.add(cell);
				}
				chrome.layout(s);
				g.setColor(new Color(0x3a5a2a));
				g.fill(new Rectangle(map.x, map.y + 4, map.width, map.height - 4));
				if (s.rowShown)
				{
					g.setColor(WORLD);
					g.fill(cell);
					paintGame(g, ink, new Rectangle(-100, -100, 1, 1), cell, row, modern);
				}
				chrome.paintStandIn(g, s);
				chrome.paintHoles(g, s);
			}
		}
	}

	/**
	 * The hub's 48x72 icon.png: a crop of the map with a selected spirit tree marker, drawn large
	 * enough to read at that size. Written to build/preview and, when run from the repo root
	 * (as {@code gradlew preview} does), to the root's icon.png.
	 */
	private static void icon(TreeRepository repo, File out) throws IOException
	{
		TileStore tiles = new TileStore(repo.getIndex(), repo.getLayers(), BASE, Runnable::run);
		Tree t = repo.tree("GRAND_EXCHANGE");
		Layer layer = repo.layer(t.getLayer());
		MapView v = MapView.of(layer, new Rectangle(0, 0, 48, 72)).centerOn(t.getX() + 0.5, t.getY() + 0.5 - 3, 2);
		BufferedImage img = new BufferedImage(48, 72, BufferedImage.TYPE_INT_RGB);
		MapRenderer.render(img, v, tiles, layer.getBackgroundColor());
		Graphics2D g = img.createGraphics();
		Scene s = scene(repo, v, t.getId(), null);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
		// darken the map a little so the marker stands out
		g.setColor(new Color(0, 0, 0, 60));
		g.fillRect(0, 0, 48, 72);
		g.translate(24, 36);
		g.scale(2.3, 2.3);
		g.setColor(AtlasPainter.withAlpha(s.selectedColor, 70));
		g.fill(AtlasPainter.circle(0, 0, 9.6));
		AtlasPainter.drawMarker(g, 0, 0, 7.5, AtlasPainter.AVAILABLE | AtlasPainter.SELECTED, s);
		g.dispose();
		ImageIO.write(img, "png", new File(out, "icon.png"));
		if (new File("runelite-plugin.properties").isFile())
		{
			ImageIO.write(img, "png", new File("icon.png"));
		}
		BufferedImage x4 = new BufferedImage(48 * 4, 72 * 4, BufferedImage.TYPE_INT_RGB);
		Graphics2D g4 = x4.createGraphics();
		g4.drawImage(img, 0, 0, 48 * 4, 72 * 4, null);
		g4.dispose();
		ImageIO.write(x4, "png", new File(out, "icon_x4.png"));
		tiles.close();
	}

	static List<String> names()
	{
		return Arrays.asList("1-fit", "2-grand-exchange-4ppt", "3-16ppt", "4-prifddinas", "5-fixed-modern", "6-fixed-classic",
			"7-locked-card", "8-checks", "9-free-space", "10-list-and-notice", "11-fixed-classic-travel", "12-full-card",
			"13-marker-states", "14-fixed-quick-select", "15-around-you", "icon");
	}
}
