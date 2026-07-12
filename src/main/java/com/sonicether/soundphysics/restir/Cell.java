package com.sonicether.soundphysics.restir;

import java.util.Random;

/**
 * The reservoirs of one 4³ cell: K weighted-reservoir-sampling slots per delay
 * bucket, 4 buckets = the 4 EFX sends. Delay stratification
 * keeps the weak late paths that form the reverb tail from being evicted by
 * strong early ones.
 *
 * <p>Threading: the audio worker is the single writer; every mutation swaps a
 * copied sample array into the volatile field. Play threads read
 * {@link #samples()} lock-free and must treat the arrays as immutable.
 */
public final class Cell {

	public static final int BUCKETS = 4;

	// [bucket][slot]; null = empty slot. Arrays are never mutated in place.
	private volatile PathSample[][] samples;

	// Running WRS weight sum per slot. Worker-only, so plain floats.
	private final float[][] slotWeight;

	// Idle-eviction clock (millis) — bumped by queries and by the worker while
	// a source plays in this cell. Racy multi-writer, but it is only a hint.
	private volatile long lastTouchedMillis;

	// Measured energy density per delay bucket: EMA of (delivered candidate
	// energy landing in the bucket) / (candidate rays fired), misses counting
	// as zero (worker-written; float element reads are atomic). The reservoirs
	// only store hits — the strongest ones at that — so without this the rare
	// multi-bounce chain in a mostly-open village reads like a cathedral. The
	// stored samples shape WHERE energy goes; this says HOW MUCH there is.
	private final float[] bucketEnergy = new float[BUCKETS];
	private volatile boolean energyMeasured;

	// Where this cell's rays start: the position of the first sound that
	// activated it (real air where sound actually happens), falling back to
	// the geometric cell center — which in a cave is frequently inside rock,
	// where chains and gates go to die. Set once; samples revalidate from it.
	private volatile float originX;
	private volatile float originY;
	private volatile float originZ;
	private volatile boolean originSet;

	public Cell(final int slotsPerBucket, final long nowMillis) {
		final PathSample[][] initial = new PathSample[BUCKETS][];
		for (int b = 0; b < BUCKETS; b++) initial[b] = new PathSample[slotsPerBucket];
		samples = initial;
		slotWeight = new float[BUCKETS][slotsPerBucket];
		lastTouchedMillis = nowMillis;
	}

	/** Lock-free snapshot for readers; do not mutate the arrays. */
	public PathSample[][] samples() {
		return samples;
	}

	public void touch(final long nowMillis) {
		lastTouchedMillis = nowMillis;
	}

	public long lastTouchedMillis() {
		return lastTouchedMillis;
	}

	/**
	 * Per-batch measurement: delivered candidate energy per bucket over rays
	 * fired. The first real measurement snaps instead of easing in, so a fresh
	 * cell is wet one worker tick after its first sound, not a quarter second
	 * later — a moving player lives in fresh cells.
	 */
	public void updateBucketEnergy(final float[] deliveredPerBucket, final int rays) {
		if (rays == 0) return;
		final float alpha = energyMeasured ? 0.25f : 1.0f;
		for (int bucket = 0; bucket < BUCKETS; bucket++) {
			bucketEnergy[bucket] += alpha * (deliveredPerBucket[bucket] / rays - bucketEnergy[bucket]);
		}
		energyMeasured = true;
	}

	/** Merge inheritance: a neighbor's energy density, through the gate. */
	public void inheritBucketEnergy(final float[] neighborEnergy, final float gate) {
		for (int bucket = 0; bucket < BUCKETS; bucket++) {
			bucketEnergy[bucket] = Math.max(bucketEnergy[bucket], neighborEnergy[bucket] * gate);
		}
	}

	/** Read-only for callers; starts at zero, so cold cells are dry, not wet. */
	public float[] bucketEnergy() {
		return bucketEnergy;
	}

	/** First activating sound donates the cell's ray origin. Idempotent. */
	public void adoptOrigin(final float x, final float y, final float z) {
		if (originSet) return;
		originX = x;
		originY = y;
		originZ = z;
		originSet = true;
	}

	public boolean hasOrigin() {
		return originSet;
	}

	public float originX() {
		return originX;
	}

	public float originY() {
		return originY;
	}

	public float originZ() {
		return originZ;
	}

	/**
	 * Streaming weighted reservoir sampling into one slot of one bucket: the
	 * candidate replaces the slot with probability weight/weightSum. Used for
	 * fresh candidates and (with gate-scaled weight) for neighbor merges —
	 * the continuous gate weight composes with WRS here.
	 */
	public void offerCandidate(final int bucket, final PathSample candidate, final float weight, final Random rng) {
		if (weight <= 0.0f) return;
		final int slot = rng.nextInt(slotWeight[bucket].length);
		slotWeight[bucket][slot] += weight;
		final PathSample current = samples[bucket][slot];
		if (current != null && rng.nextFloat() >= weight / slotWeight[bucket][slot]) return;
		swapSlot(bucket, slot, candidate);
	}

	/** Revalidation survivor / final-leg writeback: replace slot content in place. */
	public void replaceSlot(final int bucket, final int slot, final PathSample expected, final PathSample replacement) {
		if (samples[bucket][slot] != expected) return; // slot churned since batch compose; drop stale result
		swapSlot(bucket, slot, replacement);
	}

	/** Revalidation mismatch: the chain no longer exists — lose weight and fall out. */
	public void dropSlot(final int bucket, final int slot, final PathSample expected) {
		if (samples[bucket][slot] != expected) return;
		slotWeight[bucket][slot] *= 0.5f;
		swapSlot(bucket, slot, null);
	}

	public int occupancy(final int bucket) {
		final PathSample[] bucketSamples = samples[bucket];
		int filled = 0;
		for (final PathSample s : bucketSamples) if (s != null) filled++;
		return filled;
	}

	private void swapSlot(final int bucket, final int slot, final PathSample value) {
		final PathSample[][] old = samples;
		final PathSample[][] next = old.clone();
		next[bucket] = old[bucket].clone();
		next[bucket][slot] = value;
		samples = next;
	}
}
