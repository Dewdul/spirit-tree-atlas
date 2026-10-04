/*
 * Copyright (c) 2026, Dewdul
 * All rights reserved.
 * Licensed under the BSD 2-Clause License. See LICENSE.
 */
package com.fairyringatlas;

import com.google.gson.Gson;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.geom.Area;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;

/**
 * Renders the map offline, without a game client, from the real bundled resources into
 * build/preview/*.png so the drawing can be checked by eye. Run through MapPreviewTest with the
 * environment variable FRA_PREVIEW=1, or as a main from an IDE.
 */
public class MapPreview
{
	private static final String BASE = FairyRingAtlasPlugin.RESOURCES;
	/** The prebuilt groups, and whether the next render shows the Groups panel open. */
	private static RingGroups groups;
	private static boolean groupsOpen;

	public static void main(String[] args) throws IOException
	{
		File out = new File(args.length > 0 ? args[0] : "build/preview");
		if (!out.isDirectory() && !out.mkdirs())
		{
			throw new IOException("cannot create " + out);
		}
		RingRepository repo = RingRepository.load(new Gson(), BASE);
		Set<String> visited = new HashSet<>();
		for (Ring r : repo.dialable())
		{
			if (!Arrays.asList("BLQ", "CJQ", "AIS", "DLP", "CKQ", "AJP", "BJP", "AKP", "CLR").contains(r.getCode()))
			{
				visited.add(r.getCode());
			}
		}
		Map<String, Integer> faves = new HashMap<>();
		faves.put("AIQ", 0);
		faves.put("CKS", 1);
		faves.put("DKR", 2);
		faves.put("BKS", 3);
		faves.put("DLS", 4);
		repo.setState(visited, faves, "DKR");
		// unlock checks: Children of the Sun under way (AIS, AJP, CKQ need it), Beneath Cursed Sands
		// short of the Necropolis (AKP), the Great Conch reached (CJQ: a visit unlocks it), Monkey
		// Madness I done (CLR: Daero's training cannot be checked)
		repo.checkUnlocks(new UnlockCheckTest.FakeVars()
			.quest("CHILDREN_OF_THE_SUN", net.runelite.api.QuestState.IN_PROGRESS)
			.quest("MONKEY_MADNESS_I", net.runelite.api.QuestState.FINISHED)
			.varbit(net.runelite.api.gameval.VarbitID.BCS, 5)
			.varbit(net.runelite.api.gameval.VarbitID.TT, 12));

		AtlasPainter painter = new AtlasPainter();
		Layer surface = repo.surface();
		Rectangle big = new Rectangle(30, 30, 1100, 720);
		Rectangle fixed = new Rectangle(30, 30, 512, 334);

		java.awt.Insets wide = FairyRingAtlasPlugin.chromeInsets(big.width, false, false, false);
		java.awt.Insets narrow = FairyRingAtlasPlugin.chromeInsets(fixed.width, true, false, false);
		groups = RingGroups.parse(new Gson(), RingGroups.defaults(RingRepository.read(new Gson(), BASE + "groups.json", RingGroups.DefFile.class)), null);
		MapView fit = FairyRingAtlasPlugin.fitRings(MapView.of(surface, big), repo.ringsIn(Layer.SURFACE), 0, wide);
		render(repo, painter, out, "1-fit", fit, "AIQ", null, new int[]{0, 1, 2}, false);
		render(repo, painter, out, "2-lumbridge-4ppt", MapView.of(surface, big).focusOn(3222.5, 3218.5, 4, wide), "CLP", "DIS", new int[]{3, 2, 2}, false);
		render(repo, painter, out, "3-16ppt", MapView.of(surface, big).focusOn(2996.5, 3114.5, 16, wide), "AIQ", null, DialMath.values("AIQ"), false);
		Layer zanaris = repo.layer("zanaris");
		if (zanaris != null)
		{
			render(repo, painter, out, "4-zanaris", FairyRingAtlasPlugin.fitLayer(zanaris, big, wide), "BKS", null, new int[]{0, 0, 0}, false);
		}
		render(repo, painter, out, "5-fixed-fit", FairyRingAtlasPlugin.fitRings(MapView.of(surface, fixed), repo.ringsIn(Layer.SURFACE), 0, narrow),
			"AIS", null, new int[]{1, 3, 0}, true);
		render(repo, painter, out, "6-kandarin-1ppt", MapView.of(surface, big).focusOn(2620, 3350, 1.25, wide), "CKS", "ALS", new int[]{2, 2, 2}, false);
		// "Use free space" on a 2000x1082 resizable canvas: all of the HUD area but its 6 px inset
		Rectangle free = new Rectangle(30, 30, 1738, 905);
		render(repo, painter, out, "8-free-space", FairyRingAtlasPlugin.fitRings(MapView.of(surface, free), repo.ringsIn(Layer.SURFACE), 0, wide),
			"CKS", "AIQ", new int[]{0, 1, 2}, false);
		// the Groups panel open on the right with both prebuilt groups, a Slayer row hovered (its note and
		// details on the card; on the narrow map the longest details)
		groupsOpen = true;
		render(repo, painter, out, "9-groups", FairyRingAtlasPlugin.fitRings(MapView.of(surface, free), repo.ringsIn(Layer.SURFACE), 0,
			FairyRingAtlasPlugin.chromeInsets(free.width, false, true, false)), "CIR", "AJR", new int[]{0, 1, 2}, false);
		render(repo, painter, out, "10-groups-fixed", FairyRingAtlasPlugin.fitRings(MapView.of(surface, fixed), repo.ringsIn(Layer.SURFACE), 0,
			FairyRingAtlasPlugin.chromeInsets(fixed.width, true, true, false)), "CKS", "DKR", new int[]{1, 3, 0}, true);
		groupsOpen = false;
		// a locked ring: needs something first, a first visit unlocks it, a need that cannot be checked
		String[][] locked = {{"11-locked-needs", "AKP"}, {"12-locked-visit", "CJQ"}, {"13-locked-hint", "CLR"}};
		for (String[] l : locked)
		{
			Ring r = repo.ring(l[1]);
			render(repo, painter, out, l[0], MapView.of(surface, big).focusOn(r.getX() + 0.5, r.getY() + 0.5, 2, wide),
				l[1], null, new int[]{0, 1, 2}, false);
		}
		ringChecks(repo, painter, out);
		icons(repo, painter, out);
	}

