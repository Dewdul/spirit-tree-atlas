/*
 * Copyright (c) 2026, Dewdul
 * All rights reserved.
 * Licensed under the BSD 2-Clause License. See LICENSE.
 */
package com.spirittreeatlas;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The map's chrome in an OSRS style (DESIGN 4.4, 4.6): dark stone frame, the top bar with
 * breadcrumb, last trip and buttons, the Back button on other layers, the info card, the
 * stand-in over the covered Travel cell with its "Travel" caption, and the frames around the
 * holes left for the real Travel row and close button. Also List mode's floating Map button and
 * the step-aside notice. Needs no game client.
 */
public class ChromePainter
{
	static final int BAR_H = 22;
	private static final int BUTTON_H = 16;
	static final int CARD_W = 340;
	/** Width of the default, compact card. */
	static final int CARD_W_COMPACT = 280;
	static final int CARD_MAX_H = 260;
	/** The step of the card's slide along the map's edges. */
	private static final int SLIDE = 8;

	static final Color FRAME = new Color(0x1e1a14);
	static final Color BRONZE = new Color(0x6b5a40);
	static final Color BRONZE_LIGHT = new Color(0x9a8360);
	static final Color FILL = new Color(24, 20, 14, 238);
	static final Color TITLE = new Color(0xFF981F);
	static final Color CREAM = new Color(0xF0E2C0);
	static final Color GREY = new Color(0xA79D8B);
	static final Color AMBER = new Color(0xFFB347);
	static final Color DANGER = new Color(0xFF5A4E);
	static final Color GOOD = new Color(0x6BE36B);
	static final Color HINT = new Color(0xFFE36B);
	/** What {@link Scene#standInText} puts before a grey row's hint; the stand-in shows a padlock instead. */
	private static final String LOCKED = "Locked: ";
	/** The padlock and the gap after it. */
	private static final int LOCK_W = 10;
	/** How far the stand-in may grow left of the cell to fit its lines. */
	static final int STAND_IN_GROW = 24;
	private static final String LAST_TRIP = "last trip";
	/** The card title's inset, beside the tree glyph. */
	private static final int TITLE_INDENT = 17;

	private final Ink ink;

	private BufferedImage layer;
	private long layerKey;
	private final List<Hit> layerHits = new ArrayList<>();
	private Rectangle layerCard;

	public ChromePainter(Ink ink)
	{
		this.ink = ink;
	}

