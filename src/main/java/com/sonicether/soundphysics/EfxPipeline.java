package com.sonicether.soundphysics;

import me.eigenraven.lwjgl3ify.api.Lwjgl3Aware;
import net.minecraft.util.MathHelper;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.EXTEfx;

/**
 * Owns the OpenAL EFX objects (auxiliary effect slots, EAXREVERB effects and
 * lowpass filters) for one AL context and applies computed
 * {@link SoundEnvironment}s to sources. Instantiable so that a second pipeline
 * can later serve another source path (e.g. voice chat) without touching the
 * Minecraft/paulscode one.
 */
@Lwjgl3Aware
public class EfxPipeline {

	private int auxFXSlot0;
	private int auxFXSlot1;
	private int auxFXSlot2;
	private int auxFXSlot3;
	private int reverb0;
	private int reverb1;
	private int reverb2;
	private int reverb3;
	private int directFilter0;
	private int sendFilter0;
	private int sendFilter1;
	private int sendFilter2;
	private int sendFilter3;

	/**
	 * Game path: self-discovers the device from the process-current context and
	 * initializes on it. Correct for the Minecraft/paulscode sound thread, which
	 * owns the process-global context. Do NOT use this on the voice audio thread:
	 * gtnh-voice binds its context via EXTThreadLocalContext, so the process-global
	 * {@link ALC10#alcGetCurrentContext} would resolve to Minecraft's context, not
	 * the voice one — pass that thread's real device to {@link #init(long)} instead.
	 */
	public EfxPipeline init() {
		return init(ALC10.alcGetContextsDevice(ALC10.alcGetCurrentContext()));
	}

	/**
	 * Checks ALC_EXT_EFX on {@code deviceHandle}, then creates the EFX objects on
	 * the thread-current context. The AL10/EXTEfx gen calls honor the thread-local
	 * context, so this is correct on both the Minecraft sound thread and the voice
	 * audio thread. On a device without ALC_EXT_EFX this is a no-op and the pipeline
	 * stays uninitialized. Must be called on a thread with the target context current.
	 */
	public EfxPipeline init(final long deviceHandle) {
		if (!ALC10.alcIsExtensionPresent(deviceHandle, "ALC_EXT_EFX")) {
			SoundPhysics.logError("EFX Extension not found on current device. Aborting.");
			return this;
		}
		SoundPhysics.log("EFX Extension recognized.");

		// Create auxiliary effect slots
		auxFXSlot0 = genAuxSlot();
		auxFXSlot1 = genAuxSlot();
		auxFXSlot2 = genAuxSlot();
		auxFXSlot3 = genAuxSlot();
		SoundPhysics.checkErrorLog("Failed creating auxiliary effect slots!");

		reverb0 = genReverbEffect(0);
		reverb1 = genReverbEffect(1);
		reverb2 = genReverbEffect(2);
		reverb3 = genReverbEffect(3);

		// Create filters
		directFilter0 = genLowpassFilter();
		sendFilter0 = genLowpassFilter();
		sendFilter1 = genLowpassFilter();
		sendFilter2 = genLowpassFilter();
		sendFilter3 = genLowpassFilter();
		SoundPhysics.checkErrorLog("Error creating lowpass filters!");

		return this;
	}

	public boolean isInitialized() {
		return auxFXSlot0 != 0;
	}

	/**
	 * Applies the ReverbParams presets to the four reverb effects and attaches
	 * them to their effect slots. No-op while uninitialized.
	 */
	public EfxPipeline applyReverbPresets() {
		if (!isInitialized()) return this;
		return setReverbParams(ReverbParams.getReverb0(), auxFXSlot0, reverb0)
				.setReverbParams(ReverbParams.getReverb1(), auxFXSlot1, reverb1)
				.setReverbParams(ReverbParams.getReverb2(), auxFXSlot2, reverb2)
				.setReverbParams(ReverbParams.getReverb3(), auxFXSlot3, reverb3);
	}

	/**
	 * Applies a computed environment to an AL source: send filters and slot
	 * routing for sends 0-3, then the direct lowpass filter, then the air
	 * absorption factor.
	 */
	public void apply(final int sourceID, final SoundEnvironment env) {
		// Set reverb send filter values and set source to send to all reverb fx
		// slots
		routeSend(sourceID, 0, auxFXSlot0, sendFilter0, env.sendGain0, env.sendCutoff0);
		routeSend(sourceID, 1, auxFXSlot1, sendFilter1, env.sendGain1, env.sendCutoff1);
		routeSend(sourceID, 2, auxFXSlot2, sendFilter2, env.sendGain2, env.sendCutoff2);
		routeSend(sourceID, 3, auxFXSlot3, sendFilter3, env.sendGain3, env.sendCutoff3);

		EXTEfx.alFilterf(directFilter0, EXTEfx.AL_LOWPASS_GAIN, env.directGain);
		EXTEfx.alFilterf(directFilter0, EXTEfx.AL_LOWPASS_GAINHF, env.directCutoff);
		AL10.alSourcei(sourceID, EXTEfx.AL_DIRECT_FILTER, directFilter0);

		AL10.alSourcef(sourceID, EXTEfx.AL_AIR_ABSORPTION_FACTOR,
				MathHelper.clamp_float(Config.airAbsorption * env.airAbsorptionFactor, 0.0f, 10.0f));
	}

