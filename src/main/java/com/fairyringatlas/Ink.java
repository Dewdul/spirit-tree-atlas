/*
 * Copyright (c) 2026, Dewdul
 * All rights reserved.
 * Licensed under the BSD 2-Clause License. See LICENSE.
 */
package com.fairyringatlas;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.FontManager;

/**
 * Fonts and text drawing. RuneScape fonts are bitmap-style, so they are drawn without
 * antialiasing, and each outlined or shadowed string is rendered once into a small cached image:
 * drawing a label is then a single blit instead of five glyph runs.
 */
@Slf4j
final class Ink
{
	enum Style
	{
		OUTLINE,
		SHADOW,
		PLAIN,
	}

	private static final int CACHE_SIZE = 768;
	static final Color DARK = new Color(0x0c0a07);

	final Font small;
	final Font regular;
	final Font bold;
	private final Graphics2D measure;
	private final Map<Key, BufferedImage> cache = new LinkedHashMap<Key, BufferedImage>(256, 0.75f, true)
	{
		@Override
		protected boolean removeEldestEntry(Map.Entry<Key, BufferedImage> eldest)
		{
			return size() > CACHE_SIZE;
		}
	};

	Ink(Font small, Font regular, Font bold)
	{
		this.small = small;
		this.regular = regular;
		this.bold = bold;
		measure = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();
	}

	/** The RuneScape fonts, or plain Java fonts where FontManager is unavailable (previews). */
	static Ink create()
	{
		try
		{
			return new Ink(FontManager.getRunescapeSmallFont(), FontManager.getRunescapeFont(), FontManager.getRunescapeBoldFont());
		}
		catch (RuntimeException | LinkageError e)
		{
			log.debug("RuneScape fonts unavailable, using defaults", e);
			return new Ink(new Font(Font.DIALOG, Font.PLAIN, 10), new Font(Font.DIALOG, Font.PLAIN, 11), new Font(Font.DIALOG, Font.BOLD, 11));
		}
	}

	FontMetrics metrics(Font f)
	{
		return measure.getFontMetrics(f);
	}

	int width(Font f, String s)
	{
		return metrics(f).stringWidth(s);
	}

	/** Line height used for layout: ascent + descent. */
	int height(Font f)
	{
		FontMetrics fm = metrics(f);
		return fm.getAscent() + fm.getDescent();
	}

	/** Draws text with its top-left corner at (x, y). */
	void text(Graphics2D g, String s, Font f, Color c, int x, int y, Style style)
	{
		if (s == null || s.isEmpty())
		{
			return;
		}
		BufferedImage img = sprite(s, f, c, style);
		g.drawImage(img, x - 1, y - 1, null);
	}

	BufferedImage sprite(String s, Font f, Color c, Style style)
	{
		Key k = new Key(s, f, c.getRGB(), style);
		BufferedImage img = cache.get(k);
		if (img == null)
		{
			FontMetrics fm = metrics(f);
			int w = Math.max(1, fm.stringWidth(s) + 2);
			int h = fm.getAscent() + fm.getDescent() + 2;
			img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = img.createGraphics();
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
			g.setFont(f);
			int base = 1 + fm.getAscent();
			g.setColor(DARK);
			if (style == Style.OUTLINE)
			{
				g.drawString(s, 0, base);
				g.drawString(s, 2, base);
				g.drawString(s, 1, base - 1);
				g.drawString(s, 1, base + 1);
			}
			else if (style == Style.SHADOW)
			{
				g.drawString(s, 2, base + 1);
			}
			g.setColor(c);
			g.drawString(s, 1, base);
			g.dispose();
			cache.put(k, img);
		}
		return img;
	}

	/** Cuts a string to fit a width, ending it with "...". */
	String fit(String s, Font f, int maxW)
	{
		FontMetrics fm = metrics(f);
		if (s == null || fm.stringWidth(s) <= maxW)
		{
			return s;
		}
		String dots = "...";
		int end = s.length();
		while (end > 0 && fm.stringWidth(s.substring(0, end).trim() + dots) > maxW)
		{
			end--;
		}
		return end == 0 ? "" : s.substring(0, end).trim() + dots;
	}

	/** Greedy word wrap. */
	List<String> wrap(String s, Font f, int maxW)
	{
		List<String> out = new ArrayList<>();
		if (s == null || s.isEmpty())
		{
			return out;
		}
		FontMetrics fm = metrics(f);
		StringBuilder line = new StringBuilder();
		for (String word : s.split(" "))
		{
			String next = line.length() == 0 ? word : line + " " + word;
			if (fm.stringWidth(next) <= maxW || line.length() == 0)
			{
				line.setLength(0);
				line.append(next);
			}
			else
			{
				out.add(line.toString());
				line.setLength(0);
				line.append(word);
			}
		}
		if (line.length() > 0)
		{
			out.add(line.toString());
		}
		for (int i = 0; i < out.size(); i++)
		{
			out.set(i, fit(out.get(i), f, maxW));
		}
		return out;
	}

	void clear()
	{
		cache.clear();
	}

	private static final class Key
	{
		final String s;
		final Font f;
		final int c;
		final Style style;

		Key(String s, Font f, int c, Style style)
		{
			this.s = s;
			this.f = f;
			this.c = c;
			this.style = style;
		}

		@Override
		public boolean equals(Object o)
		{
			if (!(o instanceof Key))
			{
				return false;
			}
			Key k = (Key) o;
			return c == k.c && style == k.style && s.equals(k.s) && f.equals(k.f);
		}

		@Override
		public int hashCode()
		{
			return Objects.hash(s, f, c, style);
		}
	}
}