	/** Places the top bar, Back button and Travel caption so the map painter can keep labels clear of them. */
	public void layout(Scene s)
	{
		MapView v = s.view;
		s.topBar = new Rectangle(v.getX(), v.getY(), v.getW(), BAR_H);
		// the card painted last; the map painter keeps labels from hiding half under it
		s.card = layerCard != null && v.rect().contains(layerCard) ? layerCard : null;
		Layer layer = s.layer();
		s.backButton = null;
		if (layer != null && !layer.isSurface())
		{
			int w = ink.width(ink.bold, backLabel(s)) + 30;
			s.backButton = new Rectangle(v.getX() + 8, v.getY() + BAR_H + 6, w, ink.height(ink.bold) + 12);
		}
		s.captionRect = null;
		if (s.rowCell != null && s.caption != null)
		{
			int h = ink.height(ink.small);
			s.captionRect = new Rectangle(s.rowCell.x - 1, s.rowCell.y - h - 6, ink.width(ink.small, s.caption) + 2, h);
		}
		s.standInRect = null;
		if (s.rowCell != null && !s.rowShown)
		{
			// a line is never cut while a little more room to the left (over the map) lets it fit
			Rectangle c = s.rowCell;
			String name = standInName(s);
			int wide = Math.max(standInWidth(standInLine(s), standInLocked(s)), name == null ? 0 : ink.width(ink.small, name));
			int grow = Math.max(0, Math.min(STAND_IN_GROW, wide + 6 - c.width));
			s.standInRect = new Rectangle(c.x - grow, c.y, c.width + grow, c.height);
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
			paintTopBar(lg, s);
			paintBackButton(lg, s);
			paintStandIn(lg, s);
			paintCard(lg, s);
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
		return Objects.hash(v.getX(), v.getY(), v.getW(), v.getH(), v.getLayer(), s.selected, s.hovered, s.states, s.keys,
			s.here, s.last, s.rowShown, s.standIn, s.rowCell, s.caption, s.notice, hover, s.holes, s.repo.getStateHash(),
			s.availableColor, s.selectedColor, s.fullDetails, focusCovered(s));
	}

	/** Drops the map-sized layer while the menu is closed; it is rebuilt on the next paint. */
	public void release()
	{
		layer = null;
		layerHits.clear();
		layerCard = null;
		layerKey = 0;
	}

	// ------------------------------------------------------------------ top bar

	private void paintTopBar(Graphics2D g, Scene s)
	{
		Rectangle bar = s.topBar;
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
		String[] ids = {Hit.LIST, Hit.FIT, Hit.ZOOM_IN, Hit.ZOOM_OUT, Hit.CLEAR};
		String[] labels = {"List", "Fit", "+", "-", "Clear"};
		String[] options = {"Show", "Fit", "Zoom in", "Zoom out", "Clear"};
		String[] targets = {"list", "map", "", "", "selection"};
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
		Layer layer = s.layer();
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

		Tree last = s.tree(s.last);
		if (last != null && x < right - 60)
		{
			x = text(g, "Last:", ink.small, GREY, x + 14, ty, right) + 4;
			text(g, last.getLabel(), ink.small, CREAM, x, ty, right);
		}
	}

	/** On another layer: a large, unmissable way back to the world map. */
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

	/** A button in the highlight colour, with a back arrow: the way out of another layer. */
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
		ink.text(g, label, ink.small, hover ? Color.WHITE : CREAM, b.x + (b.width - tw) / 2, b.y + (b.height - ink.height(ink.small)) / 2, Ink.Style.SHADOW);
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
		/** Left inset (title lines, beside the tree glyph). */
		int indent;
		/** The last-trip badge after the text: 0 none, 1 the glyph, 2 the glyph and "last trip". */
		int badge;

		Line(String text, Font font, Color color, int gapBefore)
		{
			this.text = text;
			this.font = font;
			this.color = color;
			this.gapBefore = gapBefore;
		}
	}

	/** Width of the last-trip badge with its words, gap before it included. */
	private int badgeW()
	{
		return 20 + ink.width(ink.small, LAST_TRIP);
	}

