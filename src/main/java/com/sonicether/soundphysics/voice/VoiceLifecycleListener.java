package com.sonicether.soundphysics.voice;

import com.enn3developer.gtnhvoice.api.client.IAudioLifecycleListener;
import com.sonicether.soundphysics.SoundEnvironment;
import com.sonicether.soundphysics.efx.EfxPipeline;
import com.sonicether.soundphysics.SoundPhysics;
import me.eigenraven.lwjgl3ify.api.Lwjgl3Aware;
import org.lwjgl.openal.ALC10;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * gtnh-voice lifecycle listener that owns one {@link EfxPipeline} per voice AL
 * context and applies reverb + occlusion to voice sources. Every callback runs on
 * the voice audio thread with the voice context current, so direct AL calls are
 * allowed here — hence {@code @Lwjgl3Aware} (this class touches {@code ALC10}).
 *
 * <p>Reverb enabled: {@code contextCreated} queries how many auxiliary sends the
 * voice context actually granted ({@code ALC_MAX_AUXILIARY_SENDS} — the host
 * requests four via {@code auxiliarySends(4)} but the ALC implementation may grant
 * fewer), caps the pipeline to that count with {@link EfxPipeline#limitSends}, then
 * calls {@code applyReverbPresets()} so the aux slots carry the four EAXREVERB
 * effects. A config-GUI change re-applies those presets via
 * {@link #reapplyReverbPresets} (marshalled onto this thread).
 */
@Lwjgl3Aware
public final class VoiceLifecycleListener implements IAudioLifecycleListener {

	private final ConcurrentHashMap<UUID, Integer> handleByUuid;
	private final ConcurrentHashMap<UUID, SoundEnvironment> envByUuid;

	// Set in contextCreated, cleared in contextDestroying. Only ever touched on
	// the voice audio thread, but volatile so a torn-down pipeline is never seen
	// half-published.
	private volatile EfxPipeline pipeline;

	VoiceLifecycleListener(final ConcurrentHashMap<UUID, Integer> handleByUuid,
			final ConcurrentHashMap<UUID, SoundEnvironment> envByUuid) {
		this.handleByUuid = handleByUuid;
		this.envByUuid = envByUuid;
	}

	@Override
	public void contextCreated(final long deviceHandle) {
		if (!ALC10.alcIsExtensionPresent(deviceHandle, "ALC_EXT_EFX")) {
			SoundPhysics.log("Voice: ALC_EXT_EFX absent on voice device; reverb/occlusion disabled for this context.");
			return;
		}

		// We requested four aux sends at registration; the context may have granted
		// fewer. Cap routing to what was granted (routing an ungranted send raises
		// AL_INVALID_VALUE), then load the reverb presets into the aux slots.
		final int grantedSends = EfxPipeline.queryGrantedSends(deviceHandle);
		pipeline = new EfxPipeline()
				.init(deviceHandle)
				.limitSends(grantedSends)
				.applyReverbPresets();
		SoundPhysics.log("Voice: reverb enabled, " + grantedSends + " aux sends granted.");
	}

	@Override
	public void contextDestroying() {
		// Context teardown destroys all its AL objects for us; just drop references.
		pipeline = null;
		handleByUuid.clear();
		envByUuid.clear();
	}

	@Override
	public void sourceCreated(final UUID sourceId, final int sourceHandle) {
		// No per-source filters: EfxPipeline.apply() rewrites and re-attaches its
		// shared filters per source per tick, exactly like the game path.
		handleByUuid.put(sourceId, sourceHandle);
	}

	@Override
	public void sourceDestroying(final UUID sourceId, final int sourceHandle) {
		handleByUuid.remove(sourceId);
		envByUuid.remove(sourceId);
	}

	/**
	 * Re-applies the reverb presets after a Config-GUI change, mirroring the game
	 * pipeline's {@code SoundPhysics.onConfigChanged}. Touches AL, so it MUST be
	 * submitted via {@code IVoiceAddon.runOnAudioThread(...)} to run here on the
	 * voice audio thread. No-ops when no voice context is live — the
	 * next {@code contextCreated} loads the current presets anyway.
	 */
	public void reapplyReverbPresets() {
		final EfxPipeline p = pipeline;
		if (p == null) return;
		p.applyReverbPresets();
		SoundPhysics.log("Voice: reverb presets re-applied after config change.");
	}

	@Override
	public void audioTick() {
		final EfxPipeline p = pipeline;
		if (p == null) return;

		// Cheap AL writes only — this runs inside the ~5 ms audio pump. The env was
		// computed off-thread on the client tick; here we just apply it.
		for (final Map.Entry<UUID, Integer> entry : handleByUuid.entrySet()) {
			final SoundEnvironment env = envByUuid.get(entry.getKey());
			if (env == null) continue;
			p.apply(entry.getValue(), env);
		}
	}
}