	/**
	 * The hub's 48x72 icon.png (build/preview/icon.png, copied to the repo root by hand): a crop of
	 * the bundled Zanaris imagery with one fairy ring marker in the plugin's own style, drawn large.
	 */
	private static void icons(RingRepository repo, AtlasPainter painter, File out) throws IOException
	{
		// name, ring, ppt, marker flags, marker scale, marker colour (null: the visited colour)
		Object[][] picks = {
			{"icon", "BKS", 1.5, AtlasPainter.VISITED | AtlasPainter.HOVER, 1.9, new Color(0xFF981F)},
		};
		TileStore tiles = new TileStore(repo.getIndex(), repo.getLayers(), BASE, Runnable::run);
		for (Object[] p : picks)
		{
			Ring r = repo.ring((String) p[1]);
			Layer layer = repo.layer(r.getLayer());
			Rectangle rect = new Rectangle(0, 0, 48, 72);
			MapView v = MapView.of(layer, rect).centerOn(r.getX() + 0.5, r.getY() + 0.5, (Double) p[2]);
			BufferedImage img = new BufferedImage(48, 72, BufferedImage.TYPE_INT_RGB);
			MapRenderer.render(img, v, tiles, layer.getBackgroundColor());
			Graphics2D g = img.createGraphics();
			Scene s = new Scene();
			s.repo = repo;
			s.view = v;
			s.codeLabels = Scene.CodeLabels.NONE;
			s.placeLabels = false;
			s.mapIcons = false;
			if (p[5] != null)
			{
				s.visitedColor = (Color) p[5];
			}
			// the neighbouring rings at their normal size give the icon its map context
			for (Ring other : repo.mappedIn(layer.getId()))
			{
				if (other != r && other.isDialable())
				{
					BufferedImage m = painter.marker(AtlasPainter.VISITED, AtlasPainter.radius(v.getPpt(), false), s);
					g.drawImage(m, (int) Math.round(v.screenX(other.getX() + 0.5) - m.getWidth() / 2.0),
						(int) Math.round(v.screenY(other.getY() + 0.5) - m.getHeight() / 2.0), null);
				}
			}
			g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
			g.setRenderingHint(java.awt.RenderingHints.KEY_STROKE_CONTROL, java.awt.RenderingHints.VALUE_STROKE_PURE);
			g.translate(v.screenX(r.getX() + 0.5), v.screenY(r.getY() + 0.5));
			g.scale((Double) p[4], (Double) p[4]);
			painter.drawMarker(g, 0, 0, 5.5, (Integer) p[3], s);
			g.dispose();
			ImageIO.write(img, "png", new File(out, p[0] + ".png"));
		}
		tiles.close();
	}

