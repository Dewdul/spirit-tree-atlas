/*
 * Copyright (c) 2026, Dewdul
 * All rights reserved.
 * Licensed under the BSD 2-Clause License. See LICENSE.
 */
package com.spirittreeatlas;

import java.util.HashSet;
import java.util.Set;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class DialMathTest
{
	@Test
	public void letterOrdersAreTheVarbitOrders()
	{
		// dial 1 A,D,C,B; dial 2 I,L,K,J; dial 3 P,S,R,Q
		assertEquals('A', DialMath.letter(0, 0));
		assertEquals('D', DialMath.letter(0, 1));
		assertEquals('C', DialMath.letter(0, 2));
		assertEquals('B', DialMath.letter(0, 3));
		assertEquals('L', DialMath.letter(1, 1));
		assertEquals('J', DialMath.letter(1, 3));
		assertEquals('S', DialMath.letter(2, 1));
		assertEquals('Q', DialMath.letter(2, 3));
	}

	@Test
	public void codesRoundTripThroughValuesAndIndex()
	{
		Set<String> seen = new HashSet<>();
		for (int i = 0; i < 64; i++)
		{
			String code = DialMath.fromIndex(i);
			assertTrue(DialMath.isCode(code));
			assertEquals(i, DialMath.index(code));
			int[] v = DialMath.values(code);
			assertEquals(code, DialMath.code(v[0], v[1], v[2]));
			seen.add(code);
		}
		assertEquals(64, seen.size());
	}

	@Test
	public void indexMatchesTheGameTable()
	{
		// DB table: AIQ has multiloc state 3, ALP 4 (COL_ID 10), DKR 16+0... values in dial order
		assertEquals(3, DialMath.index("AIQ"));
		assertEquals(4, DialMath.index("ALP"));
		assertEquals(1, DialMath.index("AIS"));
		assertEquals(16 + 2 * 4 + 2, DialMath.index("DKR"));
	}

	@Test
	public void favouriteVarbitDecoding()
	{
		assertNull(DialMath.fromFavourite(0));
		assertNull(DialMath.fromFavourite(3));
		assertEquals("AIP", DialMath.fromFavourite(64));
		assertEquals("AIQ", DialMath.fromFavourite(64 | 3));
		assertEquals("BJQ", DialMath.fromFavourite(64 | (3 << 4) | (3 << 2) | 3));
	}

	@Test
	public void normalizeAcceptsSpacesAndCase()
	{
		assertEquals("AIQ", DialMath.normalize("a i q"));
		assertEquals("AIQ", DialMath.normalize("A I Q"));
		assertEquals("DLS", DialMath.normalize("dls"));
		assertNull(DialMath.normalize("AIX"));
		assertNull(DialMath.normalize("IAQ"));
		assertNull(DialMath.normalize(null));
		assertFalse(DialMath.isCode("AIQS"));
		assertEquals("A I Q", DialMath.spaced("AIQ"));
	}

	@Test
	public void rotationPlan()
	{
		assertEquals(0, DialMath.steps(2, 2));
		assertEquals(1, DialMath.steps(0, 1));
		assertEquals(2, DialMath.steps(3, 1));
		assertEquals(3, DialMath.steps(1, 0));
		assertEquals(1, DialMath.clicks(3));
		assertEquals(2, DialMath.clicks(2));
		assertTrue(DialMath.clockwise(1));
		assertTrue(DialMath.clockwise(2));
		assertFalse(DialMath.clockwise(3));

		// from A I P (0,0,0) to D K Q (1,2,3): 1 clockwise, 2 clockwise, 1 anticlockwise
		int[] plan = DialMath.plan(new int[]{0, 0, 0}, "DKQ");
		assertArrayEquals(new int[]{1, 2, 3}, plan);
		assertEquals(4, DialMath.totalClicks(plan));

		// any code from any state needs at most 6 clicks
		for (int from = 0; from < 64; from++)
		{
			for (int to = 0; to < 64; to++)
			{
				String f = DialMath.fromIndex(from);
				int[] p = DialMath.plan(DialMath.values(f), DialMath.fromIndex(to));
				assertTrue(DialMath.totalClicks(p) <= 6);
				// applying the plan lands on the target
				int[] v = DialMath.values(f);
				for (int d = 0; d < 3; d++)
				{
					int step = DialMath.clockwise(p[d]) ? 1 : 3;
					for (int c = 0; c < DialMath.clicks(p[d]); c++)
					{
						v[d] = (v[d] + step) % 4;
					}
				}
				assertEquals(DialMath.fromIndex(to), DialMath.code(v[0], v[1], v[2]));
			}
		}
	}
}
