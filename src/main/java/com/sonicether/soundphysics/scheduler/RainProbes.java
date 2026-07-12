package com.sonicether.soundphysics.scheduler;

/**
 * Rain anchor selection by measured audibility. Rain is a distributed source:
 * the perceived origin is wherever the best acoustic path to the listener
 * comes from — an open door beats a closer roof through solid planks. The
 * client tick supplies candidate columns (nearest rain-exposed column per
 * horizontal sector + overhead); the worker traces a diffraction bundle from
 * each and keeps the winner by transmission over distance, sticky so the
 * anchor doesn't flap between roof and door on ties.
 */
public final class RainProbes {

	public static final int MAX_PROBES = 9;

	private static final long CANDIDATE_STALE_NANOS = 2_000_000_000L;
	private static final long BEST_FRESH_NANOS = 1_500_000_000L;
	// Distance falloff in the score: transmission / (1 + k·dist).
	private static final float DISTANCE_K = 0.12f;
	// The reigning anchor keeps its seat until beaten by this factor.
	private static final float STICKINESS = 0.8f;

	// Written on the client thread, read by the worker.
	private volatile float[] candidates = new float[0]; // xyz triplets
	private volatile long candidatesNanos;

	// Worker-only measurement scratch.
	private float[] measuring = new float[0];
	private final float[] scores = new float[MAX_PROBES];

	// Winner, read on the client thread.
	private volatile float bestX;
	private volatile float bestY;
	private volatile float bestZ;
	private volatile long bestNanos;

	/** Client tick: replace the candidate set (xyz triplets, ≤ MAX_PROBES). */
	public void setCandidates(final float[] xyzTriplets) {
		candidates = xyzTriplets;
		candidatesNanos = System.nanoTime();
	}

	/** Worker, at batch compose: the positions to probe this tick (empty when idle). */
	public float[] beginMeasure() {
		if (System.nanoTime() - candidatesNanos > CANDIDATE_STALE_NANOS) return new float[0];
		measuring = candidates;
		java.util.Arrays.fill(scores, 0.0f);
		return measuring;
	}

	/** Worker, at consume: measured two-band transmission of probe {@code index}. */
	public void report(final int index, final float high, final float low) {
		if (index < 0 || index >= MAX_PROBES) return;
		scores[index] = 0.5f * (high + low);
	}

	/** Worker, after consume: crown the best-scoring probe, with stickiness. */
	public void selectBest(final float listenerX, final float listenerY, final float listenerZ) {
		final float[] probes = measuring;
		if (probes.length == 0) return;

		int bestIndex = -1;
		float bestScore = 0.0f;
		float reigningScore = -1.0f;
		int reigningIndex = -1;

		for (int i = 0; i * 3 + 2 < probes.length && i < MAX_PROBES; i++) {
			final float dx = probes[i * 3] - listenerX;
			final float dy = probes[i * 3 + 1] - listenerY;
			final float dz = probes[i * 3 + 2] - listenerZ;
			final float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
			final float score = scores[i] / (1.0f + DISTANCE_K * dist);

			if (score > bestScore) {
				bestScore = score;
				bestIndex = i;
			}
			// Is this probe (roughly) the current anchor?
			final float rx = probes[i * 3] - bestX;
			final float ry = probes[i * 3 + 1] - bestY;
			final float rz = probes[i * 3 + 2] - bestZ;
			if (rx * rx + ry * ry + rz * rz < 4.0f) {
				reigningIndex = i;
				reigningScore = score;
			}
		}
		if (bestIndex < 0) return;
		if (reigningIndex >= 0 && reigningIndex != bestIndex && reigningScore >= bestScore * STICKINESS) {
			bestIndex = reigningIndex; // incumbent holds on near-ties
		}

		bestX = probes[bestIndex * 3];
		bestY = probes[bestIndex * 3 + 1];
		bestZ = probes[bestIndex * 3 + 2];
		bestNanos = System.nanoTime();
	}

	/** Client thread: the measured anchor, or null when none is fresh. */
	public float[] freshBest() {
		if (System.nanoTime() - bestNanos > BEST_FRESH_NANOS) return null;
		return new float[] { bestX, bestY, bestZ };
	}
}
