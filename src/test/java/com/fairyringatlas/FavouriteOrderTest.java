/*
 * Copyright (c) 2026, Dewdul
 * All rights reserved.
 * Licensed under the BSD 2-Clause License. See LICENSE.
 */
package com.fairyringatlas;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class FavouriteOrderTest
{
	private static List<Ring> rings(String... codes)
	{
		List<Ring> out = new ArrayList<>();
		for (String c : codes)
		{
			out.add(new Ring(c, c, 3000, 3000, Layer.SURFACE));
		}
		return out;
	}

	private static List<String> codes(List<Ring> rings)
	{
		List<String> out = new ArrayList<>();
		for (Ring r : rings)
		{
			out.add(r.getCode());
		}
		return out;
	}

	@Test
	public void savedOrderFirstThenNewOnesInGameOrder()
	{
		List<Ring> game = rings("CIR", "CJQ", "CKS", "AIQ");
		// AIQ is a new favourite; ZZZ was unstarred since
		List<Ring> sorted = FavouriteOrder.sort(game, Arrays.asList("CKS", "ZZZ", "CIR"));
		assertEquals(Arrays.asList("CKS", "CIR", "CJQ", "AIQ"), codes(sorted));
		assertEquals(Arrays.asList("CIR", "CJQ", "CKS", "AIQ"), codes(FavouriteOrder.sort(game, Collections.emptyList())));
	}

	@Test
	public void moveInFrontOfAnother()
	{
		List<Ring> shown = rings("CIR", "CJQ", "CKS");
		assertEquals(Arrays.asList("CKS", "CIR", "CJQ"), FavouriteOrder.move(shown, "CKS", "CIR"));
		assertEquals(Arrays.asList("CJQ", "CKS", "CIR"), FavouriteOrder.move(shown, "CIR", null));
		assertEquals(Arrays.asList("CJQ", "CIR", "CKS"), FavouriteOrder.move(shown, "CIR", "CKS"));
		// an anchor not in the list means the end; an unknown code changes nothing
		assertEquals(Arrays.asList("CJQ", "CKS", "CIR"), FavouriteOrder.move(shown, "CIR", "ZZZ"));
		assertEquals(Arrays.asList("CIR", "CJQ", "CKS"), FavouriteOrder.move(shown, "AIQ", "CIR"));
	}

	@Test
	public void parseAndFormat()
	{
		assertEquals(Arrays.asList("CKS", "CIR"), FavouriteOrder.parse("CKS, cir,CKS,nope"));
		assertEquals(Collections.emptyList(), FavouriteOrder.parse(""));
		assertEquals("CKS,CIR", FavouriteOrder.format(Arrays.asList("CKS", "CIR")));
	}
}
