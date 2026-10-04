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
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Draws what sits on the map itself: place labels, map icons, the map-link glyphs of other
 * layers, tree markers (and their surface stand-ins) with their key badges, and tree labels
 * (DESIGN 4.7). Needs no game client. Label placement is cached until the view, the selection or
 * the tree states change; markers are pre-rendered sprites, so a frame is mostly blits.
 */
public class AtlasPainter
{
	static final int AVAILABLE = 1;
	static final int LOCKED = 2;
	static final int SELECTED = 4;
	static final int HOVER = 8;
	static final int LAST = 16;

	static final Color LOCKED_CANOPY = new Color(0x8C8C8C);
	/** A tree not in the open menu's list: drawn hollow. */
	static final Color ABSENT = new Color(0xC4CCC0);
	static final Color TRUNK = new Color(0x8A5A32);
	/** The padlock on locked trees. */
	static final Color LOCK = new Color(0xD8DEDC);
	static final Color PORTAL = new Color(0xD7B8FF);

	final Ink ink;
	private final Map<Long, BufferedImage> markerSprites = new HashMap<>();
	private int colourKey;
	private long layoutKey = Long.MIN_VALUE;
	private final List<Placed> treeLabels = new ArrayList<>();
	private final List<Placed> placeLabels = new ArrayList<>();

	private static final class Placed
	{
		final Mark mark;
		final Rectangle at;
		final BufferedImage sprite;

		Placed(Mark mark, Rectangle at, BufferedImage sprite)
		{
			this.mark = mark;
			this.at = at;
			this.sprite = sprite;
		}
	}

	/** A marker on screen: a tree on the view's layer, or a surface stand-in for one. */
	private static final class Mark
	{
		final Tree tree;
		final double x;
		final double y;
		final boolean standIn;

		Mark(Tree tree, double x, double y, boolean standIn)
		{
			this.tree = tree;
			this.x = x;
			this.y = y;
			this.standIn = standIn;
		}
	}

	public AtlasPainter()
	{
		this(Ink.create());
	}

	AtlasPainter(Ink ink)
	{
		this.ink = ink;
	}

	public Ink ink()
	{
		return ink;
	}

	/** Marker radius at a zoom: a 15 px glyph, growing slightly past 4 ppt; larger when selected. */
	static double radius(double ppt, boolean selected)
	{
		double r = 7.5 + (ppt > 4 ? Math.min(3, 1.5 * Math.log(ppt / 4) / Math.log(2)) : 0);
		return selected ? r * 1.25 : r;
	}

	int flags(Scene s, Tree t)
	{
		Tree.Status st = s.status(t);
		int f = st == Tree.Status.AVAILABLE ? AVAILABLE : st == Tree.Status.LOCKED ? LOCKED : 0;
		if (t.getId().equals(s.selected))
		{
			f |= SELECTED;
		}
		if (t == s.hovered)
		{
			f |= HOVER;
		}
		if (t.getId().equals(s.last))
		{
			f |= LAST;
		}
		return f;
	}

	float alpha(Scene s, int flags)
	{
		return s.dimLocked && (flags & LOCKED) != 0 && (flags & (SELECTED | HOVER)) == 0 ? 0.5f : 1f;
	}

	/** The markers on the view: its layer's trees and, on the surface, the stand-ins. */
	private List<Mark> marks(Scene s)
	{
		MapView v = s.view;
		List<Mark> out = new ArrayList<>();
		for (Tree t : s.trees)
		{
			if (t.isMapped() && v.getLayer().equals(t.getLayer()))
			{
				out.add(new Mark(t, v.screenX(t.getX() + 0.5), v.screenY(t.getY() + 0.5), false));
			}
		}
		if (Layer.SURFACE.equals(v.getLayer()))
		{
			for (TreeRepository.StandIn si : s.standIns)
			{
				out.add(new Mark(si.getTree(), si.screenX(v), si.screenY(v), true));
			}
		}
		return out;
	}

