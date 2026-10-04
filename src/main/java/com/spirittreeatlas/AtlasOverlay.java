/*
 * Copyright (c) 2026, Dewdul
 * All rights reserved.
 * Licensed under the BSD 2-Clause License. See LICENSE.
 */
package com.spirittreeatlas;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.geom.Area;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Draws the atlas while the spirit tree menu is open, in absolute canvas coordinates (render
 * returns null, so the overlay keeps no bounds of its own). Map mode: the cached base map, then
 * markers, labels and chrome, leaving holes over the close button and, while it is usable, the
 * Travel row. List mode: only the floating "Map" button. Stepping aside: only the notice.
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
	/** The repository state the scene's per-tree maps were filled from. */
	private int sceneState = Integer.MIN_VALUE;

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
		sceneState = Integer.MIN_VALUE;
		chrome.release();
		painter.release();
		painter.ink().clear();
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!plugin.isOpen())
		{
			return null;
		}
		TreeMenu menu = plugin.getMenu();
		if (plugin.getNotice() != null)
		{
			Rectangle slot = menu.slotBounds();
			if (slot != null)
			{
				chrome.paintNotice(g, slot, MapLayout.canvas(client), plugin.getNotice());
			}
			plugin.publish(Collections.emptyList(), Collections.emptyList(), null, null, 0);
			return null;
		}
		if (plugin.getMode() == SpiritTreeAtlasPlugin.Mode.LIST)
		{
			renderListMode(g, menu);
			return null;
		}

		Rectangle rect = MapLayout.fromClient(client, menu.slotBounds(), config.mapMaxWidth(), config.mapMaxHeight());
		if (rect == null)
		{
			plugin.publish(Collections.emptyList(), Collections.emptyList(), null, null, 0);
			return null;
		}
		TreeRepository repo = plugin.getRepo();
		String selected = plugin.getSelected();
		// the real row is uncovered only while pressing it goes where the player expects, checked
		// from the live widget every frame
		TreeMenu.Row row = repo.row(selected);
		TreeMenu.Row live = menu.liveRow(row, repo.getTrees(), repo.getUnavailableColour());
		boolean shown = Scene.rowShown(selected, live, repo.getHere());
		Rectangle cell = menu.rowCell(shown ? live : null);
		shown &= cell != null;
		List<Rectangle> holes = new ArrayList<>(2);
		Rectangle close = menu.closeRect();
		if (close != null)
		{
			holes.add(close);
		}
		if (shown)
		{
			holes.add(cell);
		}
		MapView v = plugin.frameView(rect);
		fillScene(v, repo, selected, holes, cell, close, shown, row);
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
		chrome.paintHoles(g, scene);
		plugin.publish(new ArrayList<>(scene.hits), holes, null, shown ? cell : null, chrome.panelScrollMax);
		return null;
	}

	private void fillScene(MapView v, TreeRepository repo, String selected, List<Rectangle> holes, Rectangle cell, Rectangle close,
		boolean shown, TreeMenu.Row row)
	{
		Scene s = scene;
		s.view = v;
		if (repo != s.repo || repo.getStateHash() != sceneState)
		{
			s.fromRepository(repo);
			sceneState = repo.getStateHash();
		}
		s.now = System.currentTimeMillis();
		s.selected = selected;
		s.holes.clear();
		s.holes.addAll(holes);
		s.rowCell = cell;
		s.closeRect = close;
		s.rowShown = shown;
		s.standIn = shown ? null : Scene.standInText(repo.tree(selected), row, repo.getHere());
		s.notice = null;
		s.placeLabels = config.placeLabels();
		s.mapIcons = config.mapIcons();
		s.fullDetails = config.fullDetails();
		s.treeLabels = config.treeLabels();
		s.keyHints = config.keyHints();
		s.dimLocked = config.dimLocked();
		s.availableColor = config.availableColor();
		s.selectedColor = config.selectedColor();
		s.quickSelect = config.quickSelect();
		s.panelOpen = plugin.isPanelOpen(v.getW());
		s.panelScroll = plugin.getPanelScroll();
		net.runelite.api.Point mp = client.getMouseCanvasPosition();
		boolean mapInput = plugin.isMapInput(mp.getX(), mp.getY());
		// while a right-click menu is open the card keeps describing the tree it was opened on
		if (!client.isMenuOpen())
		{
			Hit hover = mapInput ? plugin.hitAt(mp.getX(), mp.getY()) : null;
			// a marker, or a quick-select row: the card and the marker's hover ring follow both
			s.hovered = hover != null && (hover.getKind() == Hit.Kind.MARKER || hover.getKind() == Hit.Kind.ROW) ? hover.getTree() : null;
		}
		s.mouse = mapInput ? new Point(mp.getX(), mp.getY()) : null;
		s.hits.clear();
		s.card = null;
	}

	/** Client thread: the open quick-select panel's width on a map this wide (its labels set it), for fits. */
	int panelWidth(int mapWidth)
	{
		TreeRepository repo = plugin.getRepo();
		return chrome.panelWidth(repo.menuOrder(), repo.getHere(), repo.getLast(), mapWidth);
	}

	/** List mode: the plain menu works as the game made it; only the Map button is ours. */
	private void renderListMode(Graphics2D g, TreeMenu menu)
	{
		Point a = menu.anchor();
		if (a == null)
		{
			plugin.publish(Collections.emptyList(), Collections.emptyList(), null, null, 0);
			return;
		}
		Rectangle b = chrome.mapButton(a);
		net.runelite.api.Point mp = client.getMouseCanvasPosition();
		chrome.paintMapButton(g, b, !client.isMenuOpen() && b.contains(mp.getX(), mp.getY()));
		plugin.publish(Collections.emptyList(), Collections.emptyList(), b, null, 0);
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
}
