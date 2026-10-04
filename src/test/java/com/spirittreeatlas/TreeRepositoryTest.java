/*
 * Copyright (c) 2026, Dewdul
 * All rights reserved.
 * Licensed under the BSD 2-Clause License. See LICENSE.
 */
package com.spirittreeatlas;

import com.google.gson.Gson;
import java.awt.Rectangle;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/** The repository against the fixtures (DESIGN 3.4), and the Scene's pure decisions (DESIGN 4.4-4.6). */
public class TreeRepositoryTest
{
	private static final String FIXTURES = "/fixtures/";

	private static TreeRepository repo()
	{
		TreeRepository r = TreeRepository.load(new Gson(), FIXTURES);
		assertTrue(r.isTreesLoaded());
		assertTrue(r.isIndexLoaded());
		return r;
	}

	private static TreeMenu.Row row(int index, String key, String treeId, boolean grey)
	{
		return new TreeMenu.Row(index, key, treeId, grey, treeId);
	}

	@Test
	public void loads()
	{
		TreeRepository r = repo();
		assertEquals(14, r.getTrees().size());
		assertEquals("Spirit Tree Locations", r.getTitle());
		assertEquals("5f5f5f", r.getUnavailableColour());
		assertEquals(Arrays.asList("surface", "prifddinas"), ids(r.getLayers()));
		assertEquals("Test Land", r.surface().getName());
		assertEquals("prifddinas", r.layerAt(3274.5, 6124.5).getId());
		assertNull(r.layerAt(10, 10));
		assertEquals(1, r.getPortals().size());
		assertEquals(3, r.getHousePortals().size());
		assertEquals(1, r.getGlobalRequirements().size());
		assertEquals(2, r.getIndex().getLabels().get(1).getLines().length);
		assertEquals(1448, r.getIndex().getIcons().get(0).getSprite());
		Tree ge = r.tree("GRAND_EXCHANGE");
		assertEquals(3184.5, ge.getX(), 0);
		assertEquals(4, ge.getPreviousValue());
		assertTrue(ge.isMapped());
		assertFalse(ge.isPrefix());
		assertTrue(r.house().isPrefix());
		// the house is off the map until its portal is known
		assertFalse(r.house().isMapped());
		assertEquals("Your house", r.house().getName());
		assertEquals(12, r.mappedIn(Layer.SURFACE).size());
		assertEquals(1, r.mappedIn(Layer.PRIFDDINAS).size());
	}

	private static List<String> ids(List<Layer> layers)
	{
		List<String> out = new ArrayList<>();
		for (Layer l : layers)
		{
			out.add(l.getId());
		}
		return out;
	}

	@Test
	public void missingResourcesStillWork()
	{
		TreeRepository r = TreeRepository.load(new Gson(), "/nowhere/");
		assertFalse(r.isTreesLoaded());
		assertFalse(r.isIndexLoaded());
		assertTrue(r.getTrees().isEmpty());
		assertEquals("Spirit Tree Locations", r.getTitle());
		// the default layers: the plain surface and Prifddinas
		assertEquals(Arrays.asList("surface", "prifddinas"), ids(r.getLayers()));
		assertTrue(r.standIns().isEmpty());
		r.placeHouse(1);
		r.locate(3184, 3509, 0, true);
		assertNull(r.getHere());
		assertFalse(new TileStore(r.getIndex(), r.getLayers(), "/nowhere/", Runnable::run).hasImagery());
	}

	@Test
	public void availabilityFromTheRows()
	{
		TreeRepository r = repo();
		int hash = r.getStateHash();
		r.applyRows(Arrays.asList(row(3, "4", "GRAND_EXCHANGE", false), row(5, "6", "PRIFDDINAS", true), new TreeMenu.Row(14, "F", "Cancel", false, null)));
		assertTrue(hash != r.getStateHash());
		assertEquals(Tree.Status.AVAILABLE, r.status("GRAND_EXCHANGE"));
		assertEquals(Tree.Status.LOCKED, r.status("PRIFDDINAS"));
		assertEquals(Tree.Status.ABSENT, r.status("HOSIDIUS"));
		assertEquals(Tree.Status.ABSENT, r.status(null));
		assertEquals("4", r.key("GRAND_EXCHANGE"));
		assertNull(r.key("HOSIDIUS"));
		assertEquals(3, r.row("GRAND_EXCHANGE").getIndex());
	}

