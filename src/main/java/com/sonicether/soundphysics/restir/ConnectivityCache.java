package com.sonicether.soundphysics.restir;

import com.sonicether.soundphysics.instrumentation.Stats;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cached center-to-center transmission between neighboring reservoir cells —
 * the merge gate value. One straight transmission march
 * per cell pair, measured on the GPU, cached here and refreshed only when a
 * section along the segment goes dirty: a trickle, not a per-batch cost.
 *
 * <p>Worker-owned; the map is concurrent only so the client thread can clear
 * it on world change.
 */
public final class ConnectivityCache {

	/** Canonically ordered cell-pair key (low key first). */
	public record PairKey(long low, long high) {

		public static PairKey of(final long a, final long b) {
			return a <= b ? new PairKey(a, b) : new PairKey(b, a);
		}
	}

	public static final class Entry {
		// NaN until the first measurement lands; merges wait for the gate.
		volatile float transmission = Float.NaN;
		volatile boolean pending = true;
		volatile long measuredMillis;

		public boolean measured() {
			return !Float.isNaN(transmission);
		}

		public float transmission() {
			return transmission;
		}
	}

	private final ConcurrentHashMap<PairKey, Entry> pairs = new ConcurrentHashMap<>();

	/**
	 * Gate lookup for a merge: returns the measured entry, or registers the
	 * pair for a connectivity ray and returns null (merge deferred one tick).
	 */
	public Entry gate(final long cellA, final long cellB) {
		final Entry entry = pairs.computeIfAbsent(PairKey.of(cellA, cellB), k -> new Entry());
		if (entry.measured() && !entry.pending) {
			Stats.INSTANCE.connectivityHits.incrementAndGet();
			return entry;
		}
		Stats.INSTANCE.connectivityMisses.incrementAndGet();
		return entry.measured() ? entry : null;
	}

	/** Batch composition: up to {@code max} pairs awaiting a connectivity ray. */
	public List<PairKey> drainPending(final int max) {
		final List<PairKey> result = new ArrayList<>(Math.min(max, 16));
		for (final var mapEntry : pairs.entrySet()) {
			if (result.size() >= max) break;
			if (mapEntry.getValue().pending) result.add(mapEntry.getKey());
		}
		return result;
	}

	public void complete(final PairKey pair, final float transmission, final long nowMillis) {
		final Entry entry = pairs.get(pair);
		if (entry == null) return;
		entry.transmission = transmission;
		entry.measuredMillis = nowMillis;
		entry.pending = false;
	}

	/**
	 * A section went dirty: re-measure every cached pair whose center-to-center
	 * segment crosses it. Section coords are in 16-block units.
	 */
	public void invalidateCrossing(final int sectionX, final int sectionY, final int sectionZ) {
		final float minX = sectionX << 4;
		final float minY = sectionY << 4;
		final float minZ = sectionZ << 4;
		for (final var mapEntry : pairs.entrySet()) {
			final PairKey pair = mapEntry.getKey();
			final boolean crosses = segmentIntersectsBox(
					CellKeys.centerX(pair.low()), CellKeys.centerY(pair.low()), CellKeys.centerZ(pair.low()),
					CellKeys.centerX(pair.high()), CellKeys.centerY(pair.high()), CellKeys.centerZ(pair.high()),
					minX, minY, minZ, minX + 16, minY + 16, minZ + 16);
			if (crosses) mapEntry.getValue().pending = true;
		}
	}

	/** Drop pairs untouched for a while so the map tracks the store's churn. */
	public void evictOld(final long nowMillis, final long maxAgeMillis) {
		pairs.entrySet().removeIf(entry -> {
			final Entry e = entry.getValue();
			return !e.pending && nowMillis - e.measuredMillis > maxAgeMillis;
		});
	}

	public void clear() {
		pairs.clear();
	}

	// Slab test, segment (a→b) vs axis-aligned box.
	private static boolean segmentIntersectsBox(final float ax, final float ay, final float az,
			final float bx, final float by, final float bz,
			final float minX, final float minY, final float minZ,
			final float maxX, final float maxY, final float maxZ) {
		float tMin = 0.0f;
		float tMax = 1.0f;
		final float[] origin = { ax, ay, az };
		final float[] delta = { bx - ax, by - ay, bz - az };
		final float[] boxMin = { minX, minY, minZ };
		final float[] boxMax = { maxX, maxY, maxZ };
		for (int axis = 0; axis < 3; axis++) {
			if (Math.abs(delta[axis]) < 1e-6f) {
				if (origin[axis] < boxMin[axis] || origin[axis] > boxMax[axis]) return false;
				continue;
			}
			final float inv = 1.0f / delta[axis];
			float t0 = (boxMin[axis] - origin[axis]) * inv;
			float t1 = (boxMax[axis] - origin[axis]) * inv;
			if (t0 > t1) {
				final float swap = t0;
				t0 = t1;
				t1 = swap;
			}
			tMin = Math.max(tMin, t0);
			tMax = Math.min(tMax, t1);
			if (tMin > tMax) return false;
		}
		return true;
	}
}
