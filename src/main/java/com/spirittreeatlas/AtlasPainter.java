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
import java.awt.Stroke;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Draws what sits on the map itself: place labels, portals, the clue marker, ring markers and
 * their code labels. Needs no game client. Label placement is cached until the view, the selection
 * or the ring states change; markers are pre-rendered sprites, so a frame is mostly blits.
 */
public class AtlasPainter
{
	static final int VISITED = 1;
	static final int FAVE = 2;
	static final int LAST = 4;
	static final int SELECTED = 8;
	static final int HOVER = 16;

	static final Color UNVISITED = new Color(0x93B5B0);
	static final Color UNVISITED_DOT = new Color(0x6E8C88);
	/** The padlock on rings that are not unlocked yet. */
	static final Color LOCK = new Color(0xD8DEDC);
	static final Color INFO = new Color(0xB79CE8);
	static final Color PORTAL = new Color(0xD7B8FF);
	static final Color CLUE = new Color(0xE8322C);

	final Ink ink;
	private final Map<Long, BufferedImage> markerSprites = new HashMap<>();
	private int colourKey;
	private long layoutKey = Long.MIN_VALUE;
	private final List<Placed> codeLabels = new ArrayList<>();
	private final List<Placed> placeLabels = new ArrayList<>();
	private final List<Placed> portalLabels = new ArrayList<>();

	private static final class Placed
	{
		final Object what;
		final Rectangle at;
		final BufferedImage sprite;

		Placed(Object what, Rectangle at, BufferedImage sprite)
		{
			this.what = what;
			this.at = at;
			this.sprite = sprite;
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

	/** Marker radius at a zoom: about 6 px, growing slightly past 4 ppt. */
	static double radius(double ppt, boolean selected)
	{
		double r = 5.5 + (ppt > 4 ? Math.min(3, 1.5 * Math.log(ppt / 4) / Math.log(2)) : 0);
		return selected ? r * 1.3 : r;
	}

	int flags(Scene s, Ring r)
	{
		RingRepository repo = s.repo;
		String code = r.getCode();
		int f = 0;
		if (!r.isDialable() || repo.isVisited(code))
		{
			f |= VISITED;
		}
		if (repo.isFavourite(code))
		{
			f |= FAVE;
		}
		if (code != null && code.equals(repo.getLastCode()))
		{
			f |= LAST;
		}
		if (code != null && code.equals(s.selected))
		{
			f |= SELECTED;
		}
		if (r == s.hovered)
		{
			f |= HOVER;
		}
		return f;
	}

	float alpha(Scene s, Ring r, int flags)
	{
		float a = 1f;
		if (s.searching() && r.isDialable() && !r.matches(s.query))
		{
			a = 0.25f;
		}
		else if (s.dimUnvisited && (flags & VISITED) == 0 && (flags & (SELECTED | HOVER)) == 0)
		{
			a = 0.5f;
		}
		return a;
	}

	/** Draws everything on the map surface and registers marker and portal hits. */
	public void paintMap(Graphics2D g, Scene s)
	{
		MapView v = s.view;
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		layout(s);

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
		paintClue(g, s, false);

		List<Ring> rings = s.repo.mappedIn(v.getLayer());
		rings.sort(Comparator.comparingInt(r -> order(s, r)));
		Composite old = g.getComposite();
		for (Ring r : rings)
		{
			double sx = v.screenX(r.getX() + 0.5);
			double sy = v.screenY(r.getY() + 0.5);
			if (!v.contains((int) sx, (int) sy))
			{
				continue;
			}
			int f = flags(s, r);
			float a = alpha(s, r, f);
			if (a < 1)
			{
				g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, a));
			}
			boolean sel = (f & SELECTED) != 0;
			double rad = radius(v.getPpt(), sel);
			if (sel)
			{
				paintHalo(g, sx, sy, rad, s.selectedColor, s.now);
			}
			if (r.isDialable())
			{
				BufferedImage img = marker(f, rad, s);
				g.drawImage(img, (int) Math.round(sx - img.getWidth() / 2.0), (int) Math.round(sy - img.getHeight() / 2.0), null);
				if (r.getCode().equals(s.dialledCode()))
				{
					paintDialled(g, sx, sy, rad, s.selectedColor, s.now);
				}
			}
			else
			{
				paintInfoMarker(g, sx, sy, (f & HOVER) != 0);
			}
			if (r.getCode() != null && r.getCode().equals(s.here))
			{
				paintPin(g, sx, sy - rad - 3);
			}
			g.setComposite(old);
			int hr = (int) Math.ceil(rad + 3);
			String target = (r.getCode() == null ? "" : r.getCode() + " ") + s.repo.displayName(r);
			s.hits.add(new Hit(Hit.Kind.MARKER, new Rectangle((int) sx - hr, (int) sy - hr, hr * 2, hr * 2), r, null,
				r.isDialable() ? "Select" : "Zoom to", target));
		}