	private static int genAuxSlot() {
		final int slot = EXTEfx.alGenAuxiliaryEffectSlots();
		SoundPhysics.log("Aux slot " + slot + " created");
		EXTEfx.alAuxiliaryEffectSloti(slot, EXTEfx.AL_EFFECTSLOT_AUXILIARY_SEND_AUTO, AL10.AL_TRUE);
		return slot;
	}

	private static int genReverbEffect(final int index) {
		final int effect = EXTEfx.alGenEffects();
		EXTEfx.alEffecti(effect, EXTEfx.AL_EFFECT_TYPE, EXTEfx.AL_EFFECT_EAXREVERB);
		SoundPhysics.checkErrorLog("Failed creating reverb effect slot " + index + "!");
		return effect;
	}

	private static int genLowpassFilter() {
		final int filter = EXTEfx.alGenFilters();
		EXTEfx.alFilteri(filter, EXTEfx.AL_FILTER_TYPE, EXTEfx.AL_FILTER_LOWPASS);
		return filter;
	}

	private static void routeSend(final int sourceID, final int send, final int auxFXSlot, final int sendFilter,
			final float sendGain, final float sendCutoff) {
		EXTEfx.alFilterf(sendFilter, EXTEfx.AL_LOWPASS_GAIN, sendGain);
		EXTEfx.alFilterf(sendFilter, EXTEfx.AL_LOWPASS_GAINHF, sendCutoff);
		AL11.alSource3i(sourceID, EXTEfx.AL_AUXILIARY_SEND_FILTER, auxFXSlot, send, sendFilter);
	}

	/**
	 * Applies the parameters in the enum ReverbParams to the given reverb
	 * effect and attaches it to its effect slot.
	 */
	private EfxPipeline setReverbParams(final ReverbParams r, final int auxFXSlot, final int reverbSlot) {
		EXTEfx.alEffectf(reverbSlot, EXTEfx.AL_EAXREVERB_DENSITY, r.density);
		SoundPhysics.checkErrorLog("Error while assigning reverb density: " + r.density);

		EXTEfx.alEffectf(reverbSlot, EXTEfx.AL_EAXREVERB_DIFFUSION, r.diffusion);
		SoundPhysics.checkErrorLog("Error while assigning reverb diffusion: " + r.diffusion);

		EXTEfx.alEffectf(reverbSlot, EXTEfx.AL_EAXREVERB_GAIN, r.gain);
		SoundPhysics.checkErrorLog("Error while assigning reverb gain: " + r.gain);

		EXTEfx.alEffectf(reverbSlot, EXTEfx.AL_EAXREVERB_GAINHF, r.gainHF);
		SoundPhysics.checkErrorLog("Error while assigning reverb gainHF: " + r.gainHF);

		EXTEfx.alEffectf(reverbSlot, EXTEfx.AL_EAXREVERB_DECAY_TIME, r.decayTime);
		SoundPhysics.checkErrorLog("Error while assigning reverb decayTime: " + r.decayTime);

		EXTEfx.alEffectf(reverbSlot, EXTEfx.AL_EAXREVERB_DECAY_HFRATIO, r.decayHFRatio);
		SoundPhysics.checkErrorLog("Error while assigning reverb decayHFRatio: " + r.decayHFRatio);

		EXTEfx.alEffectf(reverbSlot, EXTEfx.AL_EAXREVERB_REFLECTIONS_GAIN, r.reflectionsGain);
		SoundPhysics.checkErrorLog("Error while assigning reverb reflectionsGain: " + r.reflectionsGain);

		EXTEfx.alEffectf(reverbSlot, EXTEfx.AL_EAXREVERB_LATE_REVERB_GAIN, r.lateReverbGain);
		SoundPhysics.checkErrorLog("Error while assigning reverb lateReverbGain: " + r.lateReverbGain);

		EXTEfx.alEffectf(reverbSlot, EXTEfx.AL_EAXREVERB_LATE_REVERB_DELAY, r.lateReverbDelay);
		SoundPhysics.checkErrorLog("Error while assigning reverb lateReverbDelay: " + r.lateReverbDelay);

		EXTEfx.alEffectf(reverbSlot, EXTEfx.AL_EAXREVERB_AIR_ABSORPTION_GAINHF, r.airAbsorptionGainHF);
		SoundPhysics.checkErrorLog("Error while assigning reverb airAbsorptionGainHF: " + r.airAbsorptionGainHF);

		EXTEfx.alEffectf(reverbSlot, EXTEfx.AL_EAXREVERB_ROOM_ROLLOFF_FACTOR, r.roomRolloffFactor);
		SoundPhysics.checkErrorLog("Error while assigning reverb roomRolloffFactor: " + r.roomRolloffFactor);

		// Attach updated effect object
		EXTEfx.alAuxiliaryEffectSloti(auxFXSlot, EXTEfx.AL_EFFECTSLOT_EFFECT, reverbSlot);
		return this;
	}
}