	@Test
	public void youAreHere()
	{
		TreeRepository r = repo();
		// the Grand Exchange tree's centre is 3184.5,3509.5: 6 tiles away is still here, 7 is not
		r.locate(3190, 3509, 0, false);
		assertEquals("GRAND_EXCHANGE", r.getHere());
		r.locate(3192, 3509, 0, false);
		assertNull(r.getHere());
		r.locate(3184, 3509, 1, false);
		assertNull(r.getHere());
		// Prifddinas is real world coordinates on its own layer
		r.locate(3270, 6120, 0, false);
		assertEquals("PRIFDDINAS", r.getHere());
		// in an instance (the house) with no tree near: the house
		r.locate(1900, 5700, 0, true);
		assertEquals("YOUR_HOUSE", r.getHere());
		// standing at the house portal is not standing at the house's tree
		r.placeHouse(1);
		r.locate(2951, 3224, 0, false);
		assertNull(r.getHere());
	}

	@Test
	public void lastTrip()
	{
		TreeRepository r = repo();
		r.setLast(4);
		assertEquals("GRAND_EXCHANGE", r.getLast());
		r.setLast(12);
		assertEquals("YOUR_HOUSE", r.getLast());
		r.setLast(0);
		assertNull(r.getLast());
		r.setLast(99);
		assertNull(r.getLast());
	}

	@Test
	public void theHouseSitsAtItsPortal()
	{
		TreeRepository r = repo();
		Tree house = r.house();
		r.placeHouse(1);
		assertTrue(house.isMapped());
		assertEquals(Layer.SURFACE, house.getLayer());
		assertEquals(2951.5, house.getX(), 0);
		assertEquals("Your house (Rimmington)", house.getName());
		assertEquals("Your house (Rimmington)", house.getLabel());
		assertEquals(1, r.getHouseValue());
		// in Prifddinas: on that layer, and so a stand-in on the surface too
		r.placeHouse(9);
		assertEquals(Layer.PRIFDDINAS, house.getLayer());
		assertEquals("Your house (Prifddinas)", house.getName());
		// unknown values (no house, or a town the data lacks) take it off the map
		r.placeHouse(0);
		assertFalse(house.isMapped());
		assertEquals("Your house", house.getName());
		r.placeHouse(13);
		assertFalse(house.isMapped());
	}

	@Test
	public void standInsAreSpreadAtTheirPortal()
	{
		TreeRepository r = repo();
		List<TreeRepository.StandIn> one = r.standIns();
		assertEquals(1, one.size());
		assertEquals("PRIFDDINAS", one.get(0).getTree().getId());
		assertEquals(0, one.get(0).getSlot(), 0);
		r.placeHouse(9);
		List<TreeRepository.StandIn> two = r.standIns();
		assertEquals(2, two.size());
		assertEquals(-0.5, two.get(0).getSlot(), 0);
		assertEquals(0.5, two.get(1).getSlot(), 0);
		assertEquals("YOUR_HOUSE", two.get(1).getTree().getId());
		MapView v = MapView.of(r.surface(), new Rectangle(0, 0, 400, 300)).centerOn(2240.5, 3328.5, 1);
		assertEquals(TreeRepository.SPREAD, two.get(1).screenX(v) - two.get(0).screenX(v), 1e-9);
		assertEquals(v.screenX(2240.5), (two.get(0).screenX(v) + two.get(1).screenX(v)) / 2, 1e-9);
		assertEquals(two.get(0).screenY(v), two.get(1).screenY(v), 0);
		// what Fit fits: the surface trees and the portal once per stand-in
		List<Point2D> points = r.surfaceMarkers();
		assertEquals(12 + 2, points.size());
		assertTrue(points.contains(new Point2D.Double(2240, 3328)));
	}

	// ------------------------------------------------------------------ Scene decisions

