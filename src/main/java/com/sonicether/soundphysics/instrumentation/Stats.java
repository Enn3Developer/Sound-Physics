package com.sonicether.soundphysics.instrumentation;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Engine instrumentation, built from day one: wall-bleed and
 * stale reverb tails are indistinguishable by ear, so none of the tuning
 * constants are actionable without these numbers.
 *
 * <p>All counters are written by the audio worker (single writer); the F3
 * overlay and the periodic logger read them from the client thread. Per-tick
 * gauges are plain volatile snapshots; monotonic counters are atomics so the
 * reader can compute rates.
 */
public final class Stats {

	public static final Stats INSTANCE = new Stats();

	// --- Sample age distribution per delay bucket (worker ticks), p95/p99.
	// A mean hides the problem: the starved tail lives in the top percentiles.
	public final int[] ageP95 = new int[4];
	public final int[] ageP99 = new int[4];
	public volatile int agedSampleCount;

	// --- Merge-gate stats (per tick) + connectivity cache hit rate (monotonic).
	public volatile int gateAccepts;
	public volatile int gatePartials;
	public volatile int gateRejects;
	public final AtomicLong connectivityHits = new AtomicLong();
	public final AtomicLong connectivityMisses = new AtomicLong();

	// --- Batch composition breakdown: rays actually spent per stage vs budget.
	public volatile int raysCandidates;
	public volatile int raysRevalidation;
	public volatile int raysFinalLegs;
	public volatile int raysDirect;
	public volatile int raysConnectivity;
	public volatile int raysPrefetch;
	public volatile int rayBudget;

	// --- Reservoir occupancy: filled slots per bucket, averaged over hot cells.
	public final float[] occupancy = new float[4];
	public volatile int activeCells;
	public volatile int storedCells;

	// --- Worker health.
	public volatile int playingSources;
	public volatile int voiceSpeakers;
	public volatile long workerTick;
	public volatile float workerLoadPct;

	private Stats() {
	}

	public float connectivityHitRate() {
		final long hits = connectivityHits.get();
		final long total = hits + connectivityMisses.get();
		if (total == 0) return 1.0f;
		return (float) hits / total;
	}
}