		// the X goes over the markers so a clue next to a ring stays readable
		paintClue(g, s, true);

		for (Placed p : codeLabels)
		{
			Ring r = (Ring) p.what;
			float a = alpha(s, r, flags(s, r));
			if (a < 1)
			{
				g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, a));
			}
			g.drawImage(labelSprite(s, r), p.at.x - 1, p.at.y - 1, null);
			g.setComposite(old);
		}
	}

	/** Draw and label order: plain rings first, then favourites, hovered and selected on top. */
	private int order(Scene s, Ring r)
	{
		int f = flags(s, r);
		return ((f & SELECTED) != 0 ? 8 : 0) + ((f & HOVER) != 0 ? 4 : 0) + ((f & FAVE) != 0 ? 2 : 0) + ((f & VISITED) != 0 ? 1 : 0);
	}

	private BufferedImage labelSprite(Scene s, Ring r)
	{
		int f = flags(s, r);
		Color c = !r.isDialable() ? INFO
			: (f & SELECTED) != 0 ? s.selectedColor
			: (f & FAVE) != 0 ? s.favouriteColor
			: (f & VISITED) != 0 ? Color.WHITE : UNVISITED;
		return ink.sprite(labelText(r), ink.small, c, Ink.Style.OUTLINE);
	}

	/** A ring's code, or the name of an entry that has none (the hideout, the Zanaris exits). */
	private static String labelText(Ring r)
	{
		return r.isDialable() ? r.getCode() : r.getName();
	}

	// ------------------------------------------------------------------ layout

	private void layout(Scene s)
	{
		MapView v = s.view;
		long key = Objects.hash(v, s.selected, s.hovered, s.codeLabels, s.placeLabels, s.topBar, s.panel, s.groupsPanel, s.holes,
			s.card, s.repo.getStateHash());
		if (key == layoutKey)
		{
			return;
		}
		layoutKey = key;
		codeLabels.clear();
		placeLabels.clear();
		portalLabels.clear();

		Rectangle bounds = new Rectangle(v.getX() + 2, v.getY() + 2, v.getW() - 4, v.getH() - 4);
		LabelPlacer placer = new LabelPlacer(bounds);
		placer.addObstacle(s.topBar);
		placer.addObstacle(s.panel);
		placer.addObstacle(s.groupsPanel);
		placer.addObstacle(s.card);
		placer.addObstacle(s.backButton);
		for (Rectangle h : s.blockers())
		{
			placer.addObstacle(grow(h, 4));
		}

		List<Ring> rings = s.repo.mappedIn(v.getLayer());
		for (Ring r : rings)
		{
			double rad = radius(v.getPpt(), r.getCode() != null && r.getCode().equals(s.selected));
			int sx = (int) Math.round(v.screenX(r.getX() + 0.5));
			int sy = (int) Math.round(v.screenY(r.getY() + 0.5));
			int q = (int) Math.ceil(rad + 1);
			placer.addObstacle(new Rectangle(sx - q, sy - q, q * 2, q * 2));
		}

		if (s.codeLabels != Scene.CodeLabels.NONE)
		{
			List<Ring> order = new ArrayList<>();
			for (Ring r : rings)
			{
				if (r.isDialable() || s.codeLabels == Scene.CodeLabels.ALL)
				{
					order.add(r);
				}
			}
			// dialable rings first, so a named entry never takes a code's place
			order.sort(Comparator.comparing((Ring r) -> !r.isDialable()).thenComparingInt(r -> -order(s, r))
				.thenComparing(AtlasPainter::labelText));
			int h = ink.height(ink.small);
			for (Ring r : order)
			{
				int f = flags(s, r);
				if (s.codeLabels == Scene.CodeLabels.FAVOURITES && (f & (FAVE | SELECTED | HOVER)) == 0)
				{
					continue;
				}
				double rad = radius(v.getPpt(), (f & SELECTED) != 0);
				int px = (int) v.screenX(r.getX() + 0.5);
				int py = (int) v.screenY(r.getY() + 0.5);
				if (covered(s.topBar, px, py) || covered(s.panel, px, py) || covered(s.groupsPanel, px, py) || covered(s.card, px, py)
					|| inHole(s, px, py))
				{
					// the marker is under the chrome; a label beside it would point at nothing
					continue;
				}
				Rectangle at = placer.place(v.screenX(r.getX() + 0.5), v.screenY(r.getY() + 0.5),
					ink.width(ink.small, labelText(r)), h, (int) Math.ceil(rad), 3);
				if (at != null)
				{
					codeLabels.add(new Placed(r, at, null));
				}
			}
		}

		if (Layer.SURFACE.equals(v.getLayer()))
		{
			int h = ink.height(ink.small);
			for (Portal p : s.repo.getPortals())
			{
				Layer l = s.repo.layer(p.getLayer());
				String text = p.getLabel() != null ? p.getLabel() : l == null ? p.getLayer() : l.getName();
				Rectangle at = placer.place(v.screenX(p.getX() + 0.5), v.screenY(p.getY() + 0.5),
					ink.width(ink.small, text), h, 8, 2);
				if (at != null)
				{
					portalLabels.add(new Placed(p, at, ink.sprite(text, ink.small, PORTAL, Ink.Style.OUTLINE)));
				}
			}
		}

		if (s.placeLabels)
		{
			List<MapIndex.Label> labels = new ArrayList<>(s.repo.getIndex().getLabels());
			labels.sort(Comparator.comparingInt(l -> -l.getS()));
			Layer layer = s.repo.layer(v.getLayer());
			for (MapIndex.Label l : labels)
			{
				if (l.getS() < 2 && v.getPpt() < (l.getS() == 1 ? 0.75 : 2))
				{
					continue;
				}
				boolean inLayer = l.getLayer() != null ? l.getLayer().equals(v.getLayer()) : layer != null && layer.contains(l.getX(), l.getY());
				if (!inLayer)
				{
					continue;
				}
				placeLabel(s, placer, l);
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
			placeLabels.add(new Placed(l, new Rectangle(x + (w - lw) / 2 - 1, y + i * lh - 1, img.getWidth(), img.getHeight()), img));
		}
	}

	// ------------------------------------------------------------------ markers

	BufferedImage marker(int flags, double rad, Scene s)
	{
		int ck = Objects.hash(s.visitedColor, s.selectedColor, s.favouriteColor);
		if (ck != colourKey)
		{
			markerSprites.clear();
			colourKey = ck;
		}
		long key = ((long) Math.round(rad * 4) << 8) | flags;
		BufferedImage img = markerSprites.get(key);
		if (img == null)
		{
			int size = (int) Math.ceil((rad + 7) * 2);
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

	void drawMarker(Graphics2D g, double cx, double cy, double r, int flags, Scene s)
	{
		boolean visited = (flags & VISITED) != 0;
		boolean selected = (flags & SELECTED) != 0;
		Color main = selected ? s.selectedColor : visited ? s.visitedColor : UNVISITED;

		g.setColor(new Color(0, 0, 0, 150));
		g.fill(circle(cx, cy, r + 2.6));
		if (visited || selected)
		{
			g.setColor(withAlpha(main, 120));
			g.setStroke(new BasicStroke(1.1f));
			g.draw(circle(cx, cy, r));
		}
		else
		{
			g.setColor(main);
			g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[]{2.2f, 2f}, 0));
			g.draw(circle(cx, cy, r));
		}
		double dot = visited || selected ? (selected ? 2.1 : 1.7) : 1.1;
		g.setColor(visited || selected ? main : UNVISITED_DOT);
		for (int i = 0; i < 8; i++)
		{
			double a = Math.PI / 4 * i + Math.PI / 8;
			g.fill(circle(cx + Math.cos(a) * r, cy + Math.sin(a) * r, dot));
		}
		if (visited)
		{
			g.setColor(withAlpha(main, 90));
			g.fill(circle(cx, cy, r * 0.45));
		}
		else
		{
			// not unlocked (not in the travel log yet): a padlock in the middle
			drawPadlock(g, cx, cy, Math.max(5, r * 0.95), selected ? main : LOCK);
		}
		if (selected)
		{
			g.setColor(Color.WHITE);
			g.setStroke(new BasicStroke(1.2f));
			g.draw(circle(cx, cy, r + 2.6));
		}
		if ((flags & HOVER) != 0)
		{
			g.setColor(Color.WHITE);
			g.setStroke(new BasicStroke(1.6f));
			g.draw(circle(cx, cy, r + 3.4));
		}
		if ((flags & FAVE) != 0)
		{
			drawStar(g, cx + r * 0.95, cy - r * 0.95, 3.9, s.favouriteColor);
		}
		if ((flags & LAST) != 0)
		{
			drawLastBadge(g, cx + r * 0.95, cy + r * 0.95, 3.7);
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

	private void paintDialled(Graphics2D g, double cx, double cy, double r, Color c, long now)
	{
		float phase = (now % 2000) / 2000f * 10f;
		Stroke old = g.getStroke();
		g.setColor(Ink.DARK);
		g.setStroke(new BasicStroke(3f));
		g.draw(circle(cx, cy, r + 6));
		g.setColor(c);
		g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[]{3f, 2f}, phase));
		g.draw(circle(cx, cy, r + 6));
		g.setStroke(old);
	}

	private void paintInfoMarker(Graphics2D g, double cx, double cy, boolean hover)
	{
		Path2D d = new Path2D.Double();
		d.moveTo(cx, cy - 5);
		d.lineTo(cx + 5, cy);
		d.lineTo(cx, cy + 5);
		d.lineTo(cx - 5, cy);
		d.closePath();
		g.setColor(Ink.DARK);
		g.setStroke(new BasicStroke(3f));
		g.draw(d);
		g.setColor(INFO);
		g.fill(d);
		if (hover)
		{
			g.setColor(Color.WHITE);
			g.setStroke(new BasicStroke(1.4f));
			g.draw(circle(cx, cy, 8));
		}
	}

	/** A "you are here" pin whose tip touches (x, y). */
	static void paintPin(Graphics2D g, double x, double y)
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
		g.fill(new java.awt.geom.RoundRectangle2D.Double(cx - w / 2 - 0.8, by - 0.8, w + 1.6, bh + 1.6, 2, 2));
		g.setColor(c);
		g.draw(shackle);
		g.fill(new java.awt.geom.RoundRectangle2D.Double(cx - w / 2, by, w, bh, 1.5, 1.5));
		g.setColor(Ink.DARK);
		g.fill(circle(cx, by + bh * 0.45, Math.max(0.6, w * 0.1)));
	}

	static void drawStar(Graphics2D g, double cx, double cy, double r, Color c)
	{
		Path2D p = new Path2D.Double();
		for (int i = 0; i < 10; i++)
		{
			double a = -Math.PI / 2 + i * Math.PI / 5;
			double rr = i % 2 == 0 ? r : r * 0.45;
			if (i == 0)
			{
				p.moveTo(cx + Math.cos(a) * rr, cy + Math.sin(a) * rr);
			}
			else
			{
				p.lineTo(cx + Math.cos(a) * rr, cy + Math.sin(a) * rr);
			}
		}
		p.closePath();
		g.setColor(Ink.DARK);
		g.setStroke(new BasicStroke(1.6f));
		g.draw(p);
		g.setColor(c);
		g.fill(p);
	}

	/** A small round badge with a circular arrow: "last destination". */
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

	/**
	 * A curved rotate arrow: clockwise or not, centred on (cx, cy) with radius r.
	 */
	static void drawRotateArrow(Graphics2D g, double cx, double cy, double r, boolean clockwise, Color c, float width)
	{
		double start = clockwise ? 150 : 30;
		double extent = clockwise ? -120 : 120;
		Arc2D arc = new Arc2D.Double(cx - r, cy - r, r * 2, r * 2, start, extent, Arc2D.OPEN);
		double endA = Math.toRadians(start + extent);
		double ex = cx + Math.cos(endA) * r;
		double ey = cy - Math.sin(endA) * r;
		// tangent direction of travel at the end of the arc
		double dir = endA + (clockwise ? -Math.PI / 2 : Math.PI / 2);
		double tx = Math.cos(dir);
		double ty = -Math.sin(dir);
		double hs = width * 2.6 + 2;
		Path2D head = new Path2D.Double();
		head.moveTo(ex + tx * hs, ey + ty * hs);
		head.lineTo(ex - ty * hs * 0.7, ey + tx * hs * 0.7);
		head.lineTo(ex + ty * hs * 0.7, ey - tx * hs * 0.7);
		head.closePath();
		g.setColor(Ink.DARK);
		g.setStroke(new BasicStroke(width + 2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.draw(arc);
		g.draw(head);
		g.setColor(c);
		g.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.draw(arc);
		g.fill(head);
	}

	static void drawCheck(Graphics2D g, double cx, double cy, double s, Color c)
	{
		Path2D p = new Path2D.Double();
		p.moveTo(cx - s, cy);
		p.lineTo(cx - s * 0.3, cy + s * 0.7);
		p.lineTo(cx + s, cy - s * 0.7);
		g.setColor(Ink.DARK);
		g.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.draw(p);
		g.setColor(c);
		g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.draw(p);
	}

	// ------------------------------------------------------------------ icons, portals, clue

	private void paintIcons(Graphics2D g, Scene s)
	{
		MapView v = s.view;
		Layer layer = s.repo.layer(v.getLayer());
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

	private void paintPortals(Graphics2D g, Scene s)
	{
		MapView v = s.view;
		for (Portal p : s.repo.getPortals())
		{
			double sx = v.screenX(p.getX() + 0.5);
			double sy = v.screenY(p.getY() + 0.5);
			if (!v.contains((int) sx, (int) sy))
			{
				continue;
			}
			Rectangle box = new Rectangle((int) sx - 7, (int) sy - 7, 14, 14);
			g.setColor(Ink.DARK);
			g.fillRoundRect(box.x - 1, box.y - 1, box.width + 2, box.height + 2, 5, 5);
			g.setColor(new Color(0x3B2E52));
			g.fillRoundRect(box.x, box.y, box.width, box.height, 4, 4);
			g.setColor(PORTAL);
			g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
			Path2D stairs = new Path2D.Double();
			stairs.moveTo(sx - 4.5, sy - 3.5);
			stairs.lineTo(sx - 1.5, sy - 3.5);
			stairs.lineTo(sx - 1.5, sy - 0.5);
			stairs.lineTo(sx + 1.5, sy - 0.5);
			stairs.lineTo(sx + 1.5, sy + 2.5);
			stairs.lineTo(sx + 4.5, sy + 2.5);
			g.draw(stairs);
			Layer l = s.repo.layer(p.getLayer());
			String name = l == null ? p.getLayer() : l.getName();
			s.hits.add(new Hit(Hit.Kind.PORTAL, grow(box, 2), null, p.getLayer(), "Open", name));
		}
		for (Placed p : portalLabels)
		{
			g.drawImage(p.sprite, p.at.x - 1, p.at.y - 1, null);
		}
	}

	/** The dashed line to the nearest visited ring, or (mark) the X itself. */
	private void paintClue(Graphics2D g, Scene s, boolean mark)
	{
		if (Double.isNaN(s.clueX))
		{
			return;
		}
		MapView v = s.view;
		Layer layer = s.repo.layer(v.getLayer());
		if (layer == null || !layer.contains(s.clueX, s.clueY))
		{
			return;
		}
		double cx = v.screenX(s.clueX + 0.5);
		double cy = v.screenY(s.clueY + 0.5);
		Ring best = null;
		double bestD = Double.MAX_VALUE;
		for (Ring r : s.repo.ringsIn(v.getLayer()))
		{
			if (!s.repo.isVisited(r.getCode()))
			{
				continue;
			}
			double d = Math.hypot(r.getX() - s.clueX, r.getY() - s.clueY);
			if (d < bestD)
			{
				bestD = d;
				best = r;
			}
		}
		if (best != null && !mark)
		{
			Line2D line = new Line2D.Double(cx, cy, v.screenX(best.getX() + 0.5), v.screenY(best.getY() + 0.5));
			g.setColor(new Color(0, 0, 0, 140));
			g.setStroke(new BasicStroke(3f));
			g.draw(line);
			g.setColor(new Color(0xFFE066));
			g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[]{5f, 4f}, 0));
			g.draw(line);
		}
		if (!mark || !v.contains((int) cx, (int) cy))
		{
			return;
		}
		double k = 5;
		Path2D x = new Path2D.Double();
		x.moveTo(cx - k, cy - k);
		x.lineTo(cx + k, cy + k);
		x.moveTo(cx + k, cy - k);
		x.lineTo(cx - k, cy + k);
		g.setColor(Ink.DARK);
		g.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.draw(x);
		g.setColor(CLUE);
		g.setStroke(new BasicStroke(2.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.draw(x);
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
