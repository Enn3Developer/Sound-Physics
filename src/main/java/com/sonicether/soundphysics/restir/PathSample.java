package com.sonicether.soundphysics.restir;

/**
 * One acoustic bounce chain from a source cell to its last reflection point.
 * {@code seedDir} plus {@code bounces} deterministically
 * re-traces the identical chain from the owning cell's center, which is how
 * revalidation works — the chain's geometry is never stored. A single traced
 * chain yields one sample per bounce prefix, so short/early chains exist even
 * in enclosed spaces. {@code legTransmission} is the latest measured final-leg
 * (lastHit → listener) transmission, refreshed by the batch; the final leg is
 * traced fresh, so one sample serves a moving listener. {@code
 * chainReflectivity} (average reflectivity along the chain) feeds the
 * inherited distance→delay mapping. Immutable; updates swap in a copy
 * (single-writer copy-on-write discipline in {@link Cell}).
 */
public record PathSample(
		float seedDirX, float seedDirY, float seedDirZ,
		int bounces,
		float lastHitX, float lastHitY, float lastHitZ,
		float totalDistance,
		float energy,
		float chainReflectivity,
		float legTransmission,
		long lastValidatedTick) {

	public PathSample withLegTransmission(final float trans) {
		return new PathSample(seedDirX, seedDirY, seedDirZ, bounces, lastHitX, lastHitY, lastHitZ,
				totalDistance, energy, chainReflectivity, trans, lastValidatedTick);
	}

	/** Revalidation survivor: refresh the measured chain and stamp the tick. */
	public PathSample revalidated(final float hitX, final float hitY, final float hitZ,
			final float distance, final float newEnergy, final float newChainReflectivity, final long tick) {
		return new PathSample(seedDirX, seedDirY, seedDirZ, bounces, hitX, hitY, hitZ,
				distance, newEnergy, newChainReflectivity, legTransmission, tick);
	}

	/** Delivered-energy weight used for WRS and merge gating. */
	public float weight() {
		return energy * Math.max(legTransmission, 0.01f);
	}
}
