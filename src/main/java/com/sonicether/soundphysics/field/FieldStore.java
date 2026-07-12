package com.sonicether.soundphysics.field;

import com.sonicether.soundphysics.world.SectionCache;
import com.sonicether.soundphysics.world.SectionKeys;

import java.util.concurrent.ConcurrentHashMap;

/**
 * The baked acoustic transport field: cell probes and edges, keyed by cell.
 * Everything here is measured proactively around the listener and refreshed
 * by invalidation — a block edit re-bakes the touched cells and their edges,
 * nothing else. There is no event-driven state and no cold start.
 *
 * <p>Worker owns all mutation; maps are concurrent only so the client thread
 * can clear on world change and play threads can read probe stats.
 */
public final class FieldStore {

	/** Canonically ordered cell-pair key (low key first). */
	public record PairKey(long low, long high) {

		public static PairKey of(final long a, final long b) {
			return a <= b ? new PairKey(a, b) : new PairKey(b, a);
		}
	}

	private final ConcurrentHashMap<Long, CellProbe> cells = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<PairKey, Edge> edges = new ConcurrentHashMap<>();

	public CellProbe probe(final long cellKey) {
		return cells.get(cellKey);
	}

	public CellProbe probeOrCreate(final long cellKey) {
		return cells.computeIfAbsent(cellKey, k -> new CellProbe());
	}

	/** Play-path query: the cell's probe stats, or null while unbaked. */
	public CellProbe.Stats stats(final long cellKey) {
		final CellProbe probe = cells.get(cellKey);
		return probe == null ? null : probe.stats();
	}

	public Edge edge(final long cellA, final long cellB) {
		return edges.get(PairKey.of(cellA, cellB));
	}

	public Edge edgeOrCreate(final long cellA, final long cellB) {
		return edges.computeIfAbsent(PairKey.of(cellA, cellB), k -> new Edge());
	}

	public int cellCount() {
		return cells.size();
	}

	public int edgeCount() {
		return edges.size();
	}

	/**
	 * A section went dirty: the cells inside it get stale anchors, probes
	 * and edges. Probe staleness extends one cell further out — probe rays
	 * reach past their own cell, so the neighbors' reverb character changed
	 * too even though their geometry didn't (anchors and edges there stand).
	 */
	public void invalidateSection(final int sectionX, final int sectionY, final int sectionZ) {
		final int per = CellKeys.CELLS_PER_SECTION;
		for (int dx = -1; dx <= per; dx++) {
			for (int dy = -1; dy <= per; dy++) {
				for (int dz = -1; dz <= per; dz++) {
					final long key = CellKeys.pack(sectionX * per + dx, sectionY * per + dy, sectionZ * per + dz);
					final boolean inside = dx >= 0 && dx < per && dy >= 0 && dy < per && dz >= 0 && dz < per;
					if (inside) invalidateCell(key);
					else markProbeStale(key);
				}
			}
		}
	}

	private void invalidateCell(final long key) {
		markProbeStale(key);
		final CellProbe probe = cells.get(key);
		if (probe != null) probe.markAnchorStale();
		for (final long neighbor : CellKeys.neighbors6(key)) {
			final Edge edge = edges.get(PairKey.of(key, neighbor));
			if (edge != null) edge.markStale();
		}
	}

	private void markProbeStale(final long key) {
		final CellProbe probe = cells.get(key);
		if (probe != null) probe.markProbeStale();
	}

	/**
	 * Recompute a cell's air points from the CPU section copy. A cell lies in
	 * exactly one section (8 | 16). Returns false while the section is still
	 * unseen — the caller retries next tick.
	 */
	public boolean refreshAirPoints(final SectionCache sections, final long cellKey) {
		final int cellX = CellKeys.cellX(cellKey);
		final int cellY = CellKeys.cellY(cellKey);
		final int cellZ = CellKeys.cellZ(cellKey);
		if (cellY < 0 || cellY > CellKeys.MAX_CELL_Y) {
			probeOrCreate(cellKey).setAirPoints(new float[0]); // outside the world column
			return true;
		}
		final int per = CellKeys.CELLS_PER_SECTION;
		final byte[] section = sections.section(SectionKeys.pack(
				Math.floorDiv(cellX, per), Math.floorDiv(cellY, per), Math.floorDiv(cellZ, per)));
		if (section == null) return false;

		final CellProbe probe = probeOrCreate(cellKey);
		probe.setAirPoints(section == SectionCache.ALL_AIR
				? openCellPoints(cellX, cellY, cellZ)
				: scanAirPoints(section, cellX, cellY, cellZ));
		return true;
	}

