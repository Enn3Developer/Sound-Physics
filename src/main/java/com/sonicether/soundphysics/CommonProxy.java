package com.sonicether.soundphysics;

import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;

/**
 * Common/server-side proxy: a complete no-op. Sound Physics does nothing on a
 * dedicated server, so this class — like {@link SoundPhysicsMod} — references ZERO
 * client-only, paulscode or org.lwjgl types. {@link ClientProxy} overrides these to
 * do the real client-side work.
 */
public class CommonProxy {

	public void preInit(final FMLPreInitializationEvent event) {
	}

	public void init(final FMLInitializationEvent event) {
	}
}
