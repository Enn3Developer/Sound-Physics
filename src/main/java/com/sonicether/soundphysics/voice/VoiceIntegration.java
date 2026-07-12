package com.sonicether.soundphysics.voice;

import com.enn3developer.gtnhvoice.api.client.GtnhVoiceClient;
import com.enn3developer.gtnhvoice.api.client.ISourceMetadata;
import com.enn3developer.gtnhvoice.api.client.IVoiceAddon;
import com.sonicether.soundphysics.SoundEnvironment;
import com.sonicether.soundphysics.SoundPhysics;
import com.sonicether.soundphysics.SoundPhysicsEngine;
import cpw.mods.fml.client.event.ConfigChangedEvent;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Optional gtnh-voice proximity-chat reverb + occlusion integration. This
 * package is the only place in the mod that imports
 * {@code com.enn3developer.gtnhvoice.**}, and it is class-loaded only when
 * gtnh-voice is present (via the single guarded call in the ClientProxy).
 *
 * <p>Speakers are sources like any other: the client tick
 * feeds speaker positions into the engine's active-source registry, their
 * cells get batch priority while speaking, and the audio worker estimates
 * from the same store/estimator and writes results into {@link #envByUuid}
 * (the voice sink). The voice audio thread applies them in {@code audioTick}
 * via {@link VoiceLifecycleListener}. There is no dedicated ray budget — the
 * old per-tick ray rationing does not exist here.
 */
public final class VoiceIntegration {

	// speaker UUID -> AL source handle. Written on the voice audio thread, read on
	// the client tick thread.
	static final ConcurrentHashMap<UUID, Integer> handleByUuid = new ConcurrentHashMap<>();

	// speaker UUID -> latest estimated environment. Written by the audio worker
	// (positional) and the client tick (flat passthrough), read on the voice
	// audio thread.
	static final ConcurrentHashMap<UUID, SoundEnvironment> envByUuid = new ConcurrentHashMap<>();

	// Aux sends this integration REQUESTS per voice source (max the four-slot reverb
	// bus can drive). The context may grant fewer; VoiceLifecycleListener degrades.
	private static final int REQUESTED_SENDS = 4;

	// The addon handle everything flows through since v0.8.0: sourceMetadata on the
	// client tick, runOnAudioThread on config changes. Durable for the client lifetime.
	private final IVoiceAddon addon;

	// Held so onConfigChanged can re-apply reverb presets to the live voice pipeline.
	private final VoiceLifecycleListener lifecycle;

	// Client tick thread only: speakers currently registered with the engine,
	// diffed against handleByUuid so departed speakers leave the registry.
	private final HashSet<UUID> trackedSpeakers = new HashSet<>();
	private boolean sinkWired;

	private VoiceIntegration(final IVoiceAddon addon, final VoiceLifecycleListener lifecycle) {
		this.addon = addon;
		this.lifecycle = lifecycle;
	}

	/**
	 * Registers the addon, its durable voice lifecycle bundle and the client-tick
	 * position feed. Called once at FML init; gtnh-voice replays current context
	 * and live sources to us on registration, so no reconnect handling is needed.
	 */
	public static void register() {
		final VoiceLifecycleListener lifecycle = new VoiceLifecycleListener(handleByUuid, envByUuid);
		final IVoiceAddon addon = GtnhVoiceClient.addon("Sound Physics")
				.description("Proximity-chat reverb and occlusion")
				.register();
		addon.audio()
				.auxiliarySends(REQUESTED_SENDS)
				.lifecycle(lifecycle)
				.done();
		FMLCommonHandler.instance().bus().register(new VoiceIntegration(addon, lifecycle));
		SoundPhysics.log("Voice: registered gtnh-voice proximity reverb + occlusion integration.");
	}

	/**
	 * Client-tick feed: positional speakers go into the engine's registry
	 * (positions refreshed every tick — cheap map writes, no tracing here);
	 * flat/group voice gets a passthrough environment and stays out of the
	 * simulation entirely.
	 */
	@SubscribeEvent
	public void onClientTick(final TickEvent.ClientTickEvent event) {
		if (event.phase != TickEvent.Phase.END) return;
		final SoundPhysicsEngine engine = SoundPhysicsEngine.instance();
		if (engine == null) return; // engine unavailable → voice stays vanilla

		if (!sinkWired) {
			engine.setVoiceSink(envByUuid::put);
			sinkWired = true;
		}

		// Departed speakers (source destroyed, context torn down) leave the
		// engine registry.
		for (final Iterator<UUID> it = trackedSpeakers.iterator(); it.hasNext(); ) {
			final UUID id = it.next();
			if (handleByUuid.containsKey(id)) continue;
			engine.removeSpeaker(id);
			envByUuid.remove(id);
			it.remove();
		}

		for (final UUID sourceId : handleByUuid.keySet()) {
			final Optional<ISourceMetadata> metadata = addon.sourceMetadata(sourceId);
			if (!metadata.isPresent()) continue;

			final ISourceMetadata meta = metadata.get();
			if (!meta.positional()) {
				// Flat/group voice must get NO effects.
				envByUuid.put(sourceId, SoundEnvironment.passthrough());
				if (trackedSpeakers.remove(sourceId)) engine.removeSpeaker(sourceId);
				continue;
			}

			// (0,0,0) means a brand-new source (~first 20 ms) with no real
			// position yet; wait for a real one.
			if (meta.x() == 0.0 && meta.y() == 0.0 && meta.z() == 0.0) continue;

			engine.updateSpeaker(sourceId, meta.x(), meta.y(), meta.z());
			trackedSpeakers.add(sourceId);
		}
	}

	/**
	 * Config-GUI live refresh for voice reverb, mirroring
	 * {@code SoundPhysics.applyConfigChanges} for the game pipeline. Fires on the
	 * FML bus; filtered to soundphysics' own config. AL work is marshalled onto
	 * the voice audio thread — dropped when no voice session is live, which is
	 * fine: the next {@code contextCreated} loads the current presets anyway.
	 */
	@SubscribeEvent
	public void onConfigChanged(final ConfigChangedEvent.OnConfigChangedEvent event) {
		if (!event.modID.equals(SoundPhysics.modid)) return;
		addon.runOnAudioThread(lifecycle::reapplyReverbPresets);
	}
}
