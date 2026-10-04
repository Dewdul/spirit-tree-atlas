/*
 * Copyright (c) 2026, Dewdul
 * All rights reserved.
 * Licensed under the BSD 2-Clause License. See LICENSE.
 */
package com.spirittreeatlas;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.Value;

/**
 * The map's chrome in an OSRS style: dark stone frame, the top bar with breadcrumb, dial readout
 * and buttons, the side panel (Favourites, then Elsewhere: the off-surface layers), the Groups panel on
 * the right, the info card and the frames around the holes left for the real Teleport and close
 * buttons. Needs no game client.
 */
public class ChromePainter
{
	static final int BAR_H = 22;
	private static final int BUTTON_H = 16;
	static final int PANEL_W = 150;
	/** Width of the open Groups panel on a roomy map; see {@link #groupsWidth}. */
	static final int GROUPS_W = 190;
	static final int CARD_W = 340;
	/** Width of the default, compact card. */
	static final int CARD_W_COMPACT = 280;
	static final int CARD_MAX_H = 260;
	/** Menu target of the panel's show/hide toggle. */
	private static final String PANEL_NAME = "Favourites and Elsewhere";
	private static final String GROUPS_NAME = "Groups";
	/** Height of the closed Groups panel's tab, which has its name written down it. */
	private static final int GROUPS_TAB_H = 66;

	static final Color FRAME = new Color(0x1e1a14);
	static final Color BRONZE = new Color(0x6b5a40);
	static final Color BRONZE_LIGHT = new Color(0x9a8360);
	static final Color FILL = new Color(24, 20, 14, 238);
	static final Color FILL_LIGHT = new Color(52, 44, 32, 235);
	static final Color TITLE = new Color(0xFF981F);
	static final Color CREAM = new Color(0xF0E2C0);
	static final Color GREY = new Color(0xA79D8B);
	static final Color AMBER = new Color(0xFFB347);
	static final Color DANGER = new Color(0xFF5A4E);
	static final Color GOOD = new Color(0x6BE36B);
	static final Color HINT = new Color(0xFFE36B);
	/** A locked ring a visit cannot unlock yet: it needs something first. */
	static final Color BLOCKED = new Color(0xFF7A5C);

	private final Ink ink;

	public ChromePainter(Ink ink)
	{
		this.ink = ink;
	}

	/** Places the top bar and panel so the map painter can keep labels clear of them. */
	public void layout(Scene s)
	{
		MapView v = s.view;
		// the bar and, when shown, the notice strip under it
		s.topBar = new Rectangle(v.getX(), v.getY(), v.getW(), BAR_H + (s.notice != null ? 18 : 0));
		// the card painted last; the map painter keeps labels from hiding half under it
		s.card = layerCard != null && v.rect().contains(layerCard) ? layerCard : null;
		int top = v.getY() + BAR_H + (s.notice != null ? 18 : 0) + 6;
		if (cards(s).isEmpty() && s.repo.favouriteRings().isEmpty())
		{
			s.panel = null;
		}
		else if (s.panelCollapsed)
		{
			s.panel = new Rectangle(v.getX() + 6, top, 20, 60);
		}
		else
		{
			s.panel = new Rectangle(v.getX() + 6, top, PANEL_W, v.getY() + v.getH() - 6 - top);
		}
		s.groupsPanel = groupsRect(s, top);
		Layer layer = s.repo.layer(v.getLayer());
		s.backButton = null;
		if (layer != null && !layer.isSurface())
		{
			int left = s.panel != null ? s.panel.x + s.panel.width + 8 : v.getX() + 8;
			int w = ink.width(ink.bold, backLabel(s)) + 30;
			s.backButton = new Rectangle(left, top, w, ink.height(ink.bold) + 12);
		}
	}

	private static String backLabel(Scene s)
	{
		return "Back to " + s.repo.surface().getName();
	}

	/**
	 * Paints the chrome. It is drawn into a cached translucent layer that is rebuilt only when
	 * something it shows changes, so a steady frame costs one blit.
	 */
	public void paint(Graphics2D g, Scene s)
	{
		MapView v = s.view;
		long key = key(s);
		if (layer == null || layer.getWidth() != v.getW() || layer.getHeight() != v.getH() || key != layerKey)
		{
			if (layer == null || layer.getWidth() != v.getW() || layer.getHeight() != v.getH())
			{
				layer = new BufferedImage(v.getW(), v.getH(), BufferedImage.TYPE_INT_ARGB);
			}
			Graphics2D lg = layer.createGraphics();
			lg.setComposite(AlphaComposite.Clear);
			lg.fillRect(0, 0, v.getW(), v.getH());
			lg.setComposite(AlphaComposite.SrcOver);
			lg.translate(-v.getX(), -v.getY());
			lg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			int mark = s.hits.size();
			drop = null;
			paintPanel(lg, s);
			paintGroups(lg, s);
			paintTopBar(lg, s);
			paintBackButton(lg, s);
			paintCard(lg, s);
			paintTeleportStandIn(lg, s);
			paintFrame(lg, s);
			lg.dispose();
			layerHits.clear();
			layerHits.addAll(s.hits.subList(mark, s.hits.size()));
			layerCard = s.card;
			layerKey = key;
		}
		else
		{
			s.hits.addAll(layerHits);
			s.card = layerCard;
		}
		g.drawImage(layer, v.getX(), v.getY(), null);
	}

	private long key(Scene s)
	{
		MapView v = s.view;
		Object hover = null;
		if (s.mouse != null)
		{
			for (Hit h : layerHits)
			{
				if (h.isActionable() && h.getArea().contains(s.mouse))
				{
					hover = h;
				}
			}
		}
		boolean flash = s.now < s.flashUntil && (s.now / 250) % 2 == 0;
		return java.util.Objects.hash(v.getX(), v.getY(), v.getW(), v.getH(), v.getLayer(), s.selected, s.hovered,
			java.util.Arrays.hashCode(s.dials), s.query, s.rowVisible, s.notice, s.panelCollapsed, s.panelScroll,
			s.faveOrder, s.dragList, s.dragCode, s.dragCode == null ? 0 : s.dragY, s.groupsOpen, s.groups, s.groupsScroll, s.hoveredNote, s.hoveredDetails,
			s.now < s.flashUntil, flash, hover, s.holes, s.teleportSlot, s.repo.getStateHash(), s.here,
			s.visitedColor, s.selectedColor, s.favouriteColor, s.fullDetails, focusCovered(s));
	}

	private BufferedImage layer;
	private long layerKey;
	private final List<Hit> layerHits = new ArrayList<>();
	private Rectangle layerCard;

	/** Drops the map-sized layer while the interface is closed; it is rebuilt on the next paint. */
	public void release()
	{
		layer = null;
		layerHits.clear();
		layerCard = null;
		layerKey = 0;
		drop = null;
	}

	// ------------------------------------------------------------------ top bar

