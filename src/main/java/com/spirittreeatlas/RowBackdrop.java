/*
 * Copyright (c) 2026, Dewdul
 * All rights reserved.
 * Licensed under the BSD 2-Clause License. See LICENSE.
 */
package com.spirittreeatlas;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * A solid background for the Travel hole (DESIGN 4.3). Once the classic parchment is hidden, or
 * the modern frame hangs away, the real row has nothing solid behind it; this draws under the
 * widgets, so the game draws the row's own text over it. It draws nothing else and takes no input.
 */
@Singleton
public class RowBackdrop extends Overlay
{
	private static final Color PARCHMENT = new Color(0xD9C9A0);
	private static final Color PARCHMENT_EDGE = new Color(0x5A4A2A);
	private static final Color MODERN = new Color(0, 0, 0, 200);
	private static final Color MODERN_EDGE = new Color(0x3E3529);

	private final SpiritTreeAtlasPlugin plugin;

	@Inject
	RowBackdrop(SpiritTreeAtlasPlugin plugin)
	{
		this.plugin = plugin;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.UNDER_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		// the hole as the map overlay last drew it: set only while the real row shows in Map mode
		Rectangle h = plugin.getTravelHole();
		if (!plugin.isOpen() || h == null)
		{
			return null;
		}
		boolean classic = plugin.getMenu().getStyle() == TreeMenu.Style.CLASSIC;
		g.setColor(classic ? PARCHMENT : MODERN);
		g.fillRect(h.x, h.y, h.width, h.height);
		g.setColor(classic ? PARCHMENT_EDGE : MODERN_EDGE);
		g.drawRect(h.x, h.y, h.width - 1, h.height - 1);
		return null;
	}
}
