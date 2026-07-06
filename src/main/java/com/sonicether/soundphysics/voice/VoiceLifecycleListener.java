package com.sonicether.soundphysics.voice;

import com.enn3developer.gtnhvoice.api.client.IAudioLifecycleListener;
import com.sonicether.soundphysics.EfxPipeline;
import com.sonicether.soundphysics.SoundEnvironment;
import com.sonicether.soundphysics.SoundPhysics;
import me.eigenraven.lwjgl3ify.api.Lwjgl3Aware;
import org.lwjgl.openal.ALC10;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * gtnh-voice lifecycle listener that owns one {@link EfxPipeline} per voice AL
 * context and applies occlusion to voice sources. Every callback runs on the
 * voice audio thread with the voice context current, so direct AL calls are
 * allowed here — hence {@code @Lwjgl3Aware} (this class touches {@code ALC10}).
 *
 * <p>Occlusion only: the pipeline is created with {@link EfxPipeline#init(long)}
 * and {@code applyReverbPresets()} is deliberately NOT called, so the aux slots
 * keep {@code AL_EFFECT_NULL} and the neutral sends in the occlusion-only
 * {@link SoundEnvironment} stay inert.
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
			SoundPhysics.log("Voice: ALC_EXT_EFX absent on voice device; occlusion disabled for this context.");
			return;
		}

		// Fresh pipeline on the voice device. No applyReverbPresets(): occlusion
		// only, aux slots stay AL_EFFECT_NULL.
		pipeline = new EfxPipeline().init(deviceHandle);
		SoundPhysics.log("Voice: EFX occlusion pipeline initialized on voice context.");
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
