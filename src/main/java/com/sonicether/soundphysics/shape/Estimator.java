package com.sonicether.soundphysics.shape;

import com.sonicether.soundphysics.SoundEnvironment;
import com.sonicether.soundphysics.restir.Cell;
import com.sonicether.soundphysics.restir.PathSample;

/**
 * Pure math, no tracing calls: maps a cell's path samples
 * (with their measured final-leg transmissions) plus the source's direct
 * transmission to the four EFX send gain/cutoff pairs and the direct filter.
 * The ear-tuned constants are inherited from the old
 * {@code accumulateSend}/{@code shapeEnvironment} (delay = distance × 0.12 ×
 * reflectivity, crossfade centers 0/1/2 with a ramp ≥ 2, send gains
 * 6.4/12.8/12.8/12.8), retunable by ear. Headless-testable with synthetic
 * samples — no MC types anywhere in this package.
 */
public final class Estimator {

	// Inherited crossfade constant: reflection delay per block of path length.
	private static final float DELAY_PER_BLOCK = 0.12f;

	// Inherited per-bounce energy scale (the old energyTowardsPlayer factor —
	// only a fraction of a reflection's energy radiates toward the listener)
	// combined with the old 1/(rays × bounces) normalization's bounce term:
	// the measured bucket energy sums up to MAX_BOUNCES prefixes per ray.
	private static final float ENERGY_SCALE = 0.25f / 4.0f;

	// Inherited per-send energy gains.
	private static final float[] SEND_ENERGY_GAIN = { 6.4f, 12.8f, 12.8f, 12.8f };

	// Inherited high-frequency shaping: late sends lose more HF through the
	// same paths (old code used exponents 1.0/1.0/1.5/1.5 on the occlusion term).
	private static final float[] CUTOFF_EXPONENT = { 1.0f, 1.0f, 1.5f, 1.5f };

	// Inherited bounce-reflectivity emphasis per send (old shapeEnvironment
	// multiplied gains by bounceReflectivityRatio^{-,1,3,4}).
	private static final float[] REFLECTIVITY_EXPONENT = { 0.0f, 1.0f, 3.0f, 4.0f };

	// Hysteresis: params are emitted only when the change is audible.
	private static final float AUDIBLE_DELTA = 0.01f;

	// Echo signature: a strong long-delay return with nothing early — one
	// distant wall across open space. Send 3 then steals the echo slot.
	private static final float ECHO_MIN_LATE = 0.25f;
	private static final float ECHO_MAX_EARLY = 0.12f;

	private Estimator() {
	}

	/** Delay-bucket assignment, derived from raw distance at merge/estimate time. */
	public static int bucketOf(final float totalDistance, final float chainReflectivity) {
		final float delay = totalDistance * DELAY_PER_BLOCK * chainReflectivity;
		if (delay < 0.5f) return 0;
		if (delay < 1.5f) return 1;
		if (delay < 2.5f) return 2;
		return 3;
	}

	/**
	 * Estimate the environment for one source. The magnitude comes from the
	 * cell's measured {@code bucketEnergy} (delivered energy per candidate ray,
	 * misses included — the unbiased quantity the old tracer averaged over its
	 * whole ray fan); the stored {@code samples} only distribute it: delay
	 * crossfade (with the final leg to the listener folded in, like the old
	 * escape leg), measured final-leg transmission, and HF emphasis.
	 * {@code directHigh}/{@code directLow} are the latest measured direct-path
	 * transmissions per band (1.0 until the first direct ray lands): the high
	 * band drives the lowpass cutoff, the low band the broadband gain — which
	 * is what lets bass through walls.
	 */
	public static SoundEnvironment estimate(final PathSample[][] samples, final float[] bucketEnergy,
			final float directHigh, final float directLow,
			final float listenerX, final float listenerY, final float listenerZ) {
		if (samples == null) return neutral(directHigh, directLow);

		final float[] sendEnergy = new float[Cell.BUCKETS];
		final float[] transWeighted = new float[Cell.BUCKETS];
		final float[] transWeight = new float[Cell.BUCKETS];

		for (int bucket = 0; bucket < Cell.BUCKETS; bucket++) {
			accumulateBucket(samples[bucket], bucketEnergy[bucket], listenerX, listenerY, listenerZ,
					sendEnergy, transWeighted, transWeight);
		}

		final float[] sendGain = new float[Cell.BUCKETS];
		final float[] sendCutoff = new float[Cell.BUCKETS];
		float transSum = 0.0f;

		for (int send = 0; send < Cell.BUCKETS; send++) {
			final float avgTrans = transWeight[send] > 0.0f ? transWeighted[send] / transWeight[send] : 0.0f;
			transSum += avgTrans;
			sendCutoff[send] = (float) Math.pow(avgTrans, CUTOFF_EXPONENT[send]);
			sendGain[send] = clamp01(sendEnergy[send] * SEND_ENERGY_GAIN[send]);
		}

		// Inherited late-send noise floor and HF coupling.
		sendGain[2] = clamp01(sendGain[2] * 1.05f - 0.05f);
		sendGain[3] = clamp01(sendGain[3] * 1.05f - 0.05f);
		for (int send = 0; send < Cell.BUCKETS; send++) {
			sendGain[send] *= (float) Math.pow(sendCutoff[send], 0.1);
		}

		// Directionality preservation, inherited: when reflected paths reach the
		// listener freely (the shared-airspace analog), let some filtered direct
		// signal through even if the straight line is blocked.
		final float opennessFloor = (float) Math.pow(transSum * 0.25f, 0.5) * 0.2f;
		final float directCutoff = Math.max(opennessFloor, directHigh);
		final float directGain = (float) Math.pow(Math.max(opennessFloor, directLow), 0.1);

		final boolean echoing = sendGain[3] > ECHO_MIN_LATE && sendGain[0] + sendGain[1] < ECHO_MAX_EARLY;

		return new SoundEnvironment(sendGain[0], sendGain[1], sendGain[2], sendGain[3],
				sendCutoff[0], sendCutoff[1], sendCutoff[2], sendCutoff[3],
				directCutoff, directGain, 1.0f, echoing ? sendGain[3] : 0.0f);
	}

