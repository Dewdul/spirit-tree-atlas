/*
 * Copyright (c) 2026, Dewdul
 * All rights reserved.
 * Licensed under the BSD 2-Clause License. See LICENSE.
 */
package com.spirittreeatlas;

import com.google.gson.Gson;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.geom.Area;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Renders the map offline, without a game client, from the real bundled resources into
 * build/preview/*.png so the drawing can be checked by eye (DESIGN 5). Run with
 * {@code ./gradlew preview}, through MapPreviewTest with STA_PREVIEW=1, or as a main from an IDE.
 * The game's own widgets (the slot, the real Travel row, the close button) are drawn as plain
 * stand-ins where the overlay would leave holes for them.
 */
public class MapPreview
{
	private static final String BASE = SpiritTreeAtlasPlugin.RESOURCES;
	/** Trees the preview menu lists in grey, and one it does not list at all. */
	private static final List<String> GREY = Arrays.asList("PRIFDDINAS", "ETCETERIA", "BRIMHAVEN", "POISON_WASTE", "LAGUNA_AURORAE");
	private static final String ABSENT = "FARMING_GUILD";

	/** One preview: the view, the selection and hover, the menu style and the card's detail. */
	private static final class Shot
	{
		final String name;
		final MapView view;
		String selected;
		String hovered;
		TreeMenu.Style style = TreeMenu.Style.MODERN;
		boolean fullDetails;

		Shot(String name, MapView view)
		{
			this.name = name;
			this.view = view;
		}
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
		java.awt.Insets in = SpiritTreeAtlasPlugin.chromeInsets();

		Shot fit = new Shot("1-fit", SpiritTreeAtlasPlugin.fitTrees(MapView.of(surface, big), repo.surfaceMarkers(), in));
		fit.selected = "GRAND_EXCHANGE";
		render(repo, painter, out, fit);

		Tree ge = repo.tree("GRAND_EXCHANGE");
		Shot ge4 = new Shot("2-grand-exchange-4ppt", MapView.of(surface, big).focusOn(ge.getX() + 0.5, ge.getY() + 0.5, 4, in));
		ge4.selected = "GRAND_EXCHANGE";
		ge4.hovered = "GRAND_EXCHANGE";
		render(repo, painter, out, ge4);

		Tree village = repo.tree("TREE_GNOME_VILLAGE");
		Shot close = new Shot("3-16ppt", MapView.of(surface, big).focusOn(village.getX() + 0.5, village.getY() + 0.5, 16, in));
		close.selected = "TREE_GNOME_VILLAGE";
		render(repo, painter, out, close);

		Layer prif = repo.layer(Layer.PRIFDDINAS);
		Shot city = new Shot("4-prifddinas", SpiritTreeAtlasPlugin.fitLayer(prif, big, in));
		city.selected = "PRIFDDINAS";
		render(repo, painter, out, city);

		Shot modern = new Shot("5-fixed-modern", SpiritTreeAtlasPlugin.fitTrees(MapView.of(surface, fixed), repo.surfaceMarkers(), in));
		modern.selected = "GRAND_EXCHANGE";
		render(repo, painter, out, modern);

		Shot classic = new Shot("6-fixed-classic", SpiritTreeAtlasPlugin.fitTrees(MapView.of(surface, fixed), repo.surfaceMarkers(), in));
		classic.selected = "POISON_WASTE";
		classic.style = TreeMenu.Style.CLASSIC;
		render(repo, painter, out, classic);

		Tree waste = repo.tree("POISON_WASTE");
		Shot locked = new Shot("7-locked-card", MapView.of(surface, big).focusOn(waste.getX() + 0.5, waste.getY() + 0.5, 2, in));
		locked.selected = "POISON_WASTE";
		locked.fullDetails = true;
		render(repo, painter, out, locked);

		treeChecks(repo, painter, out);

		// "Use free space" on a 2000x1082 resizable canvas: all of the HUD area but its 6 px inset
		Rectangle free = new Rectangle(30, 30, 1738, 905);
		Shot space = new Shot("9-free-space", SpiritTreeAtlasPlugin.fitTrees(MapView.of(surface, free), repo.surfaceMarkers(), in));
		space.selected = "HOSIDIUS";
		space.hovered = "LAGUNA_AURORAE";
		render(repo, painter, out, space);

		listAndNotice(painter, out);
		icon(repo, painter, out);
	}

