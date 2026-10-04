/*
 * Copyright (c) 2026, Dewdul
 * All rights reserved.
 * Licensed under the BSD 2-Clause License. See LICENSE.
 */
package com.fairyringatlas;

import com.google.gson.Gson;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class ChromePainterTest
{
	private static final Gson GSON = new Gson();

	/** The visible rows of a group, top to bottom, as the painter publishes them for the mouse. */
	private static List<Hit> rows(Scene s, String list)
	{
		List<Hit> out = new ArrayList<>();
		for (Hit h : s.hits)
		{
			if (h.getKind() == Hit.Kind.CHIP && list.equals(h.getId()))
			{
				out.add(h);
			}
		}
		out.sort((a, b) -> Integer.compare(a.getArea().y, b.getArea().y));
		return out;
	}

	private static void paint(ChromePainter chrome, Scene s)
	{
		s.hits.clear();
		chrome.layout(s);
		BufferedImage img = new BufferedImage(600, 400, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		chrome.paint(g, s);
		g.dispose();
	}

	@Test
	public void aDropInAScrolledGroupLandsWhereTheLineShows()
	{
		RingRepository repo = RingRepository.load(GSON, FairyRingAtlasPlugin.RESOURCES);
		RingGroups groups = RingGroups.parse(GSON, RingGroups.defaults(
			RingRepository.read(GSON, FairyRingAtlasPlugin.RESOURCES + "groups.json", RingGroups.DefFile.class)), null);
		Scene s = new Scene();
		s.repo = repo;
		s.view = MapView.of(repo.surface(), new Rectangle(0, 0, 512, 334));
		s.dials = new int[]{0, 0, 0};
		s.panelCollapsed = true;
		s.groupsOpen = true;
		s.groups = groups.view();
		ChromePainter chrome = new ChromePainter(new AtlasPainter().ink());

		// the fixed-mode panel scrolled so Slayer's first rows are above it
		String list = Hit.groupRow("slayer");
		List<String> slayer = new ArrayList<>(s.groups.get(0).getCodes());
		List<Hit> visible;
		do
		{
			s.groupsScroll += 20;
			paint(chrome, s);
			visible = rows(s, list);
		}
		while ((visible.isEmpty() || slayer.indexOf(visible.get(0).getRing().getCode()) < 3) && s.groupsScroll < 2000);
		assertTrue(visible.size() >= 4);
		assertTrue(s.groupsScroll <= chrome.groupsScrollMax);

		// drag the last ring to between two visible rows
		String dragged = slayer.get(slayer.size() - 1);
		Hit above = visible.get(1);
		Hit below = visible.get(2);
		s.dragList = list;
		s.dragCode = dragged;
		s.dragY = (above.getArea().y + above.getArea().height + below.getArea().y) / 2;
		paint(chrome, s);
		ChromePainter.Drop drop = chrome.drop;
		assertEquals(list, drop.getList());
		assertEquals(dragged, drop.getCode());
		assertEquals(below.getRing().getCode(), drop.getBefore());

		assertTrue(groups.move("slayer", dragged, drop.getBefore()));
		List<String> moved = groups.view().get(0).getCodes();
		assertEquals(moved.indexOf(above.getRing().getCode()) + 1, moved.indexOf(dragged));

		// no drag, no drop
		s.dragCode = null;
		paint(chrome, s);
		assertEquals(null, chrome.drop);
	}
}