	// Jitter spread for the alternate air points: inside the cell, off-center.
	private static final float SPREAD = CellKeys.CELL_SIZE * 0.375f;

	// All-air cell: the center plus a spread of jitter points, no scan needed.
	private static float[] openCellPoints(final int cellX, final int cellY, final int cellZ) {
		final float cx = cellX * CellKeys.CELL_SIZE + CellKeys.CELL_SIZE * 0.5f;
		final float cy = cellY * CellKeys.CELL_SIZE + CellKeys.CELL_SIZE * 0.5f;
		final float cz = cellZ * CellKeys.CELL_SIZE + CellKeys.CELL_SIZE * 0.5f;
		return new float[] {
				cx, cy, cz,
				cx + SPREAD, cy, cz,
				cx, cy + SPREAD, cz,
				cx, cy, cz + SPREAD };
	}

	// Occupied section: pick the air voxel nearest the center, then the air
	// voxels nearest three offset targets for hero-ray jitter (deduplicated).
	private static float[] scanAirPoints(final byte[] section, final int cellX, final int cellY, final int cellZ) {
		final int size = CellKeys.CELL_SIZE;
		final int per = CellKeys.CELLS_PER_SECTION;
		final int baseX = Math.floorMod(cellX, per) * size;
		final int baseY = Math.floorMod(cellY, per) * size;
		final int baseZ = Math.floorMod(cellZ, per) * size;
		final float half = size * 0.5f;

		// Targets in cell-local coordinates: center + three axis offsets.
		final float[] targets = {
				half, half, half,
				half + SPREAD, half, half,
				half, half + SPREAD, half,
				half, half, half + SPREAD };
		final int[] bestVoxel = { -1, -1, -1, -1 };
		final float[] bestDistSq = { Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE };

		for (int z = 0; z < size; z++) {
			for (int y = 0; y < size; y++) {
				for (int x = 0; x < size; x++) {
					if (section[((baseZ + z) * 16 + baseY + y) * 16 + baseX + x] != 0) continue;
					final float px = x + 0.5f;
					final float py = y + 0.5f;
					final float pz = z + 0.5f;
					for (int t = 0; t < 4; t++) {
						final float dx = px - targets[t * 3];
						final float dy = py - targets[t * 3 + 1];
						final float dz = pz - targets[t * 3 + 2];
						final float distSq = dx * dx + dy * dy + dz * dz;
						if (distSq >= bestDistSq[t]) continue;
						bestDistSq[t] = distSq;
						bestVoxel[t] = (z * size + y) * size + x;
					}
				}
			}
		}
		if (bestVoxel[0] < 0) return new float[0]; // solid cell: not a graph node

		final float[] points = new float[CellProbe.MAX_AIR_POINTS * 3];
		final int[] chosen = { -1, -1, -1, -1 };
		int count = 0;
		for (int t = 0; t < 4; t++) {
			final int voxel = bestVoxel[t];
			if (voxel < 0) continue;
			boolean duplicate = false;
			for (int i = 0; i < count && !duplicate; i++) duplicate = chosen[i] == voxel;
			if (duplicate) continue;
			chosen[count] = voxel;
			final int vx = voxel % size;
			final int vy = voxel / size % size;
			final int vz = voxel / (size * size);
			points[count * 3] = cellX * size + vx + 0.5f;
			points[count * 3 + 1] = cellY * size + vy + 0.5f;
			points[count * 3 + 2] = cellZ * size + vz + 0.5f;
			count++;
		}
		return count == CellProbe.MAX_AIR_POINTS ? points : java.util.Arrays.copyOf(points, count * 3);
	}

	/** Drop cells and edges outside the kept radius (cells, Chebyshev). */
	public void evictBeyond(final long listenerKey, final int keepRadiusCells) {
		final int lx = CellKeys.cellX(listenerKey);
		final int ly = CellKeys.cellY(listenerKey);
		final int lz = CellKeys.cellZ(listenerKey);
		cells.keySet().removeIf(key -> beyond(key, lx, ly, lz, keepRadiusCells));
		edges.keySet().removeIf(pair -> beyond(pair.low(), lx, ly, lz, keepRadiusCells)
				|| beyond(pair.high(), lx, ly, lz, keepRadiusCells));
	}

	private static boolean beyond(final long key, final int lx, final int ly, final int lz, final int radius) {
		return Math.abs(CellKeys.cellX(key) - lx) > radius
				|| Math.abs(CellKeys.cellY(key) - ly) > radius
				|| Math.abs(CellKeys.cellZ(key) - lz) > radius;
	}

	/** World/dimension change: the whole bake is junk. */
	public void clear() {
		cells.clear();
		edges.clear();
	}
}
