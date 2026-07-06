package com.sonicether.soundphysics;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;

/**
 * Server-safe {@code @Mod} entry point for Sound Physics.
 *
 * <p>Sound Physics is a client-only mod: on a dedicated server it must be a complete
 * no-op. FML constructs the {@code @Mod} class on BOTH sides, and modern JVMs resolve
 * referenced types eagerly during class verification — so this class references ZERO
 * client-only, paulscode or org.lwjgl types. All the real work lives in {@link SoundPhysics}
 * and is reached only through the {@link ClientProxy} (client) side of {@link #proxy};
 * the {@link CommonProxy} (server) side does nothing.
 *
 * <p>Referencing {@code SoundPhysics.modid/modName/version/mcVersion} in the annotation is
 * safe: they are compile-time {@code static final String} constants and get inlined here,
 * leaving no runtime class reference to {@link SoundPhysics} in this class' constant pool.
 *
 * <p>{@code acceptableRemoteVersions = "*"} is required: a client-only mod must not force
 * itself onto the server side of the connection handshake.
 */
@Mod(modid = SoundPhysics.modid, name = SoundPhysics.modName, acceptedMinecraftVersions = SoundPhysics.mcVersion, version = SoundPhysics.version, guiFactory = "com.sonicether.soundphysics.SPGuiFactory",
	acceptableRemoteVersions = "*",
	dependencies = "before:computronics;required-after:gtnhmixins@[2.0.0,);after:gtnhvoice") // 'before:computronics' loads SP's config before patching Computronics; 'after:gtnhvoice' (optional, not required) makes SP init run after gtnh-voice so its client API backend is ready when the VoiceIntegration bridge fires
public class SoundPhysicsMod {

	@SidedProxy(clientSide = "com.sonicether.soundphysics.ClientProxy", serverSide = "com.sonicether.soundphysics.CommonProxy")
	public static CommonProxy proxy;

	@Mod.EventHandler
	public void preInit(final FMLPreInitializationEvent event) {
		proxy.preInit(event);
	}

	@Mod.EventHandler
	public void init(final FMLInitializationEvent event) {
		proxy.init(event);
	}
}