	/** Hysteresis: emit only when the change is audible. */
	public static boolean audiblyDiffers(final SoundEnvironment a, final SoundEnvironment b) {
		if (a == null || b == null) return true;
		return Math.abs(a.sendGain0 - b.sendGain0) > AUDIBLE_DELTA
				|| Math.abs(a.sendGain1 - b.sendGain1) > AUDIBLE_DELTA
				|| Math.abs(a.sendGain2 - b.sendGain2) > AUDIBLE_DELTA
				|| Math.abs(a.sendGain3 - b.sendGain3) > AUDIBLE_DELTA
				|| Math.abs(a.sendCutoff0 - b.sendCutoff0) > AUDIBLE_DELTA
				|| Math.abs(a.sendCutoff1 - b.sendCutoff1) > AUDIBLE_DELTA
				|| Math.abs(a.sendCutoff2 - b.sendCutoff2) > AUDIBLE_DELTA
				|| Math.abs(a.sendCutoff3 - b.sendCutoff3) > AUDIBLE_DELTA
				|| Math.abs(a.directCutoff - b.directCutoff) > AUDIBLE_DELTA
				|| Math.abs(a.directGain - b.directGain) > AUDIBLE_DELTA
				|| Math.abs(a.echoSend - b.echoSend) > AUDIBLE_DELTA;
	}

	// One bucket's contribution: the measured energy density is split across
	// the stored samples by their relative strength, then each share is
	// crossfaded into the four sends by its delay, inherited from
	// accumulateSend: triangular weights around delay centers 0/1/2, ramp ≥ 2.
	// The final leg to the listener counts toward the delay: a short ground
	// bounce heard from far away is a late reflection, not an early one.
	private static void accumulateBucket(final PathSample[] bucket, final float bucketEnergy,
			final float listenerX, final float listenerY, final float listenerZ,
			final float[] sendEnergy, final float[] transWeighted, final float[] transWeight) {
		if (bucketEnergy <= 0.0f) return;

		float bucketWeight = 0.0f;
		for (final PathSample sample : bucket) {
			if (sample != null) bucketWeight += Math.max(sample.energy(), 1e-6f);
		}
		if (bucketWeight <= 0.0f) return;

		for (final PathSample sample : bucket) {
			if (sample == null) continue;
			final float dx = sample.lastHitX() - listenerX;
			final float dy = sample.lastHitY() - listenerY;
			final float dz = sample.lastHitZ() - listenerZ;
			final float legDistance = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
			final float pathDistance = Math.max(sample.totalDistance(), 0.0f) + legDistance;
			final float delay = pathDistance * DELAY_PER_BLOCK * sample.chainReflectivity();

			// Low band carries the reflected energy (bass survives the final
			// leg better); the high band shapes the cutoffs below.
			final float share = Math.max(sample.energy(), 1e-6f) / bucketWeight;
			final float energy = bucketEnergy * share * sample.legTransmissionLow() * ENERGY_SCALE;

			for (int send = 0; send < Cell.BUCKETS; send++) {
				final float cross = send < 3
						? 1.0f - clamp01(Math.abs(delay - send))
						: clamp01(delay - 2.0f);
				if (cross <= 0.0f) continue;
				final float emphasized = energy
						* (float) Math.pow(Math.max(sample.chainReflectivity(), 0.05f), REFLECTIVITY_EXPONENT[send]);
				sendEnergy[send] += cross * emphasized;
				transWeighted[send] += cross * energy * sample.legTransmission();
				transWeight[send] += cross * energy;
			}
		}
	}

	private static SoundEnvironment neutral(final float directHigh, final float directLow) {
		final float directGain = (float) Math.pow(Math.max(directLow, 0.0f), 0.1);
		return new SoundEnvironment(0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f, 1.0f,
				Math.max(directHigh, 0.0f), directGain, 1.0f);
	}

	private static float clamp01(final float value) {
		return Math.min(1.0f, Math.max(0.0f, value));
	}
}
