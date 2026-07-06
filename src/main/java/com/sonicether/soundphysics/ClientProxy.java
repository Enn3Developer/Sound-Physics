package com.sonicether.soundphysics;

import com.sonicether.soundphysics.voice.VoiceIntegration;
import cpw.mods.fml.client.event.ConfigChangedEvent;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/**
 * Client-side proxy: performs everything the old {@code SoundPhysics} event handlers did.
 * Loaded only on the physical client (via {@link SoundPhysicsMod}'s {@code @SidedProxy}),
 * so — like the voice package — it is free to reference client classes.
 */
public class ClientProxy extends CommonProxy {

	@Override
	public void preInit(final FMLPreInitializationEvent event) {
		Config.instance.syncConfig();
		SoundPhysics.applyConfigChanges();
	}

	@Override
	public void init(final FMLInitializationEvent event) {
		// OnConfigChangedEvent is posted on the FML bus, so register there (the old
		// MinecraftForge.EVENT_BUS registration was a latent bug that made config-GUI
		// live-apply dead). No handler here needs the MinecraftForge event bus.
		FMLCommonHandler.instance().bus().register(this);

		// Sole bridge to the optional gtnh-voice integration. Guarded by the mod's
		// presence so nothing in the com.sonicether.soundphysics.voice package (which
		// alone imports gtnh-voice) is class-loaded when the mod is absent. This line
		// is the ONLY reference to the voice package from outside it.
		if (Loader.isModLoaded("gtnhvoice")) {
			VoiceIntegration.register();
		}
	}

	@SubscribeEvent
	public void onConfigChanged(final ConfigChangedEvent.OnConfigChangedEvent eventArgs) {
		if (!eventArgs.modID.equals(SoundPhysics.modid)) return;
		if (Config.instance.syncConfig()) {
			SoundPhysics.applyConfigChanges();
		}
	}
}