	/** Rings whose marker must sit on the right spot of the imagery; one close-up each, ring at the centre. */
	static final List<String> CHECKS = Arrays.asList("AIQ", "BKS", "CKS", "AJR", "DKS", "ALS", "BLP", "DLS", "CIS", "DIP");
	static final int CHECK_SIZE = 240;
	static final double CHECK_PPT = 8;

	/** Writes 7-checks.png: 8 ppt close-ups, each centred on one ring of {@link #CHECKS}. */
	private static void ringChecks(RingRepository repo, AtlasPainter painter, File out) throws IOException
	{
		TileStore tiles = new TileStore(repo.getIndex(), repo.getLayers(), BASE, Runnable::run);
		int n = CHECKS.size();
		int cols = 5;
		int rows = (n + cols - 1) / cols;
		BufferedImage sheet = new BufferedImage(cols * (CHECK_SIZE + 8) + 8, rows * (CHECK_SIZE + 8) + 8, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = sheet.createGraphics();
		g.setColor(new Color(0x2a2620));
		g.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
		for (int i = 0; i < n; i++)
		{
			Ring r = repo.ring(CHECKS.get(i));
			Layer layer = repo.layer(r.getLayer());
			Rectangle rect = new Rectangle(8 + (i % cols) * (CHECK_SIZE + 8), 8 + (i / cols) * (CHECK_SIZE + 8), CHECK_SIZE, CHECK_SIZE);
			MapView v = MapView.of(layer, rect).centerOn(r.getX() + 0.5, r.getY() + 0.5, CHECK_PPT);
			BufferedImage base = new BufferedImage(rect.width, rect.height, BufferedImage.TYPE_INT_RGB);
			MapRenderer.render(base, v, tiles, layer.getBackgroundColor());
			Scene s = new Scene();
			s.repo = repo;
			s.view = v;
			s.dials = new int[]{0, 0, 0};
			Shape clip = g.getClip();
			g.clip(rect);
			g.drawImage(base, rect.x, rect.y, null);
			painter.paintMap(g, s);
			g.setClip(clip);
		}
		g.dispose();
		ImageIO.write(sheet, "png", new File(out, "7-checks.png"));
		tiles.close();
	}

	private static void render(RingRepository repo, AtlasPainter painter, File out, String name, MapView v,
		String selected, String hovered, int[] dials, boolean fixedMode) throws IOException
	{
		TileStore tiles = new TileStore(repo.getIndex(), repo.getLayers(), BASE, Runnable::run);
		ChromePainter chrome = new ChromePainter(painter.ink());
		Rectangle rect = v.rect();
		// the map grows from the dials' bottom-right corner; the moved Teleport and close are holes
		int dx = rect.x + rect.width - 512;
		int dy = rect.y + rect.height - 334;
		int[] m = FairyRingAtlasPlugin.MOVED_IN_MAP[0];
		int[] c = FairyRingAtlasPlugin.MOVED_IN_MAP[2];
		Rectangle confirm = new Rectangle(dx + m[1], dy + m[2], m[3], m[4]);
		Rectangle close = new Rectangle(dx + c[1], dy + c[2], c[3], c[4]);

		Scene s = new Scene();
		s.repo = repo;
		s.view = v;
		s.selected = selected;
		s.hovered = repo.ring(hovered);
		s.here = "DKR";
		s.dials = dials;
		// as in the overlay: Teleport is a hole only when the dials are worth using
		if (s.teleportShown())
		{
			s.holes.add(confirm);
			s.confirmHole = confirm;
		}
		s.holes.add(close);
		s.teleportSlot = confirm;
		s.now = 400;
		s.panelCollapsed = fixedMode;
		s.groups = groups.view();
		s.groupsOpen = groupsOpen;
		if (groupsOpen && s.hovered != null && !s.groups.isEmpty())
		{
			// as when hovered in the first group's row
			String note = groups.note(s.groups.get(0).getId(), hovered);
			s.hoveredNote = note == null ? null : s.groups.get(0).getName() + ": " + note;
			s.hoveredDetails = groups.details(s.groups.get(0).getId(), hovered);
		}
		s.clueX = 3110;
		s.clueY = 3165;
		s.rowVisible = true;

		// room on the right for a stand-in travel log, as in resizable mode
		BufferedImage canvas = new BufferedImage(rect.x * 2 + rect.width + 230, rect.y * 2 + rect.height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = canvas.createGraphics();
		g.setColor(new Color(0x2a2620));
		g.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
		g.setColor(new Color(0x404640));
		g.fillRect(dx, dy, 512, 334);
		// stand-ins for the game's parchment Teleport and its close button
		g.setColor(new Color(0xC8B48A));
		g.fill(confirm);
		g.setColor(new Color(0x402000));
		g.drawString("Teleport to this location", confirm.x + 18, confirm.y + confirm.height / 2 + 5);
		g.setColor(new Color(0x8a2a1a));
		g.fill(close);

		long t0 = System.nanoTime();
		BufferedImage base = new BufferedImage(rect.width, rect.height, BufferedImage.TYPE_INT_RGB);
		Layer layer = repo.layer(v.getLayer());
		MapRenderer.render(base, v, tiles, layer.getBackgroundColor());
		long t1 = System.nanoTime();
		// a second build with every tile cached: the cost of a rebuild while panning
		MapRenderer.render(base, v, tiles, layer.getBackgroundColor());
		long t2 = System.nanoTime();

		chrome.layout(s);
		chrome.paintShadow(g, s);
		Shape clip = g.getClip();
		Area a = new Area(rect);
		for (Rectangle h : s.holes)
		{
			a.subtract(new Area(h));
		}
		g.clip(a);
		g.drawImage(base, rect.x, rect.y, null);
		painter.paintMap(g, s);
		chrome.paint(g, s);
		g.setClip(clip);
		chrome.paintHoles(g, s, s.dialsMatch(selected));
		long t3 = System.nanoTime();
		// steady-state frame: blit plus overlays with layouts and sprites cached
		for (int i = 0; i < 20; i++)
		{
			s.hits.clear();
			chrome.layout(s);
			g.setClip(a);
			g.drawImage(base, rect.x, rect.y, null);
			painter.paintMap(g, s);
			chrome.paint(g, s);
			g.setClip(clip);
			chrome.paintHoles(g, s, s.dialsMatch(selected));
		}
		long t4 = System.nanoTime();
		long[] parts = new long[3];
		for (int i = 0; i < 20; i++)
		{
			s.hits.clear();
			chrome.layout(s);
			long p0 = System.nanoTime();
			g.setClip(a);
			g.drawImage(base, rect.x, rect.y, null);
			long p1 = System.nanoTime();
			painter.paintMap(g, s);
			long p2 = System.nanoTime();
			chrome.paint(g, s);
			long p3 = System.nanoTime();
			parts[0] += p1 - p0;
			parts[1] += p2 - p1;
			parts[2] += p3 - p2;
			g.setClip(clip);
			chrome.paintHoles(g, s, s.dialsMatch(selected));
		}
		System.out.printf("   blit=%.2f map=%.2f chrome=%.2f ms%n", parts[0] / 20e6, parts[1] / 20e6, parts[2] / 20e6);
		// a stand-in travel log with the selected ring's row, and the leader from Teleport to it
		Ring sel = repo.ring(selected);
		if (sel != null && repo.hasLogRow(sel.getCode()) && !s.dialsMatch(selected))
		{
			Rectangle log = new Rectangle(rect.x + rect.width + 24, rect.y + rect.height - 261, 190, 261);
			g.setColor(new Color(0x3e3529));
			g.fill(log);
			g.setColor(new Color(0x5a4b36));
			g.draw(log);
			Rectangle row = new Rectangle(log.x + 8, log.y + 46, 160, 40);
			g.setColor(new Color(0xFF981F));
			g.drawString(sel.getCode() + " " + sel.getName(), row.x + 4, row.y + 24);
			AtlasOverlay.paintRowHighlight(g, row, s.teleportSlot != null ? AtlasPainter.grow(s.teleportSlot, 4) : s.card,
				s.blockers(), s.selectedColor, s.now);
		}
		g.dispose();
		ImageIO.write(canvas, "png", new File(out, name + ".png"));
		System.out.printf("%-18s ppt=%6.3f z=%d  base(cold)=%6.1f ms  base(warm)=%5.1f ms  first paint=%5.1f ms  frame=%5.2f ms  hits=%d%n",
			name, v.getPpt(), tiles.levelFor(v.getPpt()), (t1 - t0) / 1e6, (t2 - t1) / 1e6, (t3 - t2) / 1e6, (t4 - t3) / 20e6, s.hits.size());
		tiles.close();
	}

	static List<String> names()
	{
		return Arrays.asList("1-fit", "2-lumbridge-4ppt", "3-16ppt", "4-zanaris", "5-fixed-fit", "6-kandarin-1ppt", "7-checks",
			"8-free-space", "9-groups", "10-groups-fixed", "11-locked-needs", "12-locked-visit", "13-locked-hint");
	}
}
