package com.spirittreeatlas;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class SpiritTreeAtlasPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(SpiritTreeAtlasPlugin.class);
		RuneLite.main(args);
	}
}
