/*
 * Copyright (c) 2026, Dewdul
 * All rights reserved.
 * Licensed under the BSD 2-Clause License. See LICENSE.
 */
package com.spirittreeatlas;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.QuadCurve2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.Widget;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Draws the atlas while the dial interface is open, in absolute canvas coordinates (render returns
 * null, so the overlay keeps no bounds of its own). Map mode: the cached base map, then markers,
 * labels and chrome, leaving holes over Teleport and close. Dial mode: a "Map" button and the
 * rotation guide over the real dials. In both, the selected ring's travel log row is outlined.
 */
@Singleton
public class AtlasOverlay extends Overlay
{
	private static final long RETRY_MS = 250;

	private final Client client;
	private final SpiritTreeAtlasPlugin plugin;
	private final SpiritTreeAtlasConfig config;
	private final SpriteManager spriteManager;
	private final AtlasPainter painter = new AtlasPainter();
	private final ChromePainter chrome = new ChromePainter(painter.ink());
	private final Scene scene = new Scene();
	private final Map<Integer, BufferedImage> sprites = new ConcurrentHashMap<>();
	private final Map<Integer, Boolean> spritesAsked = new ConcurrentHashMap<>();

	private BufferedImage cache;
	private MapView cacheView;
	private int cacheGeneration = -1;
	/** Whether the cached base map had every tile at the wanted level, and when it was built. */
	private boolean cacheComplete;
	private long cacheBuiltAt;
	private Area clipArea;
	private Rectangle clipRect;
	private List<Rectangle> clipHoles;

	@Inject
	AtlasOverlay(Client client, SpiritTreeAtlasPlugin plugin, SpiritTreeAtlasConfig config, SpriteManager spriteManager)
	{
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		this.spriteManager = spriteManager;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPriority(PRIORITY_HIGHEST);
		scene.sprites = this::sprite;
	}

	void reset()
	{
		releaseCaches();
		scene.hovered = null;
		sprites.clear();
		spritesAsked.clear();
	}

	/** Client thread: drops the map-sized buffers and text sprites; they are rebuilt on the next open. */
	void releaseCaches()
	{
		cache = null;
		cacheView = null;
		cacheGeneration = -1;
		chrome.release();
		painter.ink().clear();
	}

	/** Where the dragged row lands as last painted, or null. */
	ChromePainter.Drop drop()
	{
		return chrome.drop;
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!plugin.isOpen())
		{
			return null;
		}
		RingRepository repo = plugin.getRepo();
		scene.repo = repo;
		scene.now = System.currentTimeMillis();
		scene.selected = plugin.getSelected();
		scene.dials = plugin.dials();
		scene.query = plugin.getSearch();
		Ring selected = repo.ring(scene.selected);
		boolean ready = selected != null && scene.dialsMatch(selected.getCode());
		Rectangle row = selected != null && !ready && repo.hasLogRow(selected.getCode())
			? plugin.getTravelLog().rowBounds(selected, repo.favouriteSlot(selected.getCode())) : null;
		scene.rowVisible = row != null;

		if (plugin.getMode() == SpiritTreeAtlasPlugin.Mode.DIAL)
		{
			renderDialMode(g, selected, ready);
			plugin.publishGroups(null, 0);
			if (row != null)
			{
				paintRowHighlight(g, row, null, java.util.Collections.emptyList(), config.selectedColor(), scene.now);
			}
			return null;
		}

		Rectangle rect = MapLayout.fromClient(client, config.maxWidth(), config.maxHeight());
		if (rect == null)
		{
			return null;
		}
		List<Rectangle> holes = MapLayout.holes(client);
		Rectangle confirm = MapLayout.bounds(client, net.runelite.api.gameval.InterfaceID.Fairyrings.CONFIRM);
		scene.teleportSlot = confirm;
		if (confirm != null && !scene.teleportShown())
		{
			// the map covers Teleport (and owns its clicks) until the dials are worth using
			holes.remove(confirm);
			confirm = null;
		}
		MapView v = plugin.frameView(rect);
		fillScene(v, holes, confirm);
		chrome.layout(scene);

		Layer layer = repo.layer(v.getLayer());
		int gen = plugin.getTiles().getGeneration();
		if (cache == null || cache.getWidth() != v.getW() || cache.getHeight() != v.getH())
		{
			cache = new BufferedImage(v.getW(), v.getH(), BufferedImage.TYPE_INT_RGB);
			cacheView = null;
		}
		// an incomplete map is rebuilt now and then even without news from the decoder, as a
		// safety net in case a tile request was lost
		boolean retry = !cacheComplete && scene.now - cacheBuiltAt > RETRY_MS;
		if (!v.equals(cacheView) || gen != cacheGeneration || retry)
		{
			cacheGeneration = gen;
			cacheView = v;
			cacheBuiltAt = scene.now;
			cacheComplete = MapRenderer.render(cache, v, plugin.getTiles(), layer == null ? Color.BLACK : layer.getBackgroundColor());
		}