	/**
	 * The menu as a mid-game account might see it: most trees listed, five in grey, the Farming
	 * Guild missing; the house in Rimmington; the last trip to the Grand Exchange.
	 */
	private static void menuState(TreeRepository repo)
	{
		repo.placeHouse(1);
		repo.setLast(4);
		repo.locate(0, 0, 0, false);
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
		TreeMenu.Geometry g = modern ? TreeMenu.modern(512, 334, 8, 52, 322, 161, 20, 0, 0) : TreeMenu.classic(512, 334, 386, 16, 0);
		Rectangle cell = new Rectangle(slot.x + g.getCell().x, slot.y + g.getCell().y, g.getCell().width, g.getCell().height);
		Point c = modern ? new Point(g.getRoot().x + 338 - 44, g.getRoot().y + 17) : g.getClose();
		Rectangle close = new Rectangle(slot.x + c.x, slot.y + c.y, 26, 23);

		Scene s = scene(repo, v, shot.selected, shot.hovered);
		s.fullDetails = shot.fullDetails;
		TreeMenu.Row row = repo.row(shot.selected);
		s.rowShown = Scene.rowShown(shot.selected, row, repo.getHere());
		s.rowCell = cell;
		s.closeRect = close;
		s.holes.add(close);
		if (s.rowShown)
		{
			s.holes.add(cell);
		}
		s.standIn = s.rowShown ? null : Scene.standInText(repo.tree(shot.selected), row, repo.getHere());

		BufferedImage canvas = new BufferedImage(rect.x * 2 + rect.width, rect.y * 2 + rect.height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g2 = canvas.createGraphics();
		g2.setColor(new Color(0x2a2620));
		g2.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
		g2.setColor(new Color(0x404640));
		g2.fill(slot);
		// stand-ins for the game's own close button and, in the Travel hole, the real row on RowBackdrop
		g2.setColor(new Color(0x8a2a1a));
		g2.fill(close);
		if (s.rowShown)
		{
			g2.setColor(modern ? new Color(0, 0, 0) : new Color(0xD9C9A0));
			g2.fill(cell);
			g2.setColor(modern ? new Color(0xff981f) : new Color(0x322805));
			g2.setFont(new Font(Font.DIALOG, Font.PLAIN, 11));
			String text = row.getKey() + ": " + row.getLabel();
			g2.drawString(text, cell.x + (cell.width - g2.getFontMetrics().stringWidth(text)) / 2, cell.y + cell.height - 5);
		}

		long t0 = System.nanoTime();
		BufferedImage base = new BufferedImage(rect.width, rect.height, BufferedImage.TYPE_INT_RGB);
		Layer layer = repo.layer(v.getLayer());
		MapRenderer.render(base, v, tiles, layer.getBackgroundColor());
		long t1 = System.nanoTime();
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
		System.out.printf("%-22s ppt=%6.3f z=%d  base=%6.1f ms  paint=%5.1f ms  hits=%d%n",
			shot.name, v.getPpt(), tiles.levelFor(v.getPpt()), (t1 - t0) / 1e6, (t2 - t1) / 1e6, s.hits.size());
		tiles.close();
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
		g.setColor(new Color(0x2a2620));
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
		g.setColor(new Color(0x2a2620));
		g.fillRect(0, 0, img.getWidth(), img.getHeight());
		Rectangle canvas = new Rectangle(0, 0, img.getWidth(), img.getHeight());
		Rectangle[] slots = {new Rectangle(30, 60, 512, 334), new Rectangle(610, 60, 512, 334)};
		for (Rectangle slot : slots)
		{
			g.setColor(new Color(0x404640));
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

	/**
	 * The hub's 48x72 icon.png (build/preview/icon.png, copied to the repo root by hand): a crop
	 * of the map around the Grand Exchange with its spirit tree marker selected, drawn large.
	 */
	private static void icon(TreeRepository repo, AtlasPainter painter, File out) throws IOException
	{
		TileStore tiles = new TileStore(repo.getIndex(), repo.getLayers(), BASE, Runnable::run);
		Tree t = repo.tree("GRAND_EXCHANGE");
		Layer layer = repo.layer(t.getLayer());
		MapView v = MapView.of(layer, new Rectangle(0, 0, 48, 72)).centerOn(t.getX() + 0.5, t.getY() + 0.5, 1.5);
		BufferedImage img = new BufferedImage(48, 72, BufferedImage.TYPE_INT_RGB);
		MapRenderer.render(img, v, tiles, layer.getBackgroundColor());
		Graphics2D g = img.createGraphics();
		Scene s = scene(repo, v, t.getId(), null);
		g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(java.awt.RenderingHints.KEY_STROKE_CONTROL, java.awt.RenderingHints.VALUE_STROKE_PURE);
		g.translate(v.screenX(t.getX() + 0.5), v.screenY(t.getY() + 0.5));
		g.scale(1.9, 1.9);
		painter.drawMarker(g, 0, 0, 7.5, AtlasPainter.AVAILABLE | AtlasPainter.SELECTED, s);
		g.dispose();
		ImageIO.write(img, "png", new File(out, "icon.png"));
		tiles.close();
	}

	static List<String> names()
	{
		return Arrays.asList("1-fit", "2-grand-exchange-4ppt", "3-16ppt", "4-prifddinas", "5-fixed-modern", "6-fixed-classic",
			"7-locked-card", "8-checks", "9-free-space", "10-list-and-notice", "icon");
	}
}
