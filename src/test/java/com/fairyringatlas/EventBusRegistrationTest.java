/*
 * Copyright (c) 2026, Dewdul
 * All rights reserved.
 * Licensed under the BSD 2-Clause License. See LICENSE.
 */
package com.fairyringatlas;

import net.runelite.client.eventbus.EventBus;
import org.junit.Test;

/**
 * RuneLite's event bus rejects a subscriber whose handlers break its rules (one handler per event,
 * named on&lt;Event&gt;), and then the whole plugin fails to start. Register everything the way the
 * client does so such a mistake fails here instead.
 */
public class EventBusRegistrationTest
{
	@Test
	public void subscribersRegister()
	{
		EventBus bus = new EventBus();
		FairyRingAtlasPlugin plugin = new FairyRingAtlasPlugin();
		bus.register(plugin);
		bus.unregister(plugin);
		RingMenuNames names = new RingMenuNames(null, null, () -> null);
		bus.register(names);
		bus.unregister(names);
	}
}
