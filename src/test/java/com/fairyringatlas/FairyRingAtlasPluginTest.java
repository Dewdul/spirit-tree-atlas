package com.fairyringatlas;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class FairyRingAtlasPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(FairyRingAtlasPlugin.class);
		RuneLite.main(args);
	}
}