	/**
	 * The card for the hovered tree, else the selected one (DESIGN 4.6); a one-line hint with a
	 * small legend when there is neither.
	 */
	private void paintCard(Graphics2D g, Scene s)
	{
		Tree tree = s.hovered != null ? s.hovered : s.tree(s.selected);
		MapView v = s.view;
		int left = v.getX() + 6;
		int right = v.getX() + v.getW() - 6;
		int maxW = Math.min(Math.min(s.fullDetails ? CARD_W : CARD_W_COMPACT, Math.max(220, v.getW() * 45 / 100)), right - left);
		// on a small (fixed mode) map the card keeps to under half the height so the map stays usable
		int maxH = Math.min(CARD_MAX_H, v.getH() * (v.getH() < 450 ? 48 : 62) / 100);
		if (tree == null)
		{
			paintHint(g, s, left, right);
			return;
		}
		int pad = s.fullDetails ? 8 : 6;
		int textW = maxW - pad * 2;
		boolean full = s.fullDetails;
		Tree.Status status = s.status(tree);
		boolean locked = status == Tree.Status.LOCKED;

		// the title beside a small copy of the tree's map glyph, so the card reads as that marker's
		List<Line> lines = new ArrayList<>();
		List<String> title = full ? ink.wrap(tree.getName(), ink.bold, textW - TITLE_INDENT)
			: Collections.singletonList(ink.fit(tree.getName(), ink.bold, textW - TITLE_INDENT));
		for (String t : title)
		{
			Line l = new Line(t, ink.bold, TITLE, 0);
			l.indent = TITLE_INDENT;
			lines.add(l);
		}
		if (tree.getArea() != null)
		{
			addWrapped(lines, full ? tree.getArea() : ink.fit(tree.getArea(), ink.small, textW), ink.small, GREY, textW, 0);
		}
		// the status, with the last trip as a badge (the map marker's return arrow) after it
		String st = Scene.statusText(tree, status, s.here);
		Color stColor = tree.getId().equals(s.here) ? CREAM : status == Tree.Status.AVAILABLE ? s.availableColor : locked ? DANGER : GREY;
		List<String> stLines = full ? ink.wrap(st, ink.small, textW) : new ArrayList<>(Collections.singletonList(st));
		int end = stLines.size() - 1;
		int badge = 0;
		if (tree.getId().equals(s.last))
		{
			badge = ink.width(ink.small, stLines.get(end)) + badgeW() <= textW ? 2 : 1;
		}
		stLines.set(end, ink.fit(stLines.get(end), ink.small, textW - (badge == 2 ? badgeW() : badge == 1 ? 16 : 0)));
		for (int i = 0; i <= end; i++)
		{
			Line l = new Line(stLines.get(i), ink.small, stColor, i == 0 ? 2 : 0);
			l.badge = i == end ? badge : 0;
			lines.add(l);
		}
		if (full)
		{
			boolean first = true;
			for (String req : tree.getRequirements())
			{
				addWrapped(lines, "- " + req, ink.small, locked ? AMBER : GREY, textW, first ? 4 : 0);
				first = false;
			}
			first = true;
			for (String d : tree.getDanger())
			{
				addWrapped(lines, "! " + d, ink.small, DANGER, textW, first ? 4 : 0);
				first = false;
			}
			// places come after what the player must know before going, so a short card cuts them first
			if (!tree.getPoi().isEmpty())
			{
				addWrapped(lines, "Nearby: " + String.join(", ", tree.getPoi()), ink.small, CREAM, textW, 4);
			}
			first = true;
			for (String n : tree.getNotes())
			{
				addWrapped(lines, n, ink.small, new Color(0xB8C4D6), textW, first ? 4 : 0);
				first = false;
			}
		}
		else
		{
			if (locked && !tree.getRequirements().isEmpty())
			{
				lines.add(new Line(ink.fit("- " + tree.getRequirements().get(0), ink.small, textW), ink.small, AMBER, 3));
			}
			List<String> poi = tree.getPoi();
			if (!poi.isEmpty())
			{
				lines.add(new Line(ink.fit("Nearby: " + String.join(", ", poi.subList(0, Math.min(2, poi.size()))), ink.small, textW),
					ink.small, CREAM, 3));
			}
		}

		// the next step for the selected tree
		Line step = null;
		if (tree.getId().equals(s.selected))
		{
			Color c = status == Tree.Status.AVAILABLE && !tree.getId().equals(s.here) ? HINT : locked ? AMBER : GREY;
			step = new Line(Scene.nextStep(tree, status, s.key(tree), s.here), ink.small, c, 6);
		}
		int stepH = 0;
		List<String> stepLines = new ArrayList<>();
		if (step != null)
		{
			stepLines = ink.wrap(step.text, step.font, textW);
			stepH = step.gapBefore + stepLines.size() * ink.height(step.font);
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
			Line l = new Line(ink.fit(more + " ...", last.font, textW - last.indent).replace(" ...", "..."), last.font, last.color, last.gapBefore);
			l.indent = last.indent;
			lines.set(cut - 1, l);
		}
		Rectangle card = place(s, left, right, maxW, h, Math.min(h, 100), cardFocus(s));
		s.card = card;
		if (card == null)
		{
			return;
		}
		s.hits.add(new Hit(Hit.Kind.BLOCK, card, null, null, null, null));
		panelBox(g, card);
		g.setColor(TITLE);
		g.fillRect(card.x + 1, card.y + 4, 2, card.height - 8);

		int y = card.y + pad;
		int bottom = card.y + card.height - pad - stepH;
		int glyph = AtlasPainter.flags(s, tree) & (AtlasPainter.AVAILABLE | AtlasPainter.LOCKED);
		AtlasPainter.drawMarker(g, card.x + pad + 6.5, y + ink.height(ink.bold) / 2.0, 6.5, glyph, s);
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
			int x = card.x + pad + l.indent;
			ink.text(g, l.text, l.font, l.color, x, y, Ink.Style.SHADOW);
			if (l.badge > 0)
			{
				x += ink.width(l.font, l.text) + 10;
				AtlasPainter.drawLastBadge(g, x, y + lh / 2.0, 4.2);
				if (l.badge == 2)
				{
					ink.text(g, LAST_TRIP, ink.small, GREY, x + 7, y, Ink.Style.SHADOW);
				}
			}
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
		}
	}

	/** With nothing hovered or selected: what to do, and what the marker colours mean. */
	private void paintHint(Graphics2D g, Scene s, int left, int right)
	{
		String hint = "Click a tree to travel there";
		// the legend: each marker look beside its meaning, drawn as the map draws it
		String[] names = {"Available", "Locked", "Not listed"};
		int[] looks = {AtlasPainter.AVAILABLE, AtlasPainter.LOCKED, 0};
		int lh = ink.height(ink.small);
		int lw = 0;
		for (String n : names)
		{
			lw += 17 + ink.width(ink.small, n) + 8;
		}
		int w = Math.max(ink.width(ink.small, hint), lw) + 12;
		int hh = lh + 30;
		Rectangle r = place(s, left, right, w, hh, hh, null);
		// like the card, the hint keeps labels from under it and absorbs presses
		s.card = r;
		if (r == null)
		{
			return;
		}
		s.hits.add(new Hit(Hit.Kind.BLOCK, r, null, null, null, null));
		panelBox(g, r);
		ink.text(g, hint, ink.small, GREY, r.x + 6, r.y + 4, Ink.Style.SHADOW);
		int ly = r.y + 11 + lh;
		int x = r.x + 6;
		for (int i = 0; i < names.length; i++)
		{
			AtlasPainter.drawMarker(g, x + 6.5, ly + lh / 2.0, 6.5, looks[i], s);
			ink.text(g, names[i], ink.small, i == 0 ? s.availableColor : i == 1 ? AMBER : AtlasPainter.ABSENT, x + 17, ly, Ink.Style.SHADOW);
			x += 17 + ink.width(ink.small, names[i]) + 8;
		}
	}

	private void addWrapped(List<Line> lines, String text, Font f, Color c, int w, int gap)
	{
		List<String> wrapped = ink.wrap(text, f, w);
		for (int i = 0; i < wrapped.size(); i++)
		{
			lines.add(new Line(wrapped.get(i), f, c, i == 0 ? gap : 0));
		}
	}

	/** Whether the card drawn last now covers its own tree (after a pan), which forces a re-layout. */
	private boolean focusCovered(Scene s)
	{
		Point f = cardFocus(s);
		return f != null && layerCard != null && AtlasPainter.grow(layerCard, 12).contains(f);
	}

	/** The screen point of the tree the card describes (its marker or surface stand-in), which the card must not cover. */
	private static Point cardFocus(Scene s)
	{
		Tree t = s.hovered != null ? s.hovered : s.tree(s.selected);
		for (AtlasPainter.Mark m : AtlasPainter.marks(s))
		{
			if (m.tree == t)
			{
				return new Point((int) m.x, (int) m.y);
			}
		}
		return null;
	}

	/**
	 * Finds a spot for a card: bottom-left, else bottom-right, top-right or top-left, clear of the
	 * holes, the Travel cell and its caption, the Back button and the tree it describes, and
	 * covering no marker; else the first such spot along the bottom edge, then the top, the left
	 * and the right (in {@link #SLIDE} px steps); else a corner that may cover other markers.
	 * Failing that, it slides a corner spot off them, shrinking to at least minH.
	 */
	private Rectangle place(Scene s, int left, int right, int w, int h, int minH, Point focus)
	{
		MapView v = s.view;
		int top = v.getY() + BAR_H + 6;
		int bottom = v.getY() + v.getH() - 6;
		if (w <= 40)
		{
			return null;
		}
		List<Rectangle> blockers = new ArrayList<>(s.blockers());
		if (s.standInRect != null)
		{
			blockers.add(s.standInRect);
		}
		if (s.backButton != null)
		{
			blockers.add(s.backButton);
		}
		int[][] spots = {{left, 1}, {right - w, 1}, {right - w, 0}, {left, 0}};
		// a corner that covers no marker at all first; then the first spot along the bottom edge,
		// the top, the left or the right that covers none (a small fitted map has a marker near
		// every corner); then a corner clear of the card's own tree only
		List<AtlasPainter.Mark> marks = AtlasPainter.marks(s);
		for (int[] spot : spots)
		{
			Rectangle r = new Rectangle(spot[0], spot[1] == 1 ? bottom - h : top, w, h);
			if (r.y >= top && clear(blockers, r, focus) && clearOf(marks, r))
			{
				return r;
			}
		}
		for (int edge = 0; edge < 4 && bottom - h >= top; edge++)
		{
			boolean across = edge < 2;
			for (int at = across ? left : top; at <= (across ? right - w : bottom - h); at += SLIDE)
			{
				Rectangle r = across ? new Rectangle(at, edge == 0 ? bottom - h : top, w, h)
					: new Rectangle(edge == 2 ? left : right - w, at, w, h);
				if (clear(blockers, r, focus) && clearOf(marks, r))
				{
					return r;
				}
			}
		}
		for (int[] spot : spots)
		{
			Rectangle r = new Rectangle(spot[0], spot[1] == 1 ? bottom - h : top, w, h);
			if (r.y >= top && clear(blockers, r, focus))
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
				for (Rectangle hole : blockers)
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
				if (hh < minH || (pass == 0 && !clear(blockers, r, focus)))
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

	private static boolean clearOf(List<AtlasPainter.Mark> marks, Rectangle r)
	{
		Rectangle near = AtlasPainter.grow(r, 10);
		for (AtlasPainter.Mark m : marks)
		{
			if (near.contains(m.x, m.y))
			{
				return false;
			}
		}
		return true;
	}

	private static boolean clear(List<Rectangle> blockers, Rectangle r, Point focus)
	{
		for (Rectangle b : blockers)
		{
			if (AtlasPainter.grow(b, 6).intersects(r))
			{
				return false;
			}
		}
		return focus == null || !AtlasPainter.grow(r, 12).contains(focus);
	}

	// ------------------------------------------------------------------ Travel cell, frame and holes

	/**
	 * The "Travel" caption over the cell's left end and, while the real row is covered, a disabled
	 * stand-in in its place that owns its clicks (DESIGN 4.4): for a selected tree, its name over
	 * why it cannot travel (the padlock and its hint, "You are here", "Not in this tree's list");
	 * with nothing selected, one line. A cell too short for two lines shows only the reason.
	 */
	void paintStandIn(Graphics2D g, Scene s)
	{
		Rectangle cell = s.rowCell;
		if (cell == null)
		{
			return;
		}
		if (s.captionRect != null)
		{
			ink.text(g, s.caption, ink.small, s.rowShown ? GOOD : CREAM, s.captionRect.x + 1, s.captionRect.y, Ink.Style.OUTLINE);
		}
		if (s.rowShown)
		{
			return;
		}
		Rectangle box = s.standInRect != null ? s.standInRect : cell;
		g.setColor(new Color(0, 0, 0, 90));
		g.fillRect(box.x - 2, box.y + box.height + 2, box.width + 5, 3);
		g.fillRect(box.x + box.width + 2, box.y - 1, 3, box.height + 3);
		g.setColor(new Color(0x2a241b));
		g.fillRect(box.x, box.y, box.width, box.height);
		g.setColor(FRAME);
		g.drawRect(box.x - 1, box.y - 1, box.width + 1, box.height + 1);
		g.setColor(BRONZE);
		g.drawRect(box.x - 3, box.y - 3, box.width + 5, box.height + 5);
		boolean locked = standInLocked(s);
		int lh = ink.height(ink.small);
		String name = standInName(s);
		name = name != null && box.height >= 2 * lh + 2 ? name : null;
		// the lines as a block, centred; "..." only when even the widened box is too narrow
		int y = box.y + (box.height - (name == null ? lh : 2 * lh)) / 2;
		if (name != null)
		{
			String n = ink.fit(name, ink.small, box.width - 6);
			ink.text(g, n, ink.small, Color.WHITE, box.x + (box.width - ink.width(ink.small, n) + 1) / 2, y, Ink.Style.SHADOW);
			y += lh;
		}
		String t = ink.fit(standInLine(s), ink.small, box.width - 6 - (locked ? LOCK_W : 0));
		Color c = s.selected == null ? GREY : locked ? AMBER : name != null ? GREY : CREAM;
		int x = box.x + (box.width - standInWidth(t, locked) + 1) / 2;
		if (locked)
		{
			AtlasPainter.drawPadlock(g, x + 3.5, y + lh / 2.0, 7, AMBER);
			x += LOCK_W;
		}
		ink.text(g, t, ink.small, c, x, y, Ink.Style.SHADOW);
		s.hits.add(new Hit(Hit.Kind.BLOCK, AtlasPainter.grow(box, 3), null, null, null, null));
	}

	/** The stand-in's first line: the selected tree's label (the house names its town), or null. */
	private static String standInName(Scene s)
	{
		Tree sel = s.tree(s.selected);
		return sel == null ? null : sel.getLabel();
	}

	/** The stand-in's line: {@link Scene#standIn}, a grey row's hint without its "Locked: " (the padlock says it). */
	private static String standInLine(Scene s)
	{
		String t = s.standIn == null ? "" : s.standIn;
		return t.startsWith(LOCKED) ? t.substring(LOCKED.length()) : t;
	}

	private static boolean standInLocked(Scene s)
	{
		Tree sel = s.tree(s.selected);
		return s.standIn != null && (s.standIn.startsWith(LOCKED) || sel != null && s.standIn.equals(sel.getLockedHint()));
	}

	/** The stand-in line's width: the text and, for a grey row, the padlock before it. */
	int standInWidth(String text, boolean locked)
	{
		return (locked ? LOCK_W : 0) + ink.width(ink.small, text);
	}

	private void paintFrame(Graphics2D g, Scene s)
	{
		Rectangle m = s.view.rect();
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

	/** Frames the holes so the real widgets read as buttons on the map; the shown Travel row pulses. */
	public void paintHoles(Graphics2D g, Scene s)
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
			if (s.rowShown && h.equals(s.rowCell))
			{
				double t = 0.5 + 0.5 * Math.sin(s.now / 320.0);
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g.setColor(AtlasPainter.withAlpha(GOOD, (int) (90 + 120 * t)));
				g.setStroke(new BasicStroke(2f));
				g.drawRoundRect(h.x - 5, h.y - 5, h.width + 9, h.height + 9, 6, 6);
				g.setStroke(new BasicStroke(1f));
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
			}
		}
	}

	// ------------------------------------------------------------------ List mode and stepping aside

	/** List mode's floating Map button: just above the plain menu's top-left corner. */
	Rectangle mapButton(Point anchor)
	{
		return new Rectangle(anchor.x, anchor.y - 24, ink.width(ink.small, "Map") + 30, 20);
	}

	void paintMapButton(Graphics2D g, Rectangle b, boolean hover)
	{
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(FRAME);
		g.fillRoundRect(b.x - 1, b.y - 1, b.width + 2, b.height + 2, 6, 6);
		g.setColor(hover ? new Color(0x5a4b36) : new Color(0x3a3125));
		g.fillRoundRect(b.x, b.y, b.width, b.height, 5, 5);
		g.setColor(hover ? BRONZE_LIGHT : BRONZE);
		g.setStroke(new BasicStroke(1f));
		g.drawRoundRect(b.x, b.y, b.width - 1, b.height - 1, 5, 5);
		paintMapGlyph(g, b.x + 6, b.y + 5);
		ink.text(g, "Map", ink.small, hover ? Color.WHITE : CREAM, b.x + 22, b.y + (b.height - ink.height(ink.small)) / 2, Ink.Style.SHADOW);
	}

	/**
	 * The step-aside notice (DESIGN 4.10): one line in a dark box just above the menu (inside its
	 * top edge when there is no room above), kept on the canvas.
	 */
	void paintNotice(Graphics2D g, Rectangle menu, Rectangle canvas, String notice)
	{
		int w = Math.min(canvas.width - 8, ink.width(ink.small, notice) + 12);
		int h = ink.height(ink.small) + 8;
		int x = Math.max(canvas.x + 4, Math.min(menu.x + (menu.width - w) / 2, canvas.x + canvas.width - 4 - w));
		int y = menu.y - h - 4 >= canvas.y ? menu.y - h - 4 : menu.y + 4;
		g.setColor(new Color(90, 20, 14, 225));
		g.fillRoundRect(x, y, w, h, 6, 6);
		g.setColor(FRAME);
		g.drawRoundRect(x, y, w - 1, h - 1, 6, 6);
		ink.text(g, ink.fit(notice, ink.small, w - 12), ink.small, Color.WHITE, x + 6, y + 4, Ink.Style.SHADOW);
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
		g.setColor(FRAME);
		g.setStroke(new BasicStroke(1f));
		g.draw(p);
		g.drawLine(x + 4, y, x + 4, y + 9);
		g.drawLine(x + 8, y + 2, x + 8, y + 11);
	}

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
}
