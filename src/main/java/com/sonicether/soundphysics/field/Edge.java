package com.sonicether.soundphysics.field;

/**
 * One edge of the transport graph: the measured acoustic connection between
 * two face-adjacent cells. Hero rays (a small fan between the cells' air
 * points) bake it; the edge keeps the best ray's two-band transmission and
 * its portal point — where that ray crossed the shared face, which is where
 * sound actually squeezes through. Dijkstra routes along portals, so a path
 * through a doorway bends through the doorway, not through the wall's center.
 *
 * <p>Worker is the single writer; {@link ListenerField} reads on the same
 * thread, so plain fields would do — volatile keeps the door open for reads
 * from debug overlays without a memory-model debate.
 */
public final class Edge {

	private volatile float transHigh = Float.NaN; // NaN until first bake
	private volatile float transLow;
	private volatile float portalX;
	private volatile float portalY;
	private volatile float portalZ;
	private volatile boolean stale = true;
	private volatile boolean pending;

	public boolean measured() {
		return !Float.isNaN(transHigh);
	}

	public boolean needsBake() {
		return stale && !pending;
	}

	public void markStale() {
		stale = true;
	}

	public void beginBake() {
		pending = true;
	}

	/** Bake round lost (batch cut, fence timeout): eligible again next tick. */
	public void abortBake() {
		pending = false;
		stale = true;
	}

	/** Worker: commit the best hero ray of one bake round. */
	public void commitBake(final float high, final float low,
			final float px, final float py, final float pz) {
		transHigh = high;
		transLow = low;
		portalX = px;
		portalY = py;
		portalZ = pz;
		pending = false;
		stale = false;
	}

	public float transHigh() {
		return transHigh;
	}

	public float transLow() {
		return transLow;
	}

	public float portalX() {
		return portalX;
	}

	public float portalY() {
		return portalY;
	}

	public float portalZ() {
		return portalZ;
	}
}