	private void paintTopBar(Graphics2D g, Scene s)
	{
		Rectangle bar = new Rectangle(s.topBar.x, s.topBar.y, s.topBar.width, BAR_H);
		g.setColor(new Color(16, 13, 9, 215));
		g.fillRect(bar.x, bar.y, bar.width, bar.height);
		g.setColor(BRONZE);
		g.fillRect(bar.x, bar.y + bar.height - 1, bar.width, 1);
		s.hits.add(new Hit(Hit.Kind.BLOCK, bar, null, null, null, null));

		int right = bar.x + bar.width - 6;
		for (Rectangle h : s.holes)
		{
			if (h.intersects(bar))
			{
				right = Math.min(right, h.x - 6);
			}
		}
		int by = bar.y + (bar.height - BUTTON_H) / 2;
		int ty = bar.y + (bar.height - ink.height(ink.small)) / 2;
		String[] ids = {Hit.DIALS, Hit.FIT, Hit.ZOOM_IN, Hit.ZOOM_OUT, Hit.CLEAR};
		String[] labels = {"Dials", "Fit", "+", "-", "Clear"};
		String[] options = {"Show", "Fit", "Zoom in", "Zoom out", "Clear"};
		String[] targets = {"dials", "map", "", "", "selection"};
		for (int i = 0; i < ids.length; i++)
		{
			if (Hit.CLEAR.equals(ids[i]) && s.selected == null)
			{
				continue;
			}
			int w = Math.max(18, ink.width(ink.small, labels[i]) + 12);
			Rectangle b = new Rectangle(right - w, by, w, BUTTON_H);
			button(g, s, b, labels[i], ids[i], options[i], targets[i]);
			right = b.x - 3;
		}
		right -= 6;

		int x = bar.x + 6;
		Layer layer = s.repo.layer(s.view.getLayer());
		Layer surface = s.repo.surface();
		if (layer == null || layer.isSurface())
		{
			x = text(g, surface.getName(), ink.bold, TITLE, x, ty, right);
		}
		else
		{
			int w = ink.width(ink.small, surface.getName()) + 20;
			Rectangle b = new Rectangle(x, by, w, BUTTON_H);
			accentButton(g, s, b, surface.getName(), ink.small, Hit.BACK, "Back to", surface.getName());
			x = b.x + b.width + 6;
			triangle(g, x + 3, bar.y + bar.height / 2.0, 3.5, true, GREY);
			x = text(g, layer.getName(), ink.bold, TITLE, x + 10, ty, right);
		}

		x += 14;
		if (s.dials != null && x < right - 60)
		{
			if (right - x > 160)
			{
				x = text(g, "Dials:", ink.small, GREY, x, ty, right) + 4;
			}
			String code = s.dialledCode();
			x = text(g, DialMath.spaced(code), ink.bold, Color.WHITE, x, ty, right) + 5;
			Ring dialled = s.repo.ring(code);
			if (x < right - 20)
			{
				arrow(g, x, bar.y + bar.height / 2.0, 9, GREY);
				x += 14;
				boolean match = s.selected != null && s.selected.equals(code);
				x = text(g, dialled == null ? "nothing" : s.repo.displayName(dialled), ink.small,
					dialled == null ? GREY : match ? GOOD : CREAM, x, ty, right);
			}
		}
		if (s.searching() && x < right - 40)
		{
			int n = s.repo.matching(s.query).size();
			text(g, "Search: " + s.query + " (" + n + ")", ink.small, HINT, x + 14, ty, right);
		}

		if (s.notice != null)
		{
			Rectangle strip = new Rectangle(bar.x, bar.y + bar.height, bar.width, 18);
			g.setColor(new Color(90, 20, 14, 220));
			g.fill(strip);
			text(g, s.notice, ink.small, Color.WHITE, strip.x + 6, strip.y + (18 - ink.height(ink.small)) / 2, strip.x + strip.width - 6);
			s.hits.add(new Hit(Hit.Kind.BLOCK, strip, null, null, null, null));
		}
	}

	/** On an off-surface map: a large, unmissable way back to the world map. */
	private void paintBackButton(Graphics2D g, Scene s)
	{
		Rectangle b = s.backButton;
		if (b == null)
		{
			return;
		}
		g.setColor(new Color(0, 0, 0, 110));
		g.fillRoundRect(b.x + 2, b.y + 3, b.width, b.height, 10, 10);
		accentButton(g, s, b, backLabel(s), ink.bold, Hit.BACK, "Back to", s.repo.surface().getName());
	}

	/** A button in the highlight colour, with a back arrow: the way out of an off-surface map. */
	private void accentButton(Graphics2D g, Scene s, Rectangle b, String label, Font f, String id, String option, String target)
	{
		boolean hover = s.mouse != null && b.contains(s.mouse);
		g.setColor(FRAME);
		g.fillRoundRect(b.x - 1, b.y - 1, b.width + 2, b.height + 2, 8, 8);
		g.setColor(hover ? new Color(0xFFB04A) : new Color(0xE0841A));
		g.fillRoundRect(b.x, b.y, b.width, b.height, 7, 7);
		g.setColor(hover ? Color.WHITE : new Color(0xFFD08A));
		g.drawRoundRect(b.x, b.y, b.width - 1, b.height - 1, 7, 7);
		double cy = b.y + b.height / 2.0;
		double ar = Math.max(3.5, b.height * 0.2);
		triangle(g, b.x + 6 + ar, cy, ar, false, Ink.DARK);
		int tx = b.x + (int) (10 + ar * 2);
		ink.text(g, label, f, Ink.DARK, tx, b.y + (b.height - ink.height(f)) / 2, Ink.Style.PLAIN);
		s.hits.add(new Hit(Hit.Kind.BUTTON, b, null, id, option, target));
	}

	private void button(Graphics2D g, Scene s, Rectangle b, String label, String id, String option, String target)
	{
		boolean hover = s.mouse != null && b.contains(s.mouse);
		g.setColor(FRAME);
		g.fillRoundRect(b.x - 1, b.y - 1, b.width + 2, b.height + 2, 5, 5);
		g.setColor(hover ? new Color(0x5a4b36) : new Color(0x3a3125));
		g.fillRoundRect(b.x, b.y, b.width, b.height, 4, 4);
		g.setColor(hover ? BRONZE_LIGHT : BRONZE);
		g.drawRoundRect(b.x, b.y, b.width - 1, b.height - 1, 4, 4);
		int tw = ink.width(ink.small, label);
		int tx = Hit.BACK.equals(id) ? b.x + 13 : b.x + (b.width - tw) / 2;
		ink.text(g, label, ink.small, hover ? Color.WHITE : CREAM, tx, b.y + (b.height - ink.height(ink.small)) / 2, Ink.Style.SHADOW);
		s.hits.add(new Hit(Hit.Kind.BUTTON, b, null, id, option, target));
	}

	/** Draws text clipped to a right edge; returns the x after it. */
	private int text(Graphics2D g, String t, Font f, Color c, int x, int y, int right)
	{
		String fitted = ink.fit(t, f, right - x);
		if (fitted == null || fitted.isEmpty())
		{
			return x;
		}
		ink.text(g, fitted, f, c, x, y, Ink.Style.SHADOW);
		return x + ink.width(f, fitted);
	}

	// ------------------------------------------------------------------ Elsewhere panel

	/** A card: an off-surface layer (or the house) and its rings. */
	private static final class Card
	{
		final String id;
		final String name;
		final List<Ring> rings;

		Card(String id, String name, List<Ring> rings)
		{
			this.id = id;
			this.name = name;
			this.rings = rings;
		}
	}

	private List<Card> cards(Scene s)
	{
		List<Card> out = new ArrayList<>();
		for (Layer l : s.repo.getLayers())
		{
			if (l.isSurface())
			{
				continue;
			}
			List<Ring> rings = s.repo.ringsIn(l.getId());
			if (!rings.isEmpty())
			{
				out.add(new Card(l.getId(), l.getName(), rings));
			}
		}
		// the house's ring keeps its card even when it is drawn at the portal on the world map
		Ring house = s.repo.ring("DIQ");
		if (house != null)
		{
			String town = s.repo.getHouseTown();
			out.add(new Card(Layer.POH, town == null ? "Your house" : "Your house (" + town + ")", java.util.Collections.singletonList(house)));
		}
		return out;
	}

