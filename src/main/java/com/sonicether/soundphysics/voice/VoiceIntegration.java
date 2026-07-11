package com.sonicether.soundphysics.voice;

import com.enn3developer.gtnhvoice.api.client.GtnhVoiceClient;
import com.enn3developer.gtnhvoice.api.client.ISourceMetadata;
import com.enn3developer.gtnhvoice.api.client.IVoiceAddon;
import com.sonicether.soundphysics.SoundEnvironment;
import com.sonicether.soundphysics.SoundPhysics;
import cpw.mods.fml.client.event.ConfigChangedEvent;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Optional gtnh-voice proximity-chat reverb + occlusion integration. This package
 * is the only place in the mod that imports {@code com.enn3developer.gtnhvoice.**},
 * and it is class-loaded only when gtnh-voice is present (via the single guarded
 * call in {@link SoundPhysics#init()}).
 *
 * <p>Two threads cooperate through the maps below:
 * <ul>
 *   <li>the voice AUDIO thread owns {@link #handleByUuid} (written from the
 *       lifecycle listener's {@code sourceCreated}/{@code sourceDestroying}) and
 *       consumes {@link #envByUuid} in {@code audioTick};</li>
 *   <li>the CLIENT TICK thread reads {@link #handleByUuid} to know which speakers
 *       exist and writes {@link #envByUuid} with freshly computed environments.</li>
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

	// speaker UUID -> latest computed environment. Written on the client tick thread,
	// read on the voice audio thread.
	static final ConcurrentHashMap<UUID, SoundEnvironment> envByUuid = new ConcurrentHashMap<>();

	// Aux sends this integration REQUESTS per voice source (max the four-slot reverb
	// bus can drive). The context may grant fewer; VoiceLifecycleListener degrades.
	private static final int REQUESTED_SENDS = 4;

	// The full golden-angle reverb ray cast is expensive, so cap how many speakers
	// get it per client tick and rotate which ones through computeCursor. Cheap
	// checks (positional/flat/no-position) still run every tick for every speaker.
	private static final int MAX_FULL_COMPUTES_PER_TICK = 2;
	private int computeCursor;

	// The addon handle everything flows through since v0.8.0: sourceMetadata on the
	// client tick, runOnAudioThread on config changes. Durable for the client lifetime.
	private final IVoiceAddon addon;

	// Held so onConfigChanged can re-apply reverb presets to the live voice pipeline.
	private final VoiceLifecycleListener lifecycle;

	private VoiceIntegration(final IVoiceAddon addon, final VoiceLifecycleListener lifecycle) {
		this.addon = addon;
		this.lifecycle = lifecycle;
	}

	/**
	 * Registers the addon, its durable voice lifecycle bundle and the client-tick
	 * compute handler. Called once at FML init; gtnh-voice replays current context
	 * and live sources to us on registration, so no reconnect handling is needed.
	 * The addon name is identity (unique, claimed for the client lifetime) and is
	 * shown in gtnh-voice's Addons settings tab, hence the display-quality name.
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
	 * Client-tick compute: for every tracked speaker, run the cheap classification
	 * (flat -> passthrough, no-position -> skip) and, for positional speakers,
	 * budget the raycast-heavy full environment compute round-robin across ticks.
	 * This is where the heavy work happens — never in the audio pump.
	 */
	@SubscribeEvent
	public void onClientTick(final TickEvent.ClientTickEvent event) {
		if (event.phase != TickEvent.Phase.END) return;

		final Minecraft mc = Minecraft.getMinecraft();
		if (mc.thePlayer == null || mc.theWorld == null) return;
		if (handleByUuid.isEmpty()) return;

		final Vec3 playerEyePos = Vec3.createVectorHelper(mc.thePlayer.posX,
				mc.thePlayer.posY + mc.thePlayer.getEyeHeight(), mc.thePlayer.posZ);

		// Cheap pass: classify every speaker every tick, collecting the positional
		// ones that need the full compute so we can budget them below.
		final List<SpeakerPos> pending = new ArrayList<>();

		for (final UUID sourceId : handleByUuid.keySet()) {
			final Optional<ISourceMetadata> metadata = addon.sourceMetadata(sourceId);
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

			pending.add(new SpeakerPos(sourceId, Vec3.createVectorHelper(meta.x(), meta.y(), meta.z())));
		}

		computeBudgeted(mc.theWorld, playerEyePos, pending);
	}

	// Full-compute budget: run at most MAX_FULL_COMPUTES_PER_TICK speakers this tick,
	// starting at computeCursor so a large speaker set is covered over several ticks
	// instead of starving the tail. Speakers not picked keep their cached env.
	private void computeBudgeted(final World world, final Vec3 playerEyePos, final List<SpeakerPos> pending) {
		if (pending.isEmpty()) return;

		final int budget = Math.min(MAX_FULL_COMPUTES_PER_TICK, pending.size());
		for (int i = 0; i < budget; i++) {
			final SpeakerPos speaker = pending.get((computeCursor + i) % pending.size());
			envByUuid.put(speaker.sourceId,
					SoundPhysics.computeVoiceEnvironment(world, playerEyePos, speaker.speakerPos));
		}
		computeCursor = (computeCursor + budget) % pending.size();
	}

	/**
	 * Config-GUI live refresh for voice reverb, mirroring
	 * {@code SoundPhysics.onConfigChanged} for the game pipeline. Fires on the FML
	 * bus (where {@code OnConfigChangedEvent} is posted, and where this integration
	 * is registered); filtered to soundphysics' own config. AL work is marshalled
	 * onto the voice audio thread — dropped when no voice session is live, which is
	 * fine: the next {@code contextCreated} loads the current presets anyway.
	 */
	@SubscribeEvent
	public void onConfigChanged(final ConfigChangedEvent.OnConfigChangedEvent event) {
		if (!event.modID.equals(SoundPhysics.modid)) return;
		addon.runOnAudioThread(lifecycle::reapplyReverbPresets);
	}

	// Immutable speaker id + resolved position, carried from the cheap classification
	// pass into the budgeted full compute.
	private static final class SpeakerPos {
		final UUID sourceId;
		final Vec3 speakerPos;

		SpeakerPos(final UUID sourceId, final Vec3 speakerPos) {
			this.sourceId = sourceId;
			this.speakerPos = speakerPos;
		}
	}
}