	@Test
	public void travelRowShowsOnlyWhenUsable()
	{
		TreeMenu.Row ge = row(3, "4", "GRAND_EXCHANGE", false);
		assertTrue(Scene.rowShown("GRAND_EXCHANGE", ge, null));
		assertTrue(Scene.rowShown("GRAND_EXCHANGE", ge, "HOSIDIUS"));
		assertFalse(Scene.rowShown(null, ge, null));
		assertFalse(Scene.rowShown("GRAND_EXCHANGE", null, null));
		assertFalse(Scene.rowShown("GRAND_EXCHANGE", row(3, "4", "GRAND_EXCHANGE", true), null));
		assertFalse(Scene.rowShown("GRAND_EXCHANGE", ge, "GRAND_EXCHANGE"));
		// the row now says something else (re-texted, or a rebuild moved the trees)
		assertFalse(Scene.rowShown("GRAND_EXCHANGE", row(3, "4", "FELDIP_HILLS", false), null));
	}

	@Test
	public void theStandInSaysWhy()
	{
		TreeRepository r = repo();
		Tree prif = r.tree("PRIFDDINAS");
		assertEquals("Pick a tree on the map", Scene.standInText(null, null, null));
		assertEquals("Locked: " + prif.getLockedHint(), Scene.standInText(prif, row(5, "6", "PRIFDDINAS", true), null));
		assertEquals("You are here", Scene.standInText(prif, row(5, "6", "PRIFDDINAS", false), "PRIFDDINAS"));
		assertEquals("Not in this tree's list", Scene.standInText(prif, null, null));
	}

	@Test
	public void cardLines()
	{
		TreeRepository r = repo();
		Tree ge = r.tree("GRAND_EXCHANGE");
		assertEquals("Click Travel, or press 4.", Scene.nextStep(ge, Tree.Status.AVAILABLE, "4", null));
		assertEquals("Click Travel.", Scene.nextStep(ge, Tree.Status.AVAILABLE, null, null));
		assertEquals("Not available yet: " + ge.getLockedHint() + ".", Scene.nextStep(ge, Tree.Status.LOCKED, "4", null));
		assertEquals("You are at this tree.", Scene.nextStep(ge, Tree.Status.AVAILABLE, "4", "GRAND_EXCHANGE"));
		assertEquals("This tree is not in the list here.", Scene.nextStep(ge, Tree.Status.ABSENT, null, null));
		assertEquals("Available", Scene.statusText(ge, Tree.Status.AVAILABLE, null));
		assertEquals("Locked - " + ge.getLockedHint(), Scene.statusText(ge, Tree.Status.LOCKED, null));
		assertEquals("You are here", Scene.statusText(ge, Tree.Status.AVAILABLE, "GRAND_EXCHANGE"));
		assertEquals("Not in the list", Scene.statusText(ge, Tree.Status.ABSENT, null));
	}

	@Test
	public void sceneFromTheRepository()
	{
		TreeRepository r = repo();
		r.applyRows(Arrays.asList(row(3, "4", "GRAND_EXCHANGE", false), row(5, "6", "PRIFDDINAS", true)));
		r.setLast(4);
		r.locate(3270, 6120, 0, false);
		Scene s = new Scene();
		s.fromRepository(r);
		assertEquals(14, s.trees.size());
		assertEquals(Tree.Status.AVAILABLE, s.status(s.tree("GRAND_EXCHANGE")));
		assertEquals(Tree.Status.LOCKED, s.status(s.tree("PRIFDDINAS")));
		assertEquals(Tree.Status.ABSENT, s.status(s.tree("HOSIDIUS")));
		assertEquals("4", s.key(s.tree("GRAND_EXCHANGE")));
		assertNull(s.key(s.tree("HOSIDIUS")));
		assertEquals("GRAND_EXCHANGE", s.last);
		assertEquals("PRIFDDINAS", s.here);
		assertEquals(1, s.standIns.size());
		assertNotNull(s.tree("YOUR_HOUSE"));
		// blockers: the holes plus the covered cell and its caption
		s.holes.add(new Rectangle(0, 0, 5, 5));
		s.rowCell = new Rectangle(10, 10, 5, 5);
		s.captionRect = new Rectangle(10, 0, 5, 5);
		assertEquals(3, s.blockers().size());
		s.holes.add(s.rowCell);
		assertEquals(3, s.blockers().size());
	}
}
