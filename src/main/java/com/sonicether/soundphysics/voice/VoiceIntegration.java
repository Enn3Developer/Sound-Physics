package com.sonicether.soundphysics.voice;

import com.enn3developer.gtnhvoice.api.client.GtnhVoiceClientApi;
import com.enn3developer.gtnhvoice.api.client.IClientAudioApi;
import com.enn3developer.gtnhvoice.api.client.ISourceMetadata;
import com.sonicether.soundphysics.SoundEnvironment;
import com.sonicether.soundphysics.SoundPhysics;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Vec3;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Optional gtnh-voice proximity-chat occlusion integration. This package is the
 * only place in the mod that imports {@code com.enn3developer.gtnhvoice.**}, and
 * it is class-loaded only when gtnh-voice is present (via the single guarded call
 * in {@link SoundPhysics#init(cpw.mods.fml.common.event.FMLInitializationEvent)}).
 *
 * <p>Two threads cooperate through the maps below:
 * <ul>
 *   <li>the voice AUDIO thread owns {@link #handleByUuid} (written from the
 *       lifecycle listener's {@code sourceCreated}/{@code sourceDestroying}) and
 *       consumes {@link #envByUuid} in {@code audioTick};</li>
 *   <li>the CLIENT TICK thread reads {@link #handleByUuid} to know which speakers
 *       exist and writes {@link #envByUuid} with freshly computed occlusion.</li>
 * </ul>
 * Both maps are {@link ConcurrentHashMap} for that reason. This class touches no
 * {@code org.lwjgl} API (all AL work lives in {@link VoiceLifecycleListener} and
 * {@link com.sonicether.soundphysics.EfxPipeline}), so it needs no
 * {@code @Lwjgl3Aware}.
 */
public final class VoiceIntegration {

	// speaker UUID -> AL source handle. Written on the voice audio thread, read on
	// the client tick thread.
	static final ConcurrentHashMap<UUID, Integer> handleByUuid = new ConcurrentHashMap<>();

	// speaker UUID -> latest computed occlusion. Written on the client tick thread,
	// read on the voice audio thread.
	static final ConcurrentHashMap<UUID, SoundEnvironment> envByUuid = new ConcurrentHashMap<>();

	private VoiceIntegration() {}

	/**
	 * Registers the durable voice lifecycle bundle and the client-tick compute
	 * handler. Called once at FML init; gtnh-voice replays current context and
	 * live sources to us on registration, so no reconnect handling is needed.
	 */
	public static void register() {
		GtnhVoiceClientApi.audio()
				.register("soundphysics")
				.lifecycle(new VoiceLifecycleListener(handleByUuid, envByUuid))
				.done();
		FMLCommonHandler.instance().bus().register(new VoiceIntegration());
		SoundPhysics.log("Voice: registered gtnh-voice proximity occlusion integration.");
	}

	/**
	 * Client-tick compute: for every tracked speaker, query its spatial snapshot
	 * and store an occlusion-only environment for {@code audioTick} to apply. This
	 * is where the (potentially raycast-heavy) work happens — never in the audio
	 * pump.
	 */
	@SubscribeEvent
	public void onClientTick(final TickEvent.ClientTickEvent event) {
		if (event.phase != TickEvent.Phase.END) return;

		final Minecraft mc = Minecraft.getMinecraft();
		if (mc.thePlayer == null || mc.theWorld == null) return;
		if (handleByUuid.isEmpty()) return;

		final IClientAudioApi audio = GtnhVoiceClientApi.audio();
		final Vec3 playerEyePos = Vec3.createVectorHelper(mc.thePlayer.posX,
				mc.thePlayer.posY + mc.thePlayer.getEyeHeight(), mc.thePlayer.posZ);

		for (final UUID sourceId : handleByUuid.keySet()) {
			final Optional<ISourceMetadata> metadata = audio.sourceMetadata(sourceId);
			if (!metadata.isPresent()) continue;

			final ISourceMetadata meta = metadata.get();
			if (!meta.positional()) {
				// Flat/group voice must get NO effects
				envByUuid.put(sourceId, SoundEnvironment.passthrough());
				continue;
			}

			// (0,0,0) means a brand-new source (~first 20 ms) with no real
			// position yet; wait for a real one before raycasting.
			if (meta.x() == 0.0 && meta.y() == 0.0 && meta.z() == 0.0) continue;

			final Vec3 speakerPos = Vec3.createVectorHelper(meta.x(), meta.y(), meta.z());
			envByUuid.put(sourceId, SoundPhysics.computeVoiceEnvironment(mc.theWorld, playerEyePos, speakerPos));
		}
	}
}