		chrome.paintShadow(g, scene);
		Shape oldClip = g.getClip();
		g.clip(clip(rect, holes));
		g.drawImage(cache, v.getX(), v.getY(), null);
		painter.paintMap(g, scene);
		chrome.paint(g, scene);
		g.setClip(oldClip);
		chrome.paintHoles(g, scene, ready);
		if (row != null)
		{
			// the leader runs from Teleport (where the player goes next) to the log row; without a
			// Teleport slot, from the card or the map
			Rectangle from = scene.teleportSlot != null ? AtlasPainter.grow(scene.teleportSlot, 4)
				: scene.card != null ? scene.card : rect;
			paintRowHighlight(g, row, from, scene.blockers(), config.selectedColor(), scene.now);
		}
		plugin.publish(new ArrayList<>(scene.hits), holes, scene.panel, chrome.panelScrollMax, null);
		plugin.publishGroups(scene.groupsPanel, chrome.groupsScrollMax);
		return null;
	}

	private void fillScene(MapView v, List<Rectangle> holes, Rectangle confirm)
	{
		Scene s = scene;
		s.view = v;
		s.holes.clear();
		s.holes.addAll(holes);
		s.confirmHole = confirm;
		s.here = plugin.getHere();
		s.flashUntil = plugin.getFlashUntil();
		s.notice = plugin.getNotice();
		s.panelCollapsed = plugin.isPanelCollapsed(v.getW());
		s.panelScroll = plugin.getPanelScroll();
		s.faveOrder = plugin.getFaveOrder();
		s.dragList = plugin.getDragList();
		s.dragCode = plugin.getDragCode();
		s.dragY = plugin.getDragY();
		s.groupsOpen = plugin.isGroupsOpen(v.getW());
		s.groups = plugin.getGroupsView();
		s.groupsScroll = plugin.getGroupsScroll();
		WorldPoint clue = config.clueHelper() ? plugin.getClue() : null;
		s.clueX = clue == null ? Double.NaN : clue.getX();
		s.clueY = clue == null ? Double.NaN : clue.getY();
		s.codeLabels = config.codeLabels();
		s.placeLabels = config.placeLabels();
		s.mapIcons = config.mapIcons();
		s.dimUnvisited = config.dimUnvisited();
		s.fullDetails = config.fullDetails();
		s.visitedColor = config.visitedColor();
		s.selectedColor = config.selectedColor();
		s.favouriteColor = config.favouriteColor();
		net.runelite.api.Point mp = client.getMouseCanvasPosition();
		s.mouse = new java.awt.Point(mp.getX(), mp.getY());
		// while a right-click menu is open the card keeps describing the ring it was opened on
		if (!client.isMenuOpen())
		{
			Hit hover = plugin.isMapInput(mp.getX(), mp.getY()) ? plugin.hitAt(mp.getX(), mp.getY()) : null;
			s.hovered = hover != null && (hover.getKind() == Hit.Kind.MARKER || hover.getKind() == Hit.Kind.CHIP
				|| hover.getKind() == Hit.Kind.CARD) ? hover.getRing() : null;
			s.hoveredNote = s.hovered == null ? null : plugin.groupNote(hover.getId(), s.hovered.getCode());
			s.hoveredDetails = s.hovered == null ? null : plugin.groupDetails(hover.getId(), s.hovered.getCode());
		}
		if (!plugin.isMapInput(mp.getX(), mp.getY()))
		{
			s.mouse = null;
		}
		s.hits.clear();
		s.card = null;
	}

	private Shape clip(Rectangle rect, List<Rectangle> holes)
	{
		if (!rect.equals(clipRect) || !holes.equals(clipHoles))
		{
			clipRect = rect;
			clipHoles = holes;
			clipArea = new Area(rect);
			for (Rectangle h : holes)
			{
				clipArea.subtract(new Area(h));
			}
		}
		return clipArea;
	}

	private BufferedImage sprite(int id)
	{
		BufferedImage img = sprites.get(id);
		if (img == null && spritesAsked.putIfAbsent(id, Boolean.TRUE) == null)
		{
			spriteManager.getSpriteAsync(id, 0, loaded ->
			{
				if (loaded != null)
				{
					sprites.put(id, loaded);
				}
			});
		}
		return img;
	}

	// ------------------------------------------------------------------ travel log row

	/**
	 * A pulsing outline around the row to click, and a curved leader from the card or map edge.
	 * The leader passes under {@code avoid} (the holes and Teleport's slot, with their frames):
	 * its way from the card to the log can cross the map's bottom-right corner.
	 */
	static void paintRowHighlight(Graphics2D g, Rectangle row, Rectangle from, List<Rectangle> avoid, Color c, long now)
	{
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		double t = 0.5 + 0.5 * Math.sin(now / 180.0);
		Rectangle r = AtlasPainter.grow(row, 2);
		g.setStroke(new BasicStroke(4f));
		g.setColor(new Color(0, 0, 0, 120));
		g.drawRoundRect(r.x, r.y, r.width, r.height, 6, 6);
		g.setStroke(new BasicStroke(2f));
		g.setColor(AtlasPainter.withAlpha(c, (int) (140 + 115 * t)));
		g.drawRoundRect(r.x, r.y, r.width, r.height, 6, 6);
		if (from == null || from.intersects(r))
		{
			return;
		}
		double sx;
		double sy;
		double ex;
		double ey = r.getCenterY();
		if (r.x >= from.x + from.width)
		{
			sx = from.x + from.width;
			sy = Math.max(from.y + 10, Math.min(from.y + from.height - 10, ey));
			ex = r.x - 2;
		}
		else if (r.x + r.width <= from.x)
		{
			sx = from.x;
			sy = Math.max(from.y + 10, Math.min(from.y + from.height - 10, ey));
			ex = r.x + r.width + 2;
		}
		else
		{
			return;
		}
		double mx = (sx + ex) / 2;
		double my = Math.min(sy, ey) - Math.abs(ex - sx) * 0.25;
		QuadCurve2D curve = new QuadCurve2D.Double(sx, sy, mx, my, ex, ey);
		Shape oldClip = g.getClip();
		Area clip = new Area(AtlasPainter.grow(curve.getBounds(), 12));
		for (Rectangle h : avoid)
		{
			clip.subtract(new Area(AtlasPainter.grow(h, 3)));
		}
		g.clip(clip);
		g.setColor(new Color(0, 0, 0, 140));
		g.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.draw(curve);
		g.setColor(AtlasPainter.withAlpha(c, 230));
		// the dashes march toward the row; a dash phase must not be negative, so it counts
		// down through one dash period (6 + 4)
		g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10, new float[]{6f, 4f}, 10f - (now % 1000) / 100f));
		g.draw(curve);
		double ang = Math.atan2(ey - my, ex - mx);
		Path2D head = new Path2D.Double();
		head.moveTo(ex, ey);
		head.lineTo(ex - Math.cos(ang - 0.45) * 9, ey - Math.sin(ang - 0.45) * 9);
		head.lineTo(ex - Math.cos(ang + 0.45) * 9, ey - Math.sin(ang + 0.45) * 9);
		head.closePath();
		g.fill(head);
		g.setClip(oldClip);
	}

	// ------------------------------------------------------------------ dial mode

	private void renderDialMode(Graphics2D g, Ring selected, boolean ready)
	{
		Rectangle dial = MapLayout.bounds(client, net.runelite.api.gameval.InterfaceID.Fairyrings.ROOT_RECT0);
		if (dial == null)
		{
			plugin.publish(new ArrayList<>(), new ArrayList<>(), null, 0, null);
			return;
		}
		Ink ink = painter.ink();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		Rectangle b = new Rectangle(dial.x + 8, dial.y + 8, ink.width(ink.small, "Map") + 30, 20);
		net.runelite.api.Point mp = client.getMouseCanvasPosition();
		boolean hover = !client.isMenuOpen() && b.contains(mp.getX(), mp.getY());
		g.setColor(ChromePainter.FRAME);
		g.fillRoundRect(b.x - 1, b.y - 1, b.width + 2, b.height + 2, 6, 6);
		g.setColor(hover ? new Color(0x5a4b36) : new Color(0x3a3125));
		g.fillRoundRect(b.x, b.y, b.width, b.height, 5, 5);
		g.setColor(hover ? ChromePainter.BRONZE_LIGHT : ChromePainter.BRONZE);
		g.setStroke(new BasicStroke(1f));
		g.drawRoundRect(b.x, b.y, b.width - 1, b.height - 1, 5, 5);
		paintMapGlyph(g, b.x + 6, b.y + 5);
		ink.text(g, "Map", ink.small, hover ? Color.WHITE : ChromePainter.CREAM, b.x + 22, b.y + (b.height - ink.height(ink.small)) / 2, Ink.Style.SHADOW);
		plugin.publish(new ArrayList<>(), new ArrayList<>(), null, 0, b);

		if (!config.dialGuidance() || selected == null || !selected.isDialable())
		{
			return;
		}
		int[] plan = DialMath.plan(scene.dials, selected.getCode());
		Color hint = ChromePainter.HINT;
		for (int d = 0; d < 3; d++)
		{
			Widget cw = client.getWidget(SpiritTreeAtlasPlugin.ZONES[d][0]);
			Widget acw = client.getWidget(SpiritTreeAtlasPlugin.ZONES[d][1]);
			if (cw == null || acw == null || cw.isHidden() || acw.isHidden())
			{
				continue;
			}
			Rectangle zc = cw.getBounds();
			Rectangle za = acw.getBounds();
			Rectangle both = zc.union(za);
			String letter = String.valueOf(selected.getCode().charAt(d));
			int k = plan[d];
			// target letter badge above the dial
			int bw = 26;
			Rectangle badge = new Rectangle((int) both.getCenterX() - bw / 2, both.y - 24, bw, 20);
			g.setColor(new Color(16, 13, 9, 225));
			g.fillRoundRect(badge.x, badge.y, badge.width, badge.height, 6, 6);
			g.setColor(k == 0 ? ChromePainter.GOOD : config.selectedColor());
			g.setStroke(new BasicStroke(1.5f));
			g.drawRoundRect(badge.x, badge.y, badge.width, badge.height, 6, 6);
			if (k == 0)
			{
				ink.text(g, letter, ink.bold, ChromePainter.GOOD, badge.x + 4, badge.y + 4, Ink.Style.SHADOW);
				AtlasPainter.drawCheck(g, badge.x + 18, badge.y + 10, 4, ChromePainter.GOOD);
				continue;
			}
			ink.text(g, letter, ink.bold, config.selectedColor(), badge.x + (bw - ink.width(ink.bold, letter)) / 2, badge.y + 4, Ink.Style.SHADOW);
			boolean clockwise = DialMath.clockwise(k);
			Rectangle zone = clockwise ? zc : za;
			double pulse = 0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 200.0);
			g.setColor(AtlasPainter.withAlpha(hint, (int) (60 + 80 * pulse)));
			g.setStroke(new BasicStroke(2f));
			g.drawRoundRect(zone.x + 2, zone.y + 2, zone.width - 4, zone.height - 4, 10, 10);
			AtlasPainter.drawRotateArrow(g, zone.getCenterX(), zone.getCenterY() - 8, 18, clockwise, hint, 3f);
			String n = "x" + DialMath.clicks(k);
			int nw = ink.width(ink.bold, n) + 8;
			int nx = (int) zone.getCenterX() - nw / 2;
			int ny = (int) zone.getCenterY() + 18;
			g.setColor(new Color(16, 13, 9, 225));
			g.fillRoundRect(nx, ny, nw, 18, 6, 6);
			ink.text(g, n, ink.bold, hint, nx + 4, ny + 3, Ink.Style.SHADOW);
		}
		if (ready)
		{
			Rectangle confirm = MapLayout.bounds(client, net.runelite.api.gameval.InterfaceID.Fairyrings.CONFIRM);
			if (confirm != null)
			{
				double t = 0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 160.0);
				g.setColor(AtlasPainter.withAlpha(ChromePainter.GOOD, (int) (120 + 135 * t)));
				g.setStroke(new BasicStroke(2.5f));
				g.drawRoundRect(confirm.x - 4, confirm.y - 4, confirm.width + 7, confirm.height + 7, 6, 6);
			}
		}
	}

	/** A tiny folded-map glyph. */
	private static void paintMapGlyph(Graphics2D g, int x, int y)
	{
		Path2D p = new Path2D.Double();
		p.moveTo(x, y + 2);
		p.lineTo(x + 4, y);
		p.lineTo(x + 8, y + 2);
		p.lineTo(x + 12, y);
		p.lineTo(x + 12, y + 9);
		p.lineTo(x + 8, y + 11);
		p.lineTo(x + 4, y + 9);
		p.lineTo(x, y + 11);
		p.closePath();
		g.setColor(new Color(0xD9C38F));
		g.fill(p);
		g.setColor(ChromePainter.FRAME);
		g.setStroke(new BasicStroke(1f));
		g.draw(p);
		g.drawLine(x + 4, y, x + 4, y + 9);
		g.drawLine(x + 8, y + 2, x + 8, y + 11);
	}
}