	/** Draws everything on the map surface and registers marker and portal hits. */
	public void paintMap(Graphics2D g, Scene s)
	{
		MapView v = s.view;
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		List<Mark> marks = marks(s);
		layout(s, marks);

		for (Placed p : placeLabels)
		{
			g.drawImage(p.sprite, p.at.x, p.at.y, null);
		}
		if (s.mapIcons && v.getPpt() >= 2)
		{
			paintIcons(g, s);
		}
		if (Layer.SURFACE.equals(v.getLayer()))
		{
			paintPortals(g, s);
		}

		marks.sort(Comparator.comparingInt(m -> order(s, m.tree)));
		Composite old = g.getComposite();
		for (Mark m : marks)
		{
			if (!v.contains((int) m.x, (int) m.y))
			{
				continue;
			}
			int f = flags(s, m.tree);
			float a = alpha(s, f);
			if (a < 1)
			{
				g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, a));
			}
			boolean sel = (f & SELECTED) != 0;
			double rad = radius(v.getPpt(), sel);
			if (sel)
			{
				paintHalo(g, m.x, m.y, rad, s.selectedColor, s.now);
			}
			BufferedImage img = marker(f, rad, s);
			g.drawImage(img, (int) Math.round(m.x - img.getWidth() / 2.0), (int) Math.round(m.y - img.getHeight() / 2.0), null);
			if (s.keyHints && s.key(m.tree) != null)
			{
				paintKey(g, s.key(m.tree), m.x + rad * 0.5, m.y - rad * 0.8);
			}
			if (m.tree.getId().equals(s.here))
			{
				paintPin(g, ink, m.x, m.y - rad - 2);
			}
			g.setComposite(old);
			int hr = (int) Math.ceil(rad + 3);
			s.hits.add(new Hit(Hit.Kind.MARKER, new Rectangle((int) m.x - hr, (int) m.y - hr, hr * 2, hr * 2), m.tree,
				m.standIn ? Hit.STAND_IN : null, "Select", m.tree.getLabel()));
		}

		for (Placed p : treeLabels)
		{
			float a = alpha(s, flags(s, p.mark.tree));
			if (a < 1)
			{
				g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, a));
			}
			g.drawImage(labelSprite(s, p.mark.tree), p.at.x - 1, p.at.y - 1, null);
			g.setComposite(old);
		}
	}

	/** Draw and label order: absent, locked, available, last trip, then hovered and selected on top. */
	private int order(Scene s, Tree t)
	{
		int f = flags(s, t);
		return ((f & SELECTED) != 0 ? 16 : 0) + ((f & HOVER) != 0 ? 8 : 0) + ((f & LAST) != 0 ? 4 : 0)
			+ ((f & AVAILABLE) != 0 ? 2 : 0) + ((f & LOCKED) != 0 ? 1 : 0);
	}

	private BufferedImage labelSprite(Scene s, Tree t)
	{
		int f = flags(s, t);
		Color c = (f & SELECTED) != 0 ? s.selectedColor
			: (f & AVAILABLE) != 0 ? Color.WHITE
			: (f & LOCKED) != 0 ? new Color(0xB4B4B4) : ABSENT;
		return ink.sprite(t.getLabel(), ink.small, c, Ink.Style.OUTLINE);
	}

	// ------------------------------------------------------------------ layout

	private void layout(Scene s, List<Mark> marks)
	{
		MapView v = s.view;
		long key = Objects.hash(v, s.selected, s.hovered, s.treeLabels, s.placeLabels, s.keyHints, s.topBar, s.card, s.backButton,
			s.holes, s.rowCell, s.captionRect, s.states, s.keys, s.here, s.last, s.standIns.size());
		if (key == layoutKey)
		{
			return;
		}
		layoutKey = key;
		treeLabels.clear();
		placeLabels.clear();

		Rectangle bounds = new Rectangle(v.getX() + 2, v.getY() + 2, v.getW() - 4, v.getH() - 4);
		LabelPlacer placer = new LabelPlacer(bounds);
		placer.addObstacle(s.topBar);
		placer.addObstacle(s.card);
		placer.addObstacle(s.backButton);
		for (Rectangle h : s.blockers())
		{
			placer.addObstacle(grow(h, 4));
		}
		for (Mark m : marks)
		{
			double rad = radius(v.getPpt(), m.tree.getId().equals(s.selected));
			int q = (int) Math.ceil(rad + 1);
			placer.addObstacle(new Rectangle((int) Math.round(m.x) - q, (int) Math.round(m.y) - q, q * 2, q * 2));
			if (s.keyHints && s.key(m.tree) != null)
			{
				placer.addObstacle(keyBox(s.key(m.tree), m.x + rad * 0.5, m.y - rad * 0.8));
			}
		}
		if (Layer.SURFACE.equals(v.getLayer()))
		{
			for (Portal p : s.repo.getPortals())
			{
				placer.addObstacle(portalBox(s, p));
			}
		}

		if (s.treeLabels)
		{
			List<Mark> order = new ArrayList<>(marks);
			order.sort(Comparator.comparingInt((Mark m) -> -order(s, m.tree)).thenComparing(m -> m.tree.getLabel()));
			int h = ink.height(ink.small);
			for (Mark m : order)
			{
				int px = (int) m.x;
				int py = (int) m.y;
				if (covered(s.topBar, px, py) || covered(s.card, px, py) || covered(s.backButton, px, py) || inHole(s, px, py))
				{
					// the marker is under the chrome; a label beside it would point at nothing
					continue;
				}
				double rad = radius(v.getPpt(), m.tree.getId().equals(s.selected));
				Rectangle at = placer.place(m.x, m.y, ink.width(ink.small, m.tree.getLabel()), h, (int) Math.ceil(rad), 3);
				if (at != null)
				{
					treeLabels.add(new Placed(m, at, null));
				}
			}
		}

		if (s.placeLabels)
		{
			List<MapIndex.Label> labels = new ArrayList<>(s.repo.getIndex().getLabels());
			labels.sort(Comparator.comparingInt(l -> -l.getS()));
			Layer layer = s.layer();
			for (MapIndex.Label l : labels)
			{
				if (l.getS() < 2 && v.getPpt() < (l.getS() == 1 ? 0.75 : 2))
				{
					continue;
				}
				boolean inLayer = l.getLayer() != null ? l.getLayer().equals(v.getLayer()) : layer != null && layer.contains(l.getX(), l.getY());
				if (inLayer)
				{
					placeLabel(s, placer, l);
				}
			}
		}
	}

	private void placeLabel(Scene s, LabelPlacer placer, MapIndex.Label l)
	{
		MapView v = s.view;
		Font f = l.getS() >= 2 ? ink.bold : l.getS() == 1 ? ink.regular : ink.small;
		Color c = l.getColor();
		String[] lines = l.getLines();
		if (lines.length == 0)
		{
			return;
		}
		int lh = ink.height(f);
		int w = 0;
		for (String line : lines)
		{
			w = Math.max(w, ink.width(f, line));
		}
		int h = lh * lines.length;
		int x = (int) Math.round(v.screenX(l.getX() + 0.5) - w / 2.0);
		int y = (int) Math.round(v.screenY(l.getY() + 0.5) - h / 2.0);
		if (!placer.placeExact(new Rectangle(x, y, w, h)))
		{
			return;
		}
		for (int i = 0; i < lines.length; i++)
		{
			int lw = ink.width(f, lines[i]);
			BufferedImage img = ink.sprite(lines[i], f, c, Ink.Style.OUTLINE);
			placeLabels.add(new Placed(null, new Rectangle(x + (w - lw) / 2 - 1, y + i * lh - 1, img.getWidth(), img.getHeight()), img));
		}
	}

	// ------------------------------------------------------------------ markers

	BufferedImage marker(int flags, double rad, Scene s)
	{
		int ck = Objects.hash(s.availableColor, s.selectedColor);
		if (ck != colourKey)
		{
			markerSprites.clear();
			colourKey = ck;
		}
		long key = ((long) Math.round(rad * 4) << 8) | flags;
		BufferedImage img = markerSprites.get(key);
		if (img == null)
		{
			int size = (int) Math.ceil((rad + 6) * 2);
			img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = img.createGraphics();
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
			drawMarker(g, size / 2.0, size / 2.0, rad, flags, s);
			g.dispose();
			markerSprites.put(key, img);
		}
		return img;
	}

	/**
	 * A spirit tree glyph (round canopy over a short trunk) in a dark disc of radius r: the canopy
	 * in the state's colour, hollow when the tree is not in the list; a padlock when locked, a
	 * ring when selected, a white outline on hover, a return-arrow badge for the last trip.
	 */
	void drawMarker(Graphics2D g, double cx, double cy, double r, int flags, Scene s)
	{
		boolean available = (flags & AVAILABLE) != 0;
		boolean locked = (flags & LOCKED) != 0;
		g.setColor(new Color(0, 0, 0, 170));
		g.fill(circle(cx, cy, r));
		Path2D trunk = new Path2D.Double();
		trunk.moveTo(cx - r * 0.13, cy + r * 0.05);
		trunk.lineTo(cx + r * 0.13, cy + r * 0.05);
		trunk.lineTo(cx + r * 0.2, cy + r * 0.66);
		trunk.lineTo(cx - r * 0.2, cy + r * 0.66);
		trunk.closePath();
		Ellipse2D canopy = new Ellipse2D.Double(cx - r * 0.62, cy - r * 0.7, r * 1.24, r * 1.0);
		if (available || locked)
		{
			g.setColor(locked ? new Color(0x6A6A6A) : TRUNK);
			g.fill(trunk);
			Color c = available ? s.availableColor : LOCKED_CANOPY;
			g.setColor(c);
			g.fill(canopy);
			// a highlight on the canopy's upper left gives it some volume
			g.setColor(withAlpha(Color.WHITE, 70));
			g.fill(new Ellipse2D.Double(cx - r * 0.42, cy - r * 0.6, r * 0.5, r * 0.36));
		}
		else
		{
			g.setColor(ABSENT);
			g.setStroke(new BasicStroke(1.2f));
			g.draw(canopy);
			g.draw(trunk);
		}
		if (locked)
		{
			drawPadlock(g, cx + r * 0.45, cy + r * 0.38, Math.max(4.5, r * 0.6), LOCK);
		}
		if ((flags & SELECTED) != 0)
		{
			g.setColor(s.selectedColor);
			g.setStroke(new BasicStroke(1.8f));
			g.draw(circle(cx, cy, r + 0.6));
		}
		if ((flags & HOVER) != 0)
		{
			g.setColor(Color.WHITE);
			g.setStroke(new BasicStroke(1.4f));
			g.draw(circle(cx, cy, r + 2.4));
		}
		if ((flags & LAST) != 0)
		{
			drawLastBadge(g, cx - r * 0.8, cy + r * 0.8, 3.7);
		}
	}

	private void paintHalo(Graphics2D g, double cx, double cy, double r, Color c, long now)
	{
		double t = (now % 1400) / 1400.0;
		double rr = r + 4 + t * 9;
		g.setColor(withAlpha(c, (int) (110 * (1 - t))));
		g.setStroke(new BasicStroke(2f));
		g.draw(circle(cx, cy, rr));
		g.setColor(withAlpha(c, 40));
		g.fill(circle(cx, cy, r + 4));
	}

	/** The row's hotkey in a small dark rounded square whose bottom-left corner is at (x, y). */
	private void paintKey(Graphics2D g, String key, double x, double y)
	{
		Rectangle b = keyBox(key, x, y);
		g.setColor(new Color(16, 13, 9, 230));
		g.fill(new RoundRectangle2D.Double(b.x, b.y, b.width, b.height, 4, 4));
		g.setColor(new Color(0x6b5a40));
		g.setStroke(new BasicStroke(1f));
		g.draw(new RoundRectangle2D.Double(b.x + 0.5, b.y + 0.5, b.width - 1, b.height - 1, 4, 4));
		ink.text(g, key, ink.small, Color.WHITE, b.x + 3, b.y + 1, Ink.Style.PLAIN);
	}

	private Rectangle keyBox(String key, double x, double y)
	{
		int w = Math.max(11, ink.width(ink.small, key) + 6);
		int h = ink.height(ink.small) + 1;
		return new Rectangle((int) Math.round(x), (int) Math.round(y) - h, w, h);
	}

	/** A "you are here" pin whose tip touches (x, y), with "You" beside its head. */
	static void paintPin(Graphics2D g, Ink ink, double x, double y)
	{
		Path2D p = new Path2D.Double();
		p.moveTo(x, y);
		p.curveTo(x - 2, y - 4, x - 5.5, y - 6, x - 5.5, y - 10);
		p.curveTo(x - 5.5, y - 14, x + 5.5, y - 14, x + 5.5, y - 10);
		p.curveTo(x + 5.5, y - 6, x + 2, y - 4, x, y);
		p.closePath();
		g.setColor(Ink.DARK);
		g.setStroke(new BasicStroke(2f));
		g.draw(p);
		g.setColor(new Color(0xF04A3C));
		g.fill(p);
		g.setColor(Color.WHITE);
		g.fill(circle(x, y - 10, 2));
		if (ink != null)
		{
			ink.text(g, "You", ink.small, Color.WHITE, (int) Math.round(x + 7), (int) Math.round(y - 15), Ink.Style.OUTLINE);
		}
	}

	/** A small padlock centred on (cx, cy); w is the body width. */
	static void drawPadlock(Graphics2D g, double cx, double cy, double w, Color c)
	{
		double bh = w * 0.72;
		double by = cy - bh * 0.25;
		double k = w * 0.3;
		Path2D shackle = new Path2D.Double();
		shackle.append(new Arc2D.Double(cx - k, by - k * 2, k * 2, k * 2.6, 0, 180, Arc2D.OPEN), false);
		g.setStroke(new BasicStroke((float) Math.max(1.1, w * 0.17)));
		g.setColor(Ink.DARK);
		g.fill(new RoundRectangle2D.Double(cx - w / 2 - 0.8, by - 0.8, w + 1.6, bh + 1.6, 2, 2));
		g.setColor(c);
		g.draw(shackle);
		g.fill(new RoundRectangle2D.Double(cx - w / 2, by, w, bh, 1.5, 1.5));
		g.setColor(Ink.DARK);
		g.fill(circle(cx, by + bh * 0.45, Math.max(0.6, w * 0.1)));
	}

	/** A small round badge with a circular arrow: "last trip". */
	static void drawLastBadge(Graphics2D g, double cx, double cy, double r)
	{
		g.setColor(Ink.DARK);
		g.fill(circle(cx, cy, r + 0.8));
		g.setColor(new Color(0xE8E2D0));
		g.setStroke(new BasicStroke(1.1f));
		double a = r * 0.55;
		g.draw(new Arc2D.Double(cx - a, cy - a, a * 2, a * 2, 90, 270, Arc2D.OPEN));
		Path2D head = new Path2D.Double();
		head.moveTo(cx - 0.2, cy - a - 1.4);
		head.lineTo(cx + 1.6, cy - a);
		head.lineTo(cx - 0.2, cy - a + 1.4);
		head.closePath();
		g.fill(head);
	}

	// ------------------------------------------------------------------ icons and portals

	private void paintIcons(Graphics2D g, Scene s)
	{
		MapView v = s.view;
		Layer layer = s.layer();
		for (MapIndex.Icon icon : s.repo.getIndex().getIcons())
		{
			if (layer != null && !layer.contains(icon.getX(), icon.getY()))
			{
				continue;
			}
			int sx = (int) Math.round(v.screenX(icon.getX() + 0.5));
			int sy = (int) Math.round(v.screenY(icon.getY() + 0.5));
			if (!v.contains(sx, sy))
			{
				continue;
			}
			BufferedImage img = s.sprites.apply(icon.getSprite());
			if (img != null)
			{
				g.drawImage(img, sx - img.getWidth() / 2, sy - img.getHeight() / 2, null);
			}
		}
	}

	/** The map-link glyph of a portal: just below the stand-ins that sit on its point. */
	private Rectangle portalBox(Scene s, Portal p)
	{
		MapView v = s.view;
		int sx = (int) Math.round(v.screenX(p.getX() + 0.5));
		int sy = (int) Math.round(v.screenY(p.getY() + 0.5) + radius(v.getPpt(), false) + 9);
		return new Rectangle(sx - 7, sy - 7, 14, 14);
	}

	private void paintPortals(Graphics2D g, Scene s)
	{
		for (Portal p : s.repo.getPortals())
		{
			Rectangle box = portalBox(s, p);
			if (!s.view.contains((int) box.getCenterX(), (int) box.getCenterY()))
			{
				continue;
			}
			double cx = box.getCenterX();
			double cy = box.getCenterY();
			g.setColor(Ink.DARK);
			g.fillRoundRect(box.x - 1, box.y - 1, box.width + 2, box.height + 2, 5, 5);
			g.setColor(new Color(0x3B2E52));
			g.fillRoundRect(box.x, box.y, box.width, box.height, 4, 4);
			// a page with an arrow leaving it: "opens another map"
			g.setColor(PORTAL);
			g.setStroke(new BasicStroke(1.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			Path2D page = new Path2D.Double();
			page.moveTo(cx - 0.5, cy - 4);
			page.lineTo(cx - 4, cy - 4);
			page.lineTo(cx - 4, cy + 4);
			page.lineTo(cx + 4, cy + 4);
			page.lineTo(cx + 4, cy + 0.5);
			g.draw(page);
			Path2D arrow = new Path2D.Double();
			arrow.moveTo(cx - 1, cy + 1);
			arrow.lineTo(cx + 4, cy - 4);
			arrow.moveTo(cx + 1, cy - 4);
			arrow.lineTo(cx + 4, cy - 4);
			arrow.lineTo(cx + 4, cy - 1);
			g.draw(arrow);
			Layer l = s.repo.layer(p.getLayer());
			String name = l == null ? p.getLayer() : l.getName();
			s.hits.add(new Hit(Hit.Kind.PORTAL, grow(box, 2), null, p.getLayer(), "Open", name + " map"));
		}
	}

	// ------------------------------------------------------------------ helpers

	static Ellipse2D circle(double cx, double cy, double r)
	{
		return new Ellipse2D.Double(cx - r, cy - r, r * 2, r * 2);
	}

	static Color withAlpha(Color c, int a)
	{
		return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, a)));
	}

	private static boolean covered(Rectangle r, int x, int y)
	{
		return r != null && r.contains(x, y);
	}

	private static boolean inHole(Scene s, int x, int y)
	{
		for (Rectangle h : s.blockers())
		{
			if (h.contains(x, y))
			{
				return true;
			}
		}
		return false;
	}

	static Rectangle grow(Rectangle r, int n)
	{
		return new Rectangle(r.x - n, r.y - n, r.width + 2 * n, r.height + 2 * n);
	}
}
