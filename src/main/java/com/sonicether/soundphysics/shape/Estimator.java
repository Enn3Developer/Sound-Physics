package com.sonicether.soundphysics.shape;

import com.sonicether.soundphysics.SoundEnvironment;
import com.sonicether.soundphysics.field.CellProbe;

/**
 * Pure math, no tracing calls: maps a source cell's probe stats plus the
 * transport-field path to the listener (two-band transmission, acoustic path
 * length) and the source's direct-ray transmission to the four EFX send
 * gain/cutoff pairs and the direct filter. The ear-tuned constants are
 * inherited from the old {@code accumulateSend}/{@code shapeEnvironment}
 * (delay = distance × 0.12 × reflectivity, crossfade centers 0/1/2 with a
 * ramp ≥ 2, send gains 6.4/12.8/12.8/12.8), retunable by ear.
 * Headless-testable with synthetic stats — no MC types anywhere here.
 */
public final class Estimator {

	// Inherited crossfade constant: reflection delay per block of path length.
	private static final float DELAY_PER_BLOCK = 0.12f;

	// Per-bounce energy scale (the old energyTowardsPlayer factor — only a
	// fraction of a reflection's energy radiates toward the listener) combined
	// with the old 1/(rays × bounces) normalization's bounce term: the
	// measured bucket energy sums up to MAX_BOUNCES prefixes per ray. Doubled
	// from the inherited 0.25/4 — the field estimator's 4-bucket aggregation
	// reads quieter than the old per-sample accumulation did (ear-tuned).
	private static final float ENERGY_SCALE = 0.25f / 2.0f;

	// Inherited per-send energy gains.
	private static final float[] SEND_ENERGY_GAIN = { 6.4f, 12.8f, 12.8f, 12.8f };

	// Inherited high-frequency shaping: late sends lose more HF through the
	// same paths (old code used exponents 1.0/1.0/1.5/1.5 on the occlusion term).
	private static final float[] CUTOFF_EXPONENT = { 1.0f, 1.0f, 1.5f, 1.5f };

	// Inherited bounce-reflectivity emphasis per send (old shapeEnvironment
	// multiplied gains by bounceReflectivityRatio^{-,1,3,4}).
	private static final float[] REFLECTIVITY_EXPONENT = { 0.0f, 1.0f, 3.0f, 4.0f };

	// Loudness curve for the direct path: gain = low-band transmission ^ this.
	// The old 0.1 exponent turned ANY partial transmission into near-full
	// volume (0.7 → 0.97), leaving all occlusion to the HF cutoff — a closed
	// window sounded like an open door. 0.3 still compressed a plank wall
	// (0.32 low) to 63% volume; 0.5 gives occlusion real dynamic range while
	// keeping heavily damped paths audible.
	private static final float DIRECT_GAIN_EXPONENT = 0.5f;

	// Hysteresis: params are emitted only when the change is audible.
	private static final float AUDIBLE_DELTA = 0.01f;

	private Estimator() {
	}

	/** Delay-bucket assignment for probe chain hits. */
	public static int bucketOf(final float totalDistance, final float chainReflectivity) {
		final float delay = totalDistance * DELAY_PER_BLOCK * chainReflectivity;
		if (delay < 0.5f) return 0;
		if (delay < 1.5f) return 1;
		if (delay < 2.5f) return 2;
		return 3;
	}

