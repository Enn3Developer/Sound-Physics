package com.sonicether.soundphysics;

import com.sonicether.soundphysics.instrumentation.DebugOverlay;
import com.sonicether.soundphysics.voice.VoiceIntegration;
import cpw.mods.fml.client.event.ConfigChangedEvent;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.client.Minecraft;
import net.minecraftforge.common.MinecraftForge;

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
		// OnConfigChangedEvent and ClientTickEvent are posted on the FML bus, so
		// register there (the old MinecraftForge.EVENT_BUS registration was a latent
		// bug that made config-GUI live-apply dead).
		FMLCommonHandler.instance().bus().register(this);

		// F3 debug lines live on the Forge bus (RenderGameOverlayEvent).
		MinecraftForge.EVENT_BUS.register(new DebugOverlay());

		// Engine core (shared GL context + worker) starts here on the client
		// thread; the sound-system mixin hook only (re)initializes EFX, because
		// it fires on the async Sound Library Loader thread.
		SoundPhysicsEngine.initEngine();

		// Sole bridge to the optional gtnh-voice integration. Guarded by the mod's
		// presence so nothing in the com.sonicether.soundphysics.voice package (which
		// alone imports gtnh-voice) is class-loaded when the mod is absent. This line
		// is the ONLY reference to the voice package from outside it.
		if (Loader.isModLoaded("gtnhvoice")) {
			VoiceIntegration.register();
		}
	}

	// Drives the CPU section cache and listener publication; the audio worker
	// never touches World.
	@SubscribeEvent
	public void onClientTick(final TickEvent.ClientTickEvent event) {
		if (event.phase != TickEvent.Phase.END) return;
		final SoundPhysicsEngine engine = SoundPhysicsEngine.instance();
		if (engine != null) engine.onClientTick(Minecraft.getMinecraft());
	}

	@SubscribeEvent
	public void onConfigChanged(final ConfigChangedEvent.OnConfigChangedEvent eventArgs) {
		if (!eventArgs.modID.equals(SoundPhysics.modid)) return;
		if (Config.instance.syncConfig()) {
			SoundPhysics.applyConfigChanges();
		}
	}
}
