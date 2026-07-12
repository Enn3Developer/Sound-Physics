package com.sonicether.soundphysics.field;

/**
 * One cell of the transport field: its air anchors (where rays start and
 * end) and its local acoustic character, measured proactively by probe rounds
 * — chain rays fired from the anchor, aggregated per delay bucket. The field
 * is warm before the first sound plays; there is no cold start and no
 * event-driven state.
 *
 * <p>Threading: the audio worker is the single writer. Air points and stats
 * are published as immutable snapshots through volatile fields; play threads
 * read them lock-free and must not mutate the arrays.
 */
public final class CellProbe {

	public static final int BUCKETS = 4;
	public static final int MAX_AIR_POINTS = 4;

	// EMA rate for probe rounds after the first (the first snaps, so a freshly
	// entered area sounds right one round later, not a quarter second later).
	private static final float ROUND_ALPHA = 0.35f;

	/**
	 * Aggregated probe round: per delay bucket the delivered energy per ray,
	 * the energy-weighted surface reflectivity, chain distance, straight-line
	 * span from the anchor to the reflection points ({@code hitDistance} — the
	 * return leg of the reflection, which for a source beside the listener is
	 * the room size, NOT the source-listener gap) and mean unit direction to
	 * them (its length ≤ 1 encodes directional agreement); plus the escape
	 * statistics — the fraction of rays that found open sky and their mean
	 * direction, which is where the openings are. Arrays are immutable after
	 * publication.
	 */
	public record Stats(float[] energy, float[] reflectivity, float[] distance, float[] hitDistance,
			float[] dirX, float[] dirY, float[] dirZ,
			float escapeRatio, float escapeX, float escapeY, float escapeZ) {
	}

	// x0,y0,z0, x1,y1,z1, …; index 0 nearest the cell center. Length 0 = the
	// cell is solid rock (not a graph node); null = section data not seen yet.
	private volatile float[] airPoints;
	private volatile Stats stats;

	private volatile boolean anchorStale = true;
	private volatile boolean probeStale = true;
	private volatile boolean probePending;
	// Invalidation truth: the next round replaces the stats instead of easing
	// in — blending pre-edit geometry into post-edit stats is just wrong.
	private volatile boolean snapNextRound = true;
	private volatile long probeTick = Long.MIN_VALUE / 2; // "never", overflow-safe

	// --- Anchors ---------------------------------------------------------------

	public boolean anchorStale() {
		return anchorStale;
	}

	public void markAnchorStale() {
		anchorStale = true;
	}

	/** Worker: publish freshly computed air points (null = section unknown). */
	public void setAirPoints(final float[] points) {
		airPoints = points;
		if (points != null) anchorStale = false;
	}

	public boolean hasAir() {
		final float[] points = airPoints;
		return points != null && points.length >= 3;
	}

	/** Anchor = the air point nearest the cell center. Call only when hasAir(). */
	public float anchorX() {
		return airPoints[0];
	}

	public float anchorY() {
		return airPoints[1];
	}

	public float anchorZ() {
		return airPoints[2];
	}

	/** All air points, for hero-ray jitter; treat as immutable. */
	public float[] airPoints() {
		return airPoints;
	}

	// --- Probe stats -------------------------------------------------------------

	public Stats stats() {
		return stats;
	}

	/**
	 * A round is due when the cell is stale (invalidated or never probed) or
	 * its last round has aged out — one probe round is noisy, so periodic
	 * re-rounds keep the EMA converging even in a static world.
	 */
	public boolean needsProbe(final long nowTick, final long maxAgeTicks) {
		if (probePending) return false;
		return probeStale || nowTick - probeTick > maxAgeTicks;
	}

	public void markProbeStale() {
		probeStale = true;
		snapNextRound = true;
	}

	public void beginProbeRound() {
		probePending = true;
	}

	/** Probe round lost (batch cut, fence timeout): eligible again next tick. */
	public void abortProbeRound() {
		probePending = false;
		probeStale = true;
	}

	/**
	 * Worker: commit one aggregated probe round. Snaps when the cell has no
	 * stats yet or was invalidated (the world changed — the old numbers are
	 * lies, not smoothing material); otherwise EMAs the RNG noise away.
	 */
	public void commitProbeRound(final Stats round, final long nowTick) {
		probePending = false;
		probeStale = false;
		probeTick = nowTick;
		final Stats previous = stats;
		if (previous == null || snapNextRound) {
			snapNextRound = false;
			stats = round;
			return;
		}
		final float[] energy = new float[BUCKETS];
		final float[] reflectivity = new float[BUCKETS];
		final float[] distance = new float[BUCKETS];
		final float[] hitDistance = new float[BUCKETS];
		final float[] dirX = new float[BUCKETS];
		final float[] dirY = new float[BUCKETS];
		final float[] dirZ = new float[BUCKETS];
		for (int bucket = 0; bucket < BUCKETS; bucket++) {
			energy[bucket] = ema(previous.energy[bucket], round.energy[bucket]);
			reflectivity[bucket] = ema(previous.reflectivity[bucket], round.reflectivity[bucket]);
			distance[bucket] = ema(previous.distance[bucket], round.distance[bucket]);
			hitDistance[bucket] = ema(previous.hitDistance[bucket], round.hitDistance[bucket]);
			dirX[bucket] = ema(previous.dirX[bucket], round.dirX[bucket]);
			dirY[bucket] = ema(previous.dirY[bucket], round.dirY[bucket]);
			dirZ[bucket] = ema(previous.dirZ[bucket], round.dirZ[bucket]);
		}
		stats = new Stats(energy, reflectivity, distance, hitDistance, dirX, dirY, dirZ,
				ema(previous.escapeRatio, round.escapeRatio),
				ema(previous.escapeX, round.escapeX),
				ema(previous.escapeY, round.escapeY),
				ema(previous.escapeZ, round.escapeZ));
	}

	private static float ema(final float previous, final float fresh) {
		return previous + ROUND_ALPHA * (fresh - previous);
	}
}