	/**
	 * Estimate the environment for one source. The magnitude comes from the
	 * source cell's probe stats (delivered energy per probe ray, misses
	 * included — open sky reads dry); the field path shapes it: the low band
	 * carries the reverb energy through the path (bass survives walls), the
	 * high band drives every cutoff, and the path length shifts the delay
	 * crossfade so a sound down a winding corridor reverberates late. The
	 * delay distance blends from the straight-line distance (clear path — a
	 * Dijkstra polyline overshoots in open air) to the polyline length as the
	 * path closes up, which is when the polyline is the truth.
	 *
	 * <p>The diffraction floor survives from the old estimator with a better
	 * witness: a source around a corner stays audible because the PATH to it
	 * transmits, losing highs first (cutoff floor lower than gain floor) —
	 * which is how diffraction actually sounds.
	 */
	public static SoundEnvironment estimate(final CellProbe.Stats probe,
			final float pathHigh, final float pathLow, final float pathDist, final float euclidDist,
			final float directHigh, final float directLow) {
		final float directCutoff = Math.max(pathHigh * 0.5f, directHigh);
		final float directGain = (float) Math.pow(Math.max(pathLow * 0.7f, Math.max(directLow, 0.0f)),
				DIRECT_GAIN_EXPONENT);
		if (probe == null) {
			return new SoundEnvironment(0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f, 1.0f,
					directCutoff, directGain, 1.0f);
		}

		final float effectiveDist = euclidDist + (pathDist - euclidDist) * clamp01(1.0f - pathHigh);
		final float[] sendEnergy = new float[CellProbe.BUCKETS];

		for (int bucket = 0; bucket < CellProbe.BUCKETS; bucket++) {
			final float energy = probe.energy()[bucket] * pathLow * ENERGY_SCALE;
			if (energy <= 0.0f) continue;
			final float reflectivity = Math.max(probe.reflectivity()[bucket], 0.05f);
			// Reflection path = chain out + return leg. The return leg is from
			// the REFLECTION POINTS, not the source: for a click beside the
			// listener in a cave, sound still travels out to the walls and
			// back — the room size floors the delay, or nearby sounds dump
			// all their reverb into the short quiet send and read as dry.
			final float returnLeg = Math.max(probe.hitDistance()[bucket], effectiveDist);
			final float delay = (probe.distance()[bucket] + returnLeg) * DELAY_PER_BLOCK * reflectivity;

			// Crossfade into the four sends: triangular weights around delay
			// centers 0/1/2, ramp ≥ 2 — inherited from accumulateSend.
			for (int send = 0; send < CellProbe.BUCKETS; send++) {
				final float cross = send < 3
						? 1.0f - clamp01(Math.abs(delay - send))
						: clamp01(delay - 2.0f);
				if (cross <= 0.0f) continue;
				sendEnergy[send] += cross * energy
						* (float) Math.pow(reflectivity, REFLECTIVITY_EXPONENT[send]);
			}
		}

		final float[] sendGain = new float[CellProbe.BUCKETS];
		final float[] sendCutoff = new float[CellProbe.BUCKETS];
		for (int send = 0; send < CellProbe.BUCKETS; send++) {
			sendCutoff[send] = (float) Math.pow(Math.max(pathHigh, 0.0f), CUTOFF_EXPONENT[send]);
			sendGain[send] = clamp01(sendEnergy[send] * SEND_ENERGY_GAIN[send]);
		}

		// Inherited late-send noise floor and HF coupling.
		sendGain[2] = clamp01(sendGain[2] * 1.05f - 0.05f);
		sendGain[3] = clamp01(sendGain[3] * 1.05f - 0.05f);
		for (int send = 0; send < CellProbe.BUCKETS; send++) {
			sendGain[send] *= (float) Math.pow(sendCutoff[send], 0.1);
		}

		return new SoundEnvironment(sendGain[0], sendGain[1], sendGain[2], sendGain[3],
				sendCutoff[0], sendCutoff[1], sendCutoff[2], sendCutoff[3],
				directCutoff, directGain, 1.0f);
	}

	/**
	 * Exponential smoothing between worker ticks so parameter changes glide
	 * instead of snapping (measurement churn — probe rounds, path re-routes,
	 * bundle rays flipping — must never reach the ears as flicker). Gains
	 * interpolate linearly; cutoffs in log space, because frequency perception
	 * is octaves.
	 */
	public static SoundEnvironment smooth(final SoundEnvironment current, final SoundEnvironment target,
			final float alpha) {
		if (current == null) return target;
		return new SoundEnvironment(
				lerp(current.sendGain0, target.sendGain0, alpha),
				lerp(current.sendGain1, target.sendGain1, alpha),
				lerp(current.sendGain2, target.sendGain2, alpha),
				lerp(current.sendGain3, target.sendGain3, alpha),
				logLerp(current.sendCutoff0, target.sendCutoff0, alpha),
				logLerp(current.sendCutoff1, target.sendCutoff1, alpha),
				logLerp(current.sendCutoff2, target.sendCutoff2, alpha),
				logLerp(current.sendCutoff3, target.sendCutoff3, alpha),
				logLerp(current.directCutoff, target.directCutoff, alpha),
				lerp(current.directGain, target.directGain, alpha),
				lerp(current.airAbsorptionFactor, target.airAbsorptionFactor, alpha));
	}

	private static float lerp(final float a, final float b, final float t) {
		return a + (b - a) * t;
	}

	private static float logLerp(final float a, final float b, final float t) {
		final float logA = (float) Math.log(Math.max(a, 1e-3f));
		final float logB = (float) Math.log(Math.max(b, 1e-3f));
		return (float) Math.exp(logA + (logB - logA) * t);
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
				|| Math.abs(a.directGain - b.directGain) > AUDIBLE_DELTA;
	}

	private static float clamp01(final float value) {
		return Math.min(1.0f, Math.max(0.0f, value));
	}
}
