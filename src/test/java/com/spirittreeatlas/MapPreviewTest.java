/*
 * Copyright (c) 2026, Dewdul
 * All rights reserved.
 * Licensed under the BSD 2-Clause License. See LICENSE.
 */
package com.spirittreeatlas;

import java.io.File;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeNotNull;
import static org.junit.Assume.assumeTrue;
import org.junit.Test;

/**
 * Runs MapPreview when STA_PREVIEW is set: {@code STA_PREVIEW=1 ./gradlew test --tests MapPreviewTest}
 * (or {@code ./gradlew preview}).
 */
public class MapPreviewTest
{
	@Test
	public void renderPreviews() throws Exception
	{
		assumeNotNull(System.getenv("STA_PREVIEW"));
		assumeTrue(MapPreviewTest.class.getResource(SpiritTreeAtlasPlugin.RESOURCES + "trees.json") != null);
		File out = new File("build/preview");
		MapPreview.main(new String[]{out.getPath()});
		for (String name : MapPreview.names())
		{
			assertTrue(name, new File(out, name + ".png").isFile());
		}
	}
}
