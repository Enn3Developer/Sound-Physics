package com.sonicether.soundphysics.restir;

import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The acoustic path cache: source position quantized to 4³ cells → reservoir.
 * Cold-start inheritance and neighbor merges are gated by
 * inter-cell transmission — the acoustic analog of graphics ReSTIR's
 * depth/normal similarity test; without the gate, adjacent cells straddling a
 * one-block wall cross-contaminate and reverb bleeds between sealed spaces.
 *
 * <p>Biased by design: no MIS machinery. Revalidation is the sole freshness
 * mechanism, so revalidation age bounds the bias.
 *
 * <p>Threading: worker is the single writer of cell contents; play threads
 * only read snapshots and touch idle clocks.
 */
public final class ReservoirStore {

	private final ConcurrentHashMap<Long, Cell> cells = new ConcurrentHashMap<>();
	private final int slotsPerBucket;

	public ReservoirStore(final int slotsPerBucket) {
		this.slotsPerBucket = slotsPerBucket;
	}

	/** Play-path query: lock-free; null for a cold cell. */
	public Cell get(final long cellKey) {
		return cells.get(cellKey);
	}

	/** Activation: play path and worker create cells on demand. */
	public Cell getOrCreate(final long cellKey, final long nowMillis) {
		return cells.computeIfAbsent(cellKey, k -> new Cell(slotsPerBucket, nowMillis));
	}

	public int size() {
		return cells.size();
	}

	/**
	 * Gated spatial-reuse merge: import the neighbor's samples with weight
	 * scaled by the inter-cell transmission gate (mostly-air → full weight,
	 * partition → partial, wall → caller doesn't get here). Imported samples
	 * re-anchor to the destination cell: their next revalidation
	 * re-traces seedDir from the destination center, so a bad merge is
	 * transient, bounded by revalidation age.
	 */
	public void mergeFrom(final Cell destination, final Cell source, final float gate, final Random rng) {
		final PathSample[][] sourceSamples = source.samples();
		for (int bucket = 0; bucket < Cell.BUCKETS; bucket++) {
			for (final PathSample sample : sourceSamples[bucket]) {
				if (sample == null) continue;
				destination.offerCandidate(bucket, sample, sample.weight() * gate, rng);
			}
		}
		destination.inheritBucketEnergy(source.bucketEnergy(), gate);
	}

	/** Eviction by idle time; worker calls this periodically. */
	public void evictIdle(final long nowMillis, final long maxIdleMillis) {
		cells.entrySet().removeIf(entry -> nowMillis - entry.getValue().lastTouchedMillis() > maxIdleMillis);
	}

	/** World/dimension change: every cached path is junk. */
	public void clear() {
		cells.clear();
	}
}