	private void paintPanel(Graphics2D g, Scene s)
	{
		Rectangle p = s.panel;
		if (p == null)
		{
			return;
		}
		panelBox(g, p);
		if (s.panelCollapsed)
		{
			s.hits.add(new Hit(Hit.Kind.BUTTON, p, null, Hit.TOGGLE_PANEL, "Show", PANEL_NAME));
			// the search matches a ring elsewhere: the tab says so (and flashes with the cards)
			boolean matches = false;
			for (Card c : cards(s))
			{
				for (Ring r : c.rings)
				{
					matches |= s.searching() && r.matches(s.query);
				}
			}
			boolean flash = s.now < s.flashUntil && (s.now / 250) % 2 == 0;
			if (matches)
			{
				g.setColor(flash ? Color.WHITE : HINT);
				g.setStroke(new BasicStroke(1.5f));
				g.drawRoundRect(p.x, p.y, p.width - 1, p.height - 1, 8, 8);
			}
			triangle(g, p.x + p.width / 2.0, p.y + 12, 4, true, CREAM);
			for (int i = 0; i < 3; i++)
			{
				g.setColor(matches ? HINT : i == 0 ? s.visitedColor : BRONZE_LIGHT);
				g.fill(AtlasPainter.circle(p.x + p.width / 2.0, p.y + 26 + i * 10, 2.5));
			}
			return;
		}
		s.hits.add(new Hit(Hit.Kind.PANEL, p, null, null, null, null));
		int sh = ink.height(ink.small);
		ink.text(g, "Favourites", ink.bold, TITLE, p.x + 6, p.y + 4, Ink.Style.SHADOW);
		Rectangle toggle = new Rectangle(p.x + p.width - 20, p.y + 3, 16, 15);
		button(g, s, toggle, "", Hit.TOGGLE_PANEL, "Hide", PANEL_NAME);
		triangle(g, toggle.x + 8, toggle.y + 7.5, 3.5, false, CREAM);

		Rectangle content = new Rectangle(p.x + 4, p.y + 22, p.width - 8, p.height - 26);
		Shape oldClip = g.getClip();
		g.clip(content);
		int y = paintFavourites(g, s, content, content.y - s.panelScroll);
		ink.text(g, "Elsewhere", ink.bold, TITLE, content.x + 2, y, Ink.Style.SHADOW);
		y += ink.height(ink.bold) + 4;
		Layer active = s.repo.layer(s.view.getLayer());
		boolean flash = s.now < s.flashUntil && (s.now / 250) % 2 == 0;
		Composite oldComp = g.getComposite();
		for (Card c : cards(s))
		{
			List<Rectangle> chips = new ArrayList<>();
			int cx = content.x + 5;
			int cy = y + 4 + sh + 2;
			for (Ring r : c.rings)
			{
				int w = chipWidth(s, r);
				if (cx + w > content.x + content.width - 4 && cx > content.x + 5)
				{
					cx = content.x + 5;
					cy += sh + 4;
				}
				chips.add(new Rectangle(cx, cy, w, sh + 2));
				cx += w + 3;
			}
			int h = cy + sh + 2 + 5 - y;
			Rectangle card = new Rectangle(content.x, y, content.width, h);
			boolean isActive = active != null && active.getId().equals(c.id);
			boolean matches = false;
			for (Ring r : c.rings)
			{
				matches |= s.searching() && r.matches(s.query);
			}
			if (s.searching() && !matches)
			{
				g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.4f));
			}
			g.setColor(isActive ? new Color(70, 52, 30, 240) : FILL_LIGHT);
			g.fillRoundRect(card.x, card.y, card.width, card.height, 6, 6);
			g.setColor(isActive ? TITLE : matches ? (flash ? Color.WHITE : HINT) : BRONZE);
			g.setStroke(new BasicStroke(isActive || matches ? 1.5f : 1f));
			g.drawRoundRect(card.x, card.y, card.width - 1, card.height - 1, 6, 6);
			ink.text(g, ink.fit(c.name, ink.small, card.width - 10), ink.small, CREAM, card.x + 5, card.y + 4, Ink.Style.SHADOW);
			Ring house = Layer.POH.equals(c.id) ? c.rings.get(0) : null;
			if (content.intersects(card))
			{
				if (house != null)
				{
					s.hits.add(new Hit(Hit.Kind.CHIP, clipTo(card, content), house, Hit.HOUSE, "Select", house.getCode() + " " + s.repo.displayName(house)));
				}
				else
				{
					// a click opens the area and selects its first usable ring
					Ring pick = s.repo.defaultRing(c.id);
					s.hits.add(pick == null ? new Hit(Hit.Kind.CARD, clipTo(card, content), null, c.id, "Open", c.name)
						: new Hit(Hit.Kind.CARD, clipTo(card, content), pick, c.id, "Select", pick.getCode() + " " + s.repo.displayName(pick)));
				}
			}
			for (int i = 0; i < c.rings.size(); i++)
			{
				Ring r = c.rings.get(i);
				Rectangle chip = chips.get(i);
				chip(g, s, r, chip);
				if (house == null && content.intersects(chip))
				{
					s.hits.add(new Hit(Hit.Kind.CHIP, clipTo(chip, content), r, null, "Select", r.getCode() + " " + s.repo.displayName(r)));
				}
			}
			g.setComposite(oldComp);
			y += h + 5;
		}
		g.setClip(oldClip);
		int overflow = y + s.panelScroll - (content.y + content.height);
		if (s.panelScroll > 0)
		{
			chevron(g, p.x + p.width / 2.0, content.y + 3, -1);
		}
		if (overflow > s.panelScroll)
		{
			chevron(g, p.x + p.width / 2.0, content.y + content.height - 3, 1);
		}
		panelScrollMax = Math.max(0, overflow);
	}

	/**
	 * The Favourites list at the top of the panel, one row per favourite in the player's own
	 * order (dragging a row moves it). Returns the y below it.
	 */
	private int paintFavourites(Graphics2D g, Scene s, Rectangle content, int y)
	{
		int sh = ink.height(ink.small);
		List<Ring> faves = FavouriteOrder.sort(s.repo.favouriteRings(), s.faveOrder);
		if (faves.isEmpty())
		{
			for (String t : ink.wrap("Star a code in your travel log to list it here.", ink.small, content.width - 8))
			{
				ink.text(g, t, ink.small, GREY, content.x + 4, y + 2, Ink.Style.SHADOW);
				y += sh;
			}
			return y + 12;
		}
		return paintRows(g, s, faves, null, Hit.FAVE, content, y) + 8;
	}

	/**
	 * Ring rows (code chip and name) of a list the player can reorder by dragging: the Favourites
	 * or one group, whose rows show their labels (null: the ring names). Returns the y below them.
	 */
	private int paintRows(Graphics2D g, Scene s, List<Ring> rings, Map<String, String> labels, String list, Rectangle content, int y)
	{
		int sh = ink.height(ink.small);
		int top = y;
		Composite oldComp = g.getComposite();
		List<Rectangle> others = new ArrayList<>();
		List<Ring> otherRings = new ArrayList<>();
		Ring dragged = null;
		for (Ring r : rings)
		{
			Rectangle row = new Rectangle(content.x, y, content.width, sh + 6);
			boolean drag = s.dragging(list, r.getCode());
			boolean hover = s.dragCode == null && s.mouse != null && row.contains(s.mouse) && content.contains(s.mouse);
			String label = label(s, r, labels);
			if (drag || (s.searching() && !rowMatches(s, r, labels)))
			{
				g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, drag ? 0.3f : 0.4f));
			}
			paintRow(g, s, r, label, row, hover);
			g.setComposite(oldComp);
			if (drag)
			{
				dragged = r;
			}
			else
			{
				others.add(row);
				otherRings.add(r);
			}
			if (content.intersects(row))
			{
				s.hits.add(new Hit(Hit.Kind.CHIP, clipTo(row, content), r, list, "Select", r.getCode() + " " + label));
			}
			y += row.height + 2;
		}
		if (dragged != null)
		{
			// where the row would land, and the row itself under the pointer
			int at = dropIndex(others, s.dragY);
			drop = new Drop(list, dragged.getCode(), at < otherRings.size() ? otherRings.get(at).getCode() : null);
			int lineY = others.isEmpty() ? top : at < others.size() ? others.get(at).y - 2
				: others.get(others.size() - 1).y + others.get(others.size() - 1).height + 1;
			g.setColor(TITLE);
			g.fillRect(content.x + 2, lineY - 1, content.width - 4, 2);
			Rectangle ghost = new Rectangle(content.x, s.dragY - (sh + 6) / 2, content.width, sh + 6);
			g.setColor(new Color(0, 0, 0, 120));
			g.fillRoundRect(ghost.x + 2, ghost.y + 3, ghost.width, ghost.height, 6, 6);
			g.setColor(FILL_LIGHT);
			g.fillRoundRect(ghost.x, ghost.y, ghost.width, ghost.height, 6, 6);
			paintRow(g, s, dragged, label(s, dragged, labels), ghost, true);
		}
		return y;
	}

	/** Where a dragged row lands among the other rows of its list: before the first row below the pointer. */
	private static int dropIndex(List<Rectangle> rows, int y)
	{
		int at = 0;
		for (Rectangle r : rows)
		{
			if (y > r.y + r.height / 2)
			{
				at++;
			}
		}
		return at;
	}

	/** A row's label: its group's label for the ring, else the ring's name (the house's with its town). */
	private static String label(Scene s, Ring r, Map<String, String> labels)
	{
		String l = labels == null ? null : labels.get(r.getCode());
		return l != null ? l : s.repo.displayName(r);
	}

	/**
	 * Whether the search matches a row, by the travel log's rules ({@link Ring#matches}): up to
	 * three letters match the code only; longer searches also count the words of the row's group label.
	 */
	private static boolean rowMatches(Scene s, Ring r, Map<String, String> labels)
	{
		return r.matches(s.query, labels == null ? null : labels.get(r.getCode()));
	}

	private void paintRow(Graphics2D g, Scene s, Ring r, String label, Rectangle row, boolean hover)
	{
		int sh = ink.height(ink.small);
		boolean sel = r.getCode().equals(s.selected);
		if (sel || hover)
		{
			g.setColor(sel ? new Color(70, 52, 30, 240) : FILL_LIGHT);
			g.fillRoundRect(row.x, row.y, row.width, row.height, 6, 6);
			g.setColor(sel ? TITLE : BRONZE_LIGHT);
			g.setStroke(new BasicStroke(1f));
			g.drawRoundRect(row.x, row.y, row.width - 1, row.height - 1, 6, 6);
		}
		Rectangle chip = new Rectangle(row.x + 3, row.y + 2, chipWidth(s, r), sh + 2);
		chip(g, s, r, chip);
		int tx = chip.x + chip.width + 5;
		ink.text(g, ink.fit(label, ink.small, row.x + row.width - 3 - tx), ink.small, sel ? TITLE : CREAM, tx, row.y + 3, Ink.Style.SHADOW);
	}

	/** Largest useful panel scroll of the last paint, for the input handler to clamp to. */
	volatile int panelScrollMax;
	volatile int groupsScrollMax;

	/**
	 * Where the dragged row lands as last painted, so the drop is where the line showed (the
	 * line counts every row of the list, the hits only the ones in view); null with no drag.
	 */
	volatile Drop drop;

	/** A dragged row: its list and ring, and the ring it goes in front of (null: the end). */
	@Value
	static class Drop
	{
		String list;
		String code;
		String before;
	}

	// ------------------------------------------------------------------ Groups panel

	/**
	 * The open Groups panel's width on a map this wide: {@link #GROUPS_W}, so most row labels fit,
	 * but at most 30% of the map (labels are cut short instead) and never narrower than the left panel.
	 */
	static int groupsWidth(int mapWidth)
	{
		return Math.max(PANEL_W, Math.min(GROUPS_W, mapWidth * 30 / 100));
	}

	/** The Groups panel against the map's right edge, stopping above Teleport and close; or its tab. */
	private static Rectangle groupsRect(Scene s, int top)
	{
		MapView v = s.view;
		int right = v.getX() + v.getW() - 6;
		int w = groupsWidth(v.getW());
		if (!s.groupsOpen)
		{
			return new Rectangle(right - 20, top, 20, GROUPS_TAB_H);
		}
		int bottom = v.getY() + v.getH() - 6;
		for (Rectangle b : s.blockers())
		{
			// with room for the holes' frames and shadow
			Rectangle gb = AtlasPainter.grow(b, 8);
			if (gb.x < right && gb.x + gb.width > right - w && gb.y < bottom && gb.y + gb.height > top)
			{
				bottom = Math.min(bottom, gb.y);
			}
		}
		return new Rectangle(right - w, top, w, Math.max(GROUPS_TAB_H, bottom - top));
	}

	/** A group's rings, in its order, leaving out codes the data does not know. */
	private static List<Ring> rings(Scene s, RingGroups.Group group)
	{
		List<Ring> out = new ArrayList<>();
		for (String code : group.getCodes())
		{
			Ring r = s.repo.ring(code);
			if (r != null)
			{
				out.add(r);
			}
		}
		return out;
	}

	private static boolean groupMatches(Scene s, RingGroups.Group group)
	{
		for (Ring r : rings(s, group))
		{
			if (rowMatches(s, r, group.getLabels()))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * The Groups panel: a header per group (collapse triangle, name, count) over its ring rows.
	 * Closed, it is a slim tab with its name written down it.
	 */
	private void paintGroups(Graphics2D g, Scene s)
	{
		Rectangle p = s.groupsPanel;
		if (p == null)
		{
			return;
		}
		panelBox(g, p);
		boolean matches = false;
		for (RingGroups.Group gr : s.groups)
		{
			matches |= s.searching() && groupMatches(s, gr);
		}
		if (!s.groupsOpen)
		{
			groupsScrollMax = 0;
			s.hits.add(new Hit(Hit.Kind.BUTTON, p, null, Hit.TOGGLE_GROUPS, "Show", GROUPS_NAME));
			if (matches)
			{
				g.setColor(HINT);
				g.setStroke(new BasicStroke(1.5f));
				g.drawRoundRect(p.x, p.y, p.width - 1, p.height - 1, 8, 8);
			}
			triangle(g, p.x + p.width / 2.0, p.y + 12, 4, false, CREAM);
			Graphics2D tg = (Graphics2D) g.create();
			tg.translate(p.x + (p.width + ink.height(ink.small)) / 2, p.y + 22);
			tg.rotate(Math.PI / 2);
			ink.text(tg, GROUPS_NAME, ink.small, matches ? HINT : CREAM, 0, 0, Ink.Style.SHADOW);
			tg.dispose();
			return;
		}
		s.hits.add(new Hit(Hit.Kind.PANEL, p, null, null, null, null));
		int sh = ink.height(ink.small);
		Rectangle toggle = new Rectangle(p.x + 4, p.y + 3, 16, 15);
		button(g, s, toggle, "", Hit.TOGGLE_GROUPS, "Hide", GROUPS_NAME);
		triangle(g, toggle.x + 8, toggle.y + 7.5, 3.5, true, CREAM);
		ink.text(g, GROUPS_NAME, ink.bold, TITLE, toggle.x + toggle.width + 5, p.y + 4, Ink.Style.SHADOW);
		button(g, s, new Rectangle(p.x + p.width - 20, p.y + 3, 16, 15), "+", Hit.NEW_GROUP, "New group", "");

		Rectangle content = new Rectangle(p.x + 4, p.y + 22, p.width - 8, p.height - 26);
		Shape oldClip = g.getClip();
		g.clip(content);
		Composite oldComp = g.getComposite();
		int y = content.y - s.groupsScroll;
		if (s.groups.isEmpty())
		{
			for (String t : ink.wrap("Click + for a new group, or right-click a ring: Add to group.", ink.small, content.width - 8))
			{
				ink.text(g, t, ink.small, GREY, content.x + 4, y + 2, Ink.Style.SHADOW);
				y += sh;
			}
		}
		for (RingGroups.Group gr : s.groups)
		{
			List<Ring> rings = rings(s, gr);
			Rectangle head = new Rectangle(content.x, y, content.width, sh + 6);
			boolean hover = s.dragCode == null && s.mouse != null && head.contains(s.mouse) && content.contains(s.mouse);
			if (s.searching() && !groupMatches(s, gr))
			{
				g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.4f));
			}
			g.setColor(hover ? new Color(0x5a4b36) : FILL_LIGHT);
			g.fillRoundRect(head.x, head.y, head.width, head.height, 6, 6);
			g.setColor(hover ? BRONZE_LIGHT : BRONZE);
			g.setStroke(new BasicStroke(1f));
			g.drawRoundRect(head.x, head.y, head.width - 1, head.height - 1, 6, 6);
			double cy = head.y + head.height / 2.0;
			if (gr.isCollapsed())
			{
				triangle(g, head.x + 8, cy, 3.5, true, CREAM);
			}
			else
			{
				chevron(g, head.x + 8, cy, 1);
			}
			String count = String.valueOf(rings.size());
			int cw = ink.width(ink.small, count);
			ink.text(g, count, ink.small, GREY, head.x + head.width - 5 - cw, head.y + 3, Ink.Style.SHADOW);
			ink.text(g, ink.fit(gr.getName(), ink.small, head.width - 25 - cw), ink.small, TITLE, head.x + 15, head.y + 3, Ink.Style.SHADOW);
			g.setComposite(oldComp);
			if (content.intersects(head))
			{
				s.hits.add(new Hit(Hit.Kind.GROUP, clipTo(head, content), null, gr.getId(), gr.isCollapsed() ? "Expand" : "Collapse", gr.getName()));
			}
			y += head.height + 2;
			if (gr.isCollapsed())
			{
				y += 3;
				continue;
			}
			if (rings.isEmpty())
			{
				for (String t : ink.wrap("Right-click a ring: Add to group.", ink.small, content.width - 8))
				{
					ink.text(g, t, ink.small, GREY, content.x + 4, y + 1, Ink.Style.SHADOW);
					y += sh;
				}
			}
			y = paintRows(g, s, rings, gr.getLabels(), Hit.groupRow(gr.getId()), content, y) + 6;
		}
		g.setClip(oldClip);
		int overflow = y + s.groupsScroll - (content.y + content.height);
		if (s.groupsScroll > 0)
		{
			chevron(g, p.x + p.width / 2.0, content.y + 3, -1);
		}
		if (overflow > s.groupsScroll)
		{
			chevron(g, p.x + p.width / 2.0, content.y + content.height - 3, 1);
		}
		groupsScrollMax = Math.max(0, overflow);
	}

	/** A code chip is the code, plus a padlock in front when the ring is not unlocked yet. */
	private int chipWidth(Scene s, Ring r)
	{
		return ink.width(ink.small, r.getCode()) + 8 + (s.repo.isVisited(r.getCode()) ? 0 : 8);
	}

	private void chip(Graphics2D g, Scene s, Ring r, Rectangle c)
	{
		String code = r.getCode();
		boolean sel = code.equals(s.selected);
		boolean visited = s.repo.isVisited(code);
		boolean fave = s.repo.isFavourite(code);
		Color fill = sel ? s.selectedColor : visited ? AtlasPainter.withAlpha(s.visitedColor, 200) : new Color(0x4a4f4d);
		g.setColor(fill);
		g.fillRoundRect(c.x, c.y, c.width, c.height, 5, 5);
		if (fave)
		{
			g.setColor(s.favouriteColor);
			g.setStroke(new BasicStroke(1.4f));
			g.drawRoundRect(c.x, c.y, c.width - 1, c.height - 1, 5, 5);
		}
		boolean hover = s.mouse != null && c.contains(s.mouse);
		if (hover)
		{
			g.setColor(Color.WHITE);
			g.setStroke(new BasicStroke(1f));
			g.drawRoundRect(c.x - 1, c.y - 1, c.width + 1, c.height + 1, 6, 6);
		}
		Color text = sel || visited ? Ink.DARK : new Color(0xC9CFCD);
		int tx = c.x + 4;
		if (!visited)
		{
			AtlasPainter.drawPadlock(g, c.x + 7, c.y + c.height / 2.0, 5, sel ? Ink.DARK : AtlasPainter.LOCK);
			tx += 8;
		}
		ink.text(g, code, ink.small, text, tx, c.y + 1, Ink.Style.PLAIN);
	}

	private void panelBox(Graphics2D g, Rectangle p)
	{
		g.setColor(new Color(0, 0, 0, 70));
		g.fillRoundRect(p.x + 2, p.y + 2, p.width, p.height, 8, 8);
		g.setColor(FILL);
		g.fillRoundRect(p.x, p.y, p.width, p.height, 8, 8);
		g.setColor(BRONZE);
		g.setStroke(new BasicStroke(1f));
		g.drawRoundRect(p.x, p.y, p.width - 1, p.height - 1, 8, 8);
	}

	// ------------------------------------------------------------------ info card

	/** One laid-out card line. */
	private static final class Line
	{
		final String text;
		final Font font;
		final Color color;
		final int gapBefore;

		Line(String text, Font font, Color color, int gapBefore)
		{
			this.text = text;
			this.font = font;
			this.color = color;
			this.gapBefore = gapBefore;
		}
	}

	private void paintCard(Graphics2D g, Scene s)
	{
		Ring ring = s.hovered != null ? s.hovered : s.repo.ring(s.selected);
		MapView v = s.view;
		int left = s.panel != null ? s.panel.x + s.panel.width + 6 : v.getX() + 6;
		// an open Groups panel takes the right side; the closed tab is only kept clear of
		int right = s.groupsOpen && s.groupsPanel != null ? s.groupsPanel.x - 6 : v.getX() + v.getW() - 6;
		int maxW = Math.min(Math.min(s.fullDetails ? CARD_W : CARD_W_COMPACT, Math.max(220, v.getW() * 45 / 100)),
			right - left);
		// on a small (fixed mode) map the card keeps to under half the height so the map stays usable
		int maxH = Math.min(CARD_MAX_H, v.getH() * (v.getH() < 450 ? 48 : 62) / 100);
		java.awt.Point focus = cardFocus(s);
		if (ring == null)
		{
			String hint = "Click a fairy ring to plan your trip";
			int lh = ink.height(ink.small);
			int w = Math.max(ink.width(ink.small, hint), 22 + ink.width(ink.small, "Unlocked") + 26 + ink.width(ink.small, "Locked")) + 12;
			int hh = lh * 2 + 12;
			Rectangle r = place(s, left, right, w, hh, hh, null);
			// like the card, the hint keeps labels from under it and absorbs presses
			s.card = r;
			if (r != null)
			{
				s.hits.add(new Hit(Hit.Kind.BLOCK, r, null, null, null, null));
				panelBox(g, r);
				ink.text(g, hint, ink.small, GREY, r.x + 6, r.y + 4, Ink.Style.SHADOW);
				// legend: unlocked (in the travel log) and locked rings
				int ly = r.y + 8 + lh;
				double cy = ly + lh / 2.0;
				g.setColor(s.visitedColor);
				g.setStroke(new BasicStroke(1.6f));
				g.draw(AtlasPainter.circle(r.x + 12, cy, 4.5));
				int x = r.x + 22;
				ink.text(g, "Unlocked", ink.small, s.visitedColor, x, ly, Ink.Style.SHADOW);
				x += ink.width(ink.small, "Unlocked") + 12;
				AtlasPainter.drawPadlock(g, x + 4, cy, 6, AtlasPainter.LOCK);
				ink.text(g, "Locked", ink.small, AMBER, x + 14, ly, Ink.Style.SHADOW);
			}
			return;
		}
		int pad = s.fullDetails ? 8 : 6;
		int textW = maxW - pad * 2;
		boolean isSelected = ring.getCode() != null && ring.getCode().equals(s.selected);
		boolean visited = !ring.isDialable() || s.repo.isVisited(ring.getCode());
		boolean full = s.fullDetails;

		List<Line> lines = new ArrayList<>();
		String title = (ring.getCode() != null && ring.isDialable() ? ring.getCode() + "  " : "") + s.repo.displayName(ring);
		if (full)
		{
			for (String t : ink.wrap(title, ink.bold, textW))
			{
				lines.add(new Line(t, ink.bold, TITLE, 0));
			}
			if (ring.getArea() != null)
			{
				lines.add(new Line(ink.fit(ring.getArea(), ink.small, textW), ink.small, GREY, 0));
			}
		}
		else
		{
			lines.add(new Line(ink.fit(title, ink.bold, textW), ink.bold, TITLE, 0));
		}
		UnlockCheck.Result unlock = s.repo.unlock(ring);
		boolean blocked = !visited && unlock.getStatus() == UnlockCheck.Status.NOT_MET;
		if (ring.isDialable())
		{
			boolean house = "DIQ".equals(ring.getCode());
			StringBuilder status = new StringBuilder(visited ? (full ? "Unlocked - in your travel log" : "Unlocked")
				: house ? "Not used yet" : "Locked");
			if (s.repo.isFavourite(ring.getCode()))
			{
				status.append(" - favourite");
			}
			if (ring.getCode().equals(s.repo.getLastCode()))
			{
				status.append(full ? " - last destination" : " - last used");
			}
			if (ring.getCode().equals(s.here))
			{
				status.append(" - you are here");
			}
			if (!visited)
			{
				// what unlocking it takes: the first unmet need, a need the client cannot check, or
				// nothing more than a first visit
				status.append(unlock.getLabel() != null ? " - needs " + unlock.getLabel() : " - a first visit unlocks it");
			}
			Color statusColor = visited ? s.visitedColor : blocked ? BLOCKED : AMBER;
			if (full)
			{
				addWrapped(lines, status.toString(), ink.small, statusColor, textW, 2);
			}
			else
			{
				lines.add(new Line(ink.fit(status.toString(), ink.small, textW), ink.small, statusColor, 0));
			}
		}
		if (ring == s.hovered && (s.hoveredNote != null || s.hoveredDetails != null))
		{
			// hovered in a group: why the group has it, then what is there (patches, monsters) in at most
			// two lines; the compact card shows only what is there when it has both, so it grows by a line
			// at most
			if (s.hoveredNote != null && (full || s.hoveredDetails == null))
			{
				if (full)
				{
					addWrapped(lines, s.hoveredNote, ink.small, HINT, textW, 0);
				}
				else
				{
					lines.add(new Line(ink.fit(s.hoveredNote, ink.small, textW), ink.small, HINT, 0));
				}
			}
			if (s.hoveredDetails != null)
			{
				addWrapped(lines, s.hoveredDetails, ink.small, HINT, textW, 0, 2);
			}
		}
		if (full)
		{
			addWrapped(lines, ring.getDescription(), ink.regular, Color.WHITE, textW, 4);
			boolean first = true;
			for (String req : ring.getRequirements())
			{
				addWrapped(lines, "- " + req, ink.small, visited ? GREY : AMBER, textW, first ? 4 : 0);
				first = false;
			}
			if (ring.isNoStaffReturn() && !s.repo.isStaffless())
			{
				// the game's no_staff_return flag: this ring works without a staff, but only to Zanaris
				addWrapped(lines, "No staff needed to leave: this ring can take you back to Zanaris.", ink.small, GREY, textW, 4);
			}
			first = true;
			for (String d : ring.getDanger())
			{
				addWrapped(lines, "! " + d, ink.small, DANGER, textW, first ? 4 : 0);
				first = false;
			}
			// places come after what the player must know before going, so a short card cuts them first
			List<String> poi = ring.getPoi();
			if (!poi.isEmpty())
			{
				addWrapped(lines, "Nearby: " + String.join(", ", poi.subList(0, Math.min(6, poi.size()))), ink.small, CREAM, textW, 4);
			}
			List<String> notes = ring.getNotes();
			for (int i = 0; i < Math.min(2, notes.size()); i++)
			{
				addWrapped(lines, notes.get(i), ink.small, new Color(0xB8C4D6), textW, i == 0 ? 4 : 0);
			}
		}
		else
		{
			// the summary: what is there, what stops you (only until you have been) and the worst danger,
			// one line each
			List<String> poi = ring.getPoi();
			if (!poi.isEmpty())
			{
				lines.add(new Line(ink.fit("Near: " + String.join(", ", poi.subList(0, Math.min(3, poi.size()))), ink.small, textW),
					ink.small, CREAM, 3));
			}
			if (!visited)
			{
				List<String> reqs = ring.getRequirements();
				for (int i = 0; i < Math.min(2, reqs.size()); i++)
				{
					lines.add(new Line(ink.fit("- " + reqs.get(i), ink.small, textW), ink.small, AMBER, 0));
				}
			}
			if (!ring.getDanger().isEmpty())
			{
				lines.add(new Line(ink.fit("! " + ring.getDanger().get(0), ink.small, textW), ink.small, DANGER, 0));
			}
		}

		// the next step for the selected ring
		Line step = null;
		boolean dialRow = false;
		if (isSelected && ring.isDialable())
		{
			String code = ring.getCode();
			if (s.dialsMatch(code))
			{
				step = new Line("Ready: click Teleport", ink.bold, GOOD, 6);
			}
			else if (s.repo.hasLogRow(code))
			{
				step = new Line(s.rowVisible ? "Click the highlighted code in your travel log, then Teleport."
					: s.searching() ? "Clear the travel log search to show this code."
					: "Use this code in your travel log, then Teleport.", ink.small, HINT, 6);
			}
			else if (blocked)
			{
				// a visit cannot unlock it yet, so there is nothing to dial
				step = new Line("Not unlocked yet: needs " + unlock.getLabel() + " first.", ink.small, BLOCKED, 6);
			}
			else
			{
				// the house's ring is only there once built in the garden; otherwise the code goes nowhere
				step = new Line("DIQ".equals(code) ? "Build a fairy ring in your house's garden first; then dial DIQ by hand:"
					: unlock.getLabel() != null ? "If you have " + unlock.getLabel() + ", a first visit unlocks it: dial it by hand (orange button), then Teleport:"
					: "A first visit unlocks it: dial it by hand (orange button), then Teleport:", ink.small, HINT, 6);
				dialRow = s.dials != null;
			}
		}
		int stepH = 0;
		List<String> stepLines = new ArrayList<>();
		if (step != null)
		{
			stepLines = ink.wrap(step.text, step.font, textW);
			stepH = step.gapBefore + stepLines.size() * ink.height(step.font) + (dialRow ? 24 : 0);
		}

		int h = pad * 2 + stepH;
		int cut = lines.size();
		for (int i = 0; i < lines.size(); i++)
		{
			Line l = lines.get(i);
			int lh = l.gapBefore + ink.height(l.font);
			if (h + lh > maxH)
			{
				cut = i;
				break;
			}
			h += lh;
		}
		if (cut < lines.size() && cut > 0)
		{
			// the last line shown ends in "..." so the cut is visible
			Line last = lines.get(cut - 1);
			String more = last.text.endsWith(".") ? last.text.substring(0, last.text.length() - 1) : last.text;
			lines.set(cut - 1, new Line(ink.fit(more + " ...", last.font, textW).replace(" ...", "..."), last.font, last.color, last.gapBefore));
		}
		Rectangle card = place(s, left, right, maxW, h, Math.min(h, 120), focus);
		if (card == null)
		{
			s.card = null;
			return;
		}
		s.card = card;
		s.hits.add(new Hit(Hit.Kind.BLOCK, card, null, null, null, null));
		panelBox(g, card);
		g.setColor(TITLE);
		g.fillRect(card.x + 1, card.y + 4, 2, card.height - 8);

		int y = card.y + pad;
		int bottom = card.y + card.height - pad - stepH;
		for (int i = 0; i < cut; i++)
		{
			Line l = lines.get(i);
			int lh = ink.height(l.font);
			if (y + l.gapBefore + lh > bottom)
			{
				ink.text(g, "...", ink.small, GREY, card.x + pad, y, Ink.Style.SHADOW);
				break;
			}
			y += l.gapBefore;
			ink.text(g, l.text, l.font, l.color, card.x + pad, y, Ink.Style.SHADOW);
			y += lh;
		}

		if (step != null)
		{
			y = bottom + step.gapBefore;
			g.setColor(BRONZE);
			g.fillRect(card.x + pad, y - 3, card.width - pad * 2, 1);
			for (String t : stepLines)
			{
				ink.text(g, t, step.font, step.color, card.x + pad, y, Ink.Style.SHADOW);
				y += ink.height(step.font);
			}
			if (dialRow)
			{
				int x = dialTokens(g, card.x + pad, y + 2, DialMath.plan(s.dials, ring.getCode()), ring.getCode());
				String label = "Show dials";
				int bw = ink.width(ink.small, label) + 12;
				Rectangle b = new Rectangle(Math.max(x + 8, card.x + card.width - pad - bw), y + 3, bw, 17);
				button(g, s, b, label, Hit.DIALS, "Show", "dials");
			}
		}
	}

	private void addWrapped(List<Line> lines, String text, Font f, Color c, int w, int gap)
	{
		addWrapped(lines, text, f, c, w, gap, Integer.MAX_VALUE);
	}

	/** Wrapped to at most max lines; the last one ends in "..." when there is more. */
	private void addWrapped(List<Line> lines, String text, Font f, Color c, int w, int gap, int max)
	{
		List<String> wrapped = ink.wrap(text, f, w);
		for (int i = 0; i < Math.min(max, wrapped.size()); i++)
		{
			String t = wrapped.get(i);
			if (i == max - 1 && wrapped.size() > max)
			{
				t = ink.fit(t + " " + String.join(" ", wrapped.subList(max, wrapped.size())), f, w);
			}
			lines.add(new Line(t, f, c, i == 0 ? gap : 0));
		}
	}

	/**
	 * Draws the per-dial plan as tokens: the target letter, then a rotate arrow and a count or a
	 * check mark. Returns the x after the last token.
	 */
	int dialTokens(Graphics2D g, int x, int y, int[] plan, String code)
	{
		for (int d = 0; d < 3; d++)
		{
			String letter = String.valueOf(code.charAt(d));
			ink.text(g, letter, ink.bold, Color.WHITE, x, y + 2, Ink.Style.SHADOW);
			x += ink.width(ink.bold, letter) + 4;
			int k = plan[d];
			if (k == 0)
			{
				AtlasPainter.drawCheck(g, x + 6, y + 9, 4.5, GOOD);
				x += 14;
			}
			else
			{
				AtlasPainter.drawRotateArrow(g, x + 7, y + 10, 5.5, DialMath.clockwise(k), HINT, 1.6f);
				x += 17;
				String n = "x" + DialMath.clicks(k);
				ink.text(g, n, ink.small, HINT, x, y + 3, Ink.Style.SHADOW);
				x += ink.width(ink.small, n);
			}
			x += 10;
		}
		return x;
	}

	/** Whether the card drawn last now covers its own ring (after a pan), which forces a re-layout. */
	private boolean focusCovered(Scene s)
	{
		java.awt.Point f = cardFocus(s);
		return f != null && layerCard != null && AtlasPainter.grow(layerCard, 12).contains(f);
	}

	/** The screen point of the ring the card describes, which the card must not cover. */
	private java.awt.Point cardFocus(Scene s)
	{
		Ring r = s.hovered != null ? s.hovered : s.repo.ring(s.selected);
		MapView v = s.view;
		if (r == null || !r.isMapped() || !v.getLayer().equals(r.getLayer()))
		{
			return null;
		}
		return new java.awt.Point((int) v.screenX(r.getX() + 0.5), (int) v.screenY(r.getY() + 0.5));
	}

	/**
	 * Finds a spot for a card: bottom-left (beside the panel), else bottom-right, top-right or
	 * top-left, clear of the holes and of the ring it describes. Failing that, it slides a corner
	 * spot off the holes, shrinking to at least minH.
	 */
	private Rectangle place(Scene s, int left, int right, int w, int h, int minH, java.awt.Point focus)
	{
		MapView v = s.view;
		int top = v.getY() + BAR_H + (s.notice != null ? 18 : 0) + 6;
		int bottom = v.getY() + v.getH() - 6;
		if (w <= 40)
		{
			return null;
		}
		int[][] spots = {{left, 1}, {right - w, 1}, {right - w, 0}, {left, 0}};
		for (int[] spot : spots)
		{
			Rectangle r = new Rectangle(spot[0], spot[1] == 1 ? bottom - h : top, w, h);
			if (r.y >= top && clear(s, r, focus))
			{
				return r;
			}
		}
		Rectangle best = null;
		for (int pass = 0; pass < 2; pass++)
		{
			for (int[] spot : spots)
			{
				int x = spot[0];
				int lo = top;
				int hi = bottom;
				for (Rectangle hole : s.blockers())
				{
					Rectangle gh = AtlasPainter.grow(hole, 6);
					if (gh.x < x + w && gh.x + gh.width > x)
					{
						if (spot[1] == 1 && gh.y < hi && gh.y + gh.height > lo)
						{
							hi = Math.min(hi, gh.y);
						}
						else if (spot[1] == 0 && gh.y < lo + h && gh.y + gh.height > lo)
						{
							lo = Math.max(lo, gh.y + gh.height);
						}
					}
				}
				int hh = Math.min(h, hi - lo);
				Rectangle r = new Rectangle(x, spot[1] == 1 ? hi - hh : lo, w, hh);
				if (hh < minH || (pass == 0 && !clear(s, r, focus)))
				{
					continue;
				}
				if (hh == h)
				{
					return r;
				}
				if (best == null || hh > best.height)
				{
					best = r;
				}
			}
			if (best != null)
			{
				return best;
			}
		}
		return null;
	}

	private static boolean clear(Scene s, Rectangle r, java.awt.Point focus)
	{
		for (Rectangle hole : s.blockers())
		{
			if (AtlasPainter.grow(hole, 6).intersects(r))
			{
				return false;
			}
		}
		if (s.groupsPanel != null && AtlasPainter.grow(s.groupsPanel, 4).intersects(r))
		{
			return false;
		}
		return focus == null || !AtlasPainter.grow(r, 12).contains(focus);
	}

	// ------------------------------------------------------------------ frame and holes

	/** Teleport is covered until the dials are worth using; a disabled stand-in keeps its place. */
	private void paintTeleportStandIn(Graphics2D g, Scene s)
	{
		Rectangle slot = s.teleportSlot;
		if (slot == null || s.holes.contains(slot))
		{
			return;
		}
		g.setColor(new Color(0, 0, 0, 90));
		g.fillRect(slot.x - 2, slot.y + slot.height + 2, slot.width + 5, 3);
		g.fillRect(slot.x + slot.width + 2, slot.y - 1, 3, slot.height + 3);
		if (s.needsDialing())
		{
			// a locked ring has no travel log row: the next step is the dials, and this says so
			// where the eye goes for the next click; it is a button that shows them
			Ring r = s.repo.ring(s.selected);
			boolean hover = s.mouse != null && slot.contains(s.mouse);
			g.setColor(FRAME);
			g.fillRoundRect(slot.x - 1, slot.y - 1, slot.width + 2, slot.height + 2, 8, 8);
			g.setColor(hover ? new Color(0xFFB04A) : new Color(0xE0841A));
			g.fillRoundRect(slot.x, slot.y, slot.width, slot.height, 7, 7);
			g.setColor(hover ? Color.WHITE : new Color(0xFFD08A));
			g.drawRoundRect(slot.x, slot.y, slot.width - 1, slot.height - 1, 7, 7);
			String title = ink.fit("Dial " + r.getCode() + " by hand", ink.bold, slot.width - 8);
			String why = ink.fit("DIQ".equals(r.getCode()) ? "Needs a ring in your house" : "Click to show the dials", ink.small, slot.width - 8);
			int y = slot.y + (slot.height - ink.height(ink.bold) - ink.height(ink.small)) / 2;
			ink.text(g, title, ink.bold, Ink.DARK, slot.x + (slot.width - ink.width(ink.bold, title)) / 2, y, Ink.Style.PLAIN);
			ink.text(g, why, ink.small, Ink.DARK, slot.x + (slot.width - ink.width(ink.small, why)) / 2,
				y + ink.height(ink.bold), Ink.Style.PLAIN);
			s.hits.add(new Hit(Hit.Kind.BUTTON, AtlasPainter.grow(slot, 3), null, Hit.DIALS, "Show", "dials"));
			return;
		}
		g.setColor(new Color(0x2a241b));
		g.fillRect(slot.x, slot.y, slot.width, slot.height);
		g.setColor(FRAME);
		g.drawRect(slot.x - 1, slot.y - 1, slot.width + 1, slot.height + 1);
		g.setColor(BRONZE);
		g.drawRect(slot.x - 3, slot.y - 3, slot.width + 5, slot.height + 5);
		// it says where it would go and what sets the dials, so selecting a ring visibly changes it
		String[] t = s.teleportStandIn();
		String title = ink.fit(t[0], ink.bold, slot.width - 8);
		// a locked ring's need may take two lines
		List<String> why = ink.wrap(t[1], ink.small, slot.width - 8);
		if (why.size() > 2)
		{
			why = java.util.Arrays.asList(why.get(0), ink.fit(why.get(1) + " " + why.get(2), ink.small, slot.width - 8));
		}
		Color whyColor = s.selected == null ? GREY : s.unlockBlocked() ? BLOCKED : HINT;
		int y = slot.y + (slot.height - ink.height(ink.bold) - ink.height(ink.small) * why.size()) / 2;
		ink.text(g, title, ink.bold, GREY, slot.x + (slot.width - ink.width(ink.bold, title)) / 2, y, Ink.Style.SHADOW);
		y += ink.height(ink.bold);
		for (String w : why)
		{
			ink.text(g, w, ink.small, whyColor, slot.x + (slot.width - ink.width(ink.small, w)) / 2, y, Ink.Style.SHADOW);
			y += ink.height(ink.small);
		}
		s.hits.add(new Hit(Hit.Kind.BLOCK, AtlasPainter.grow(slot, 3), null, null, null, null));
	}

	private void paintFrame(Graphics2D g, Scene s)
	{
		MapView v = s.view;
		Rectangle m = v.rect();
		g.setStroke(new BasicStroke(1f));
		g.setColor(BRONZE);
		g.drawRect(m.x + 2, m.y + 2, m.width - 5, m.height - 5);
		g.setColor(FRAME);
		g.drawRect(m.x, m.y, m.width - 1, m.height - 1);
		g.drawRect(m.x + 1, m.y + 1, m.width - 3, m.height - 3);
	}

	/** Shadow outside the map; drawn before the map itself. */
	public void paintShadow(Graphics2D g, Scene s)
	{
		Rectangle m = s.view.rect();
		for (int i = 1; i <= 4; i++)
		{
			g.setColor(new Color(0, 0, 0, 70 - i * 15));
			g.drawRect(m.x - i, m.y - i, m.width - 1 + i * 2, m.height - 1 + i * 2);
		}
	}

	/** Frames the holes so the real buttons read as buttons on the map. */
	public void paintHoles(Graphics2D g, Scene s, boolean ready)
	{
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
		g.setStroke(new BasicStroke(1f));
		for (Rectangle h : s.holes)
		{
			g.setColor(new Color(0, 0, 0, 90));
			g.fillRect(h.x - 2, h.y + h.height + 2, h.width + 5, 3);
			g.fillRect(h.x + h.width + 2, h.y - 1, 3, h.height + 3);
			g.setColor(FRAME);
			g.drawRect(h.x - 1, h.y - 1, h.width + 1, h.height + 1);
			g.drawRect(h.x - 2, h.y - 2, h.width + 3, h.height + 3);
			g.setColor(BRONZE_LIGHT);
			g.drawRect(h.x - 3, h.y - 3, h.width + 5, h.height + 5);
			if (ready && h.equals(s.confirmHole))
			{
				double t = 0.5 + 0.5 * Math.sin(s.now / 160.0);
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g.setColor(AtlasPainter.withAlpha(GOOD, (int) (120 + 135 * t)));
				g.setStroke(new BasicStroke(2.5f));
				g.drawRoundRect(h.x - 6, h.y - 6, h.width + 11, h.height + 11, 6, 6);
				g.setStroke(new BasicStroke(1f));
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
			}
		}
		Rectangle slot = s.teleportSlot;
		if (slot != null && !s.holes.contains(slot) && s.needsDialing())
		{
			double t = 0.5 + 0.5 * Math.sin(s.now / 160.0);
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setColor(AtlasPainter.withAlpha(TITLE, (int) (110 + 145 * t)));
			g.setStroke(new BasicStroke(2.5f));
			g.drawRoundRect(slot.x - 6, slot.y - 6, slot.width + 11, slot.height + 11, 8, 8);
			g.setStroke(new BasicStroke(1f));
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
		}
	}

	// ------------------------------------------------------------------ glyphs

	static void triangle(Graphics2D g, double cx, double cy, double r, boolean pointRight, Color c)
	{
		Path2D p = new Path2D.Double();
		double d = pointRight ? 1 : -1;
		p.moveTo(cx + d * r, cy);
		p.lineTo(cx - d * r * 0.7, cy - r);
		p.lineTo(cx - d * r * 0.7, cy + r);
		p.closePath();
		g.setColor(c);
		g.fill(p);
	}

	/** A small up (dir -1) or down (dir 1) scroll hint. */
	private static void chevron(Graphics2D g, double cx, double cy, int dir)
	{
		Path2D p = new Path2D.Double();
		p.moveTo(cx, cy + 2 * dir);
		p.lineTo(cx + 4, cy - 2 * dir);
		p.lineTo(cx - 4, cy - 2 * dir);
		p.closePath();
		g.setColor(CREAM);
		g.fill(p);
	}

	/** A right-pointing arrow of the given length starting at (x, y). */
	static void arrow(Graphics2D g, double x, double y, double len, Color c)
	{
		g.setColor(c);
		g.setStroke(new BasicStroke(1.4f));
		g.draw(new java.awt.geom.Line2D.Double(x, y, x + len - 2, y));
		Path2D p = new Path2D.Double();
		p.moveTo(x + len, y);
		p.lineTo(x + len - 4, y - 3);
		p.lineTo(x + len - 4, y + 3);
		p.closePath();
		g.fill(p);
	}

	private static Rectangle clipTo(Rectangle r, Rectangle bounds)
	{
		return r.intersection(bounds);
	}
}
