package com.sonicether.soundphysics.field;

import java.util.HashMap;
import java.util.PriorityQueue;

/**
 * The listener's view of the transport field: one Dijkstra pass over the
 * baked edges, from the listener's cell outward, rebuilt every worker tick
 * (a few thousand nodes — cheaper than tracing a single sound the old way).
 * Every cell in range gets accumulated two-band transmission, the acoustic
 * path length (portal-to-portal polyline, so a winding corridor arrives late
 * and long) and the portal chain back to the listener for validation rays.
 *
 * <p>Edge costs mix attenuation and distance: crossing a half-transmissive
 * partition costs like a {@code TRANS_COST}-block detour, so sound routes
 * through the doorway when one exists and through the wall when that's
 * genuinely shorter. Reads are lock-free against a volatile snapshot.
 */
public final class ListenerField {

	public static final long NO_PREV = Long.MIN_VALUE;

	// Blocks of detour one e-fold of attenuation is worth.
	private static final float TRANS_COST = 32.0f;
	private static final float MIN_TRANS = 1e-4f;
	// Edges below this in both bands are sealed: not worth a queue entry.
	private static final float SEAL_BELOW = 1e-3f;

	/**
	 * One reached cell: path-accumulated transmission per band, polyline
	 * length, and the hop toward the listener ({@code prevKey} plus the portal
	 * crossed into this cell from it).
	 */
	public record Node(float transHigh, float transLow, float pathDist,
			long prevKey, float portalX, float portalY, float portalZ) {
	}

	private volatile HashMap<Long, Node> nodes = new HashMap<>();

	/** The published node map; treat as immutable (worker swaps it wholesale). */
	public java.util.Map<Long, Node> snapshot() {
		return nodes;
	}

	/** No reached cell here or adjacent — sealed off or out of range. */
	public static final long NO_NODE = Long.MIN_VALUE;

	/** Play-path/worker query; null = unreached (sealed off or out of range). */
	public Node sample(final long cellKey) {
		return nodes.get(cellKey);
	}

	/**
	 * The cell itself when reached, else its best-connected neighbor. Sources
	 * embedded in solid blocks — block-place clicks, note blocks in walls —
	 * sit in cells that are not graph nodes; acoustically they radiate from
	 * the adjacent air, so that is the cell whose path and probe stats apply.
	 */
	public long resolve(final long cellKey) {
		final HashMap<Long, Node> snapshot = nodes;
		if (snapshot.containsKey(cellKey)) return cellKey;
		long best = NO_NODE;
		float bestTrans = -1.0f;
		for (final long neighborKey : CellKeys.neighbors6(cellKey)) {
			final Node node = snapshot.get(neighborKey);
			if (node == null || node.transHigh() <= bestTrans) continue;
			bestTrans = node.transHigh();
			best = neighborKey;
		}
		return best;
	}

	public int size() {
		return nodes.size();
	}

	public void clear() {
		nodes = new HashMap<>();
	}

	/**
	 * Portal chain from a cell back to the listener, ordered source→listener,
	 * written as x,y,z triples. Returns the portal count, 0 for the listener's
	 * own cell, or -1 when there is no path or it overflows {@code out}.
	 */
	public int portalChain(final long fromKey, final float[] out) {
		final HashMap<Long, Node> snapshot = nodes;
		Node node = snapshot.get(fromKey);
		if (node == null) return -1;
		int count = 0;
		while (node.prevKey() != NO_PREV) {
			if (count * 3 + 2 >= out.length) return -1;
			out[count * 3] = node.portalX();
			out[count * 3 + 1] = node.portalY();
			out[count * 3 + 2] = node.portalZ();
			count++;
			node = snapshot.get(node.prevKey());
			if (node == null) return -1; // snapshot raced a rebuild; skip this tick
		}
		return count;
	}

	private record QueueEntry(long key, float cost) implements Comparable<QueueEntry> {

		@Override
		public int compareTo(final QueueEntry other) {
			return Float.compare(cost, other.cost);
		}
	}

	/**
	 * Worker tick: full rebuild from the listener's cell. The first polyline
	 * segment starts at the actual listener position, not the cell anchor.
	 */
	public void rebuild(final FieldStore store, final long startKey,
			final float listenerX, final float listenerY, final float listenerZ, final int radiusCells) {
		final CellProbe startProbe = store.probe(startKey);
		if (startProbe == null || !startProbe.hasAir()) {
			nodes = new HashMap<>();
			return;
		}

		final int startCellX = CellKeys.cellX(startKey);
		final int startCellY = CellKeys.cellY(startKey);
		final int startCellZ = CellKeys.cellZ(startKey);

		final HashMap<Long, Node> settled = new HashMap<>();
		final HashMap<Long, Float> bestCost = new HashMap<>();
		final HashMap<Long, Node> bestNode = new HashMap<>();
		final PriorityQueue<QueueEntry> queue = new PriorityQueue<>();

		bestCost.put(startKey, 0.0f);
		bestNode.put(startKey, new Node(1.0f, 1.0f, 0.0f, NO_PREV, 0.0f, 0.0f, 0.0f));
		queue.add(new QueueEntry(startKey, 0.0f));

		while (!queue.isEmpty()) {
			final QueueEntry entry = queue.poll();
			if (settled.containsKey(entry.key())) continue;
			final Node node = bestNode.get(entry.key());
			settled.put(entry.key(), node);

			// Departure point of onward polyline segments.
			final boolean atStart = entry.key() == startKey;
			final CellProbe probe = store.probe(entry.key());
			if (probe == null || !probe.hasAir()) continue;
			final float fromX = atStart ? listenerX : probe.anchorX();
			final float fromY = atStart ? listenerY : probe.anchorY();
			final float fromZ = atStart ? listenerZ : probe.anchorZ();

			for (final long neighborKey : CellKeys.neighbors6(entry.key())) {
				if (settled.containsKey(neighborKey)) continue;
				if (Math.abs(CellKeys.cellX(neighborKey) - startCellX) > radiusCells
						|| Math.abs(CellKeys.cellY(neighborKey) - startCellY) > radiusCells
						|| Math.abs(CellKeys.cellZ(neighborKey) - startCellZ) > radiusCells) continue;
				final CellProbe neighborProbe = store.probe(neighborKey);
				if (neighborProbe == null || !neighborProbe.hasAir()) continue;
				final Edge edge = store.edge(entry.key(), neighborKey);
				if (edge == null || !edge.measured()) continue;
				if (Math.max(edge.transHigh(), edge.transLow()) < SEAL_BELOW) continue;

				final float segment = distance(fromX, fromY, fromZ, edge.portalX(), edge.portalY(), edge.portalZ())
						+ distance(edge.portalX(), edge.portalY(), edge.portalZ(),
								neighborProbe.anchorX(), neighborProbe.anchorY(), neighborProbe.anchorZ());
				final float cost = entry.cost() + segment
						+ TRANS_COST * (float) -Math.log(Math.max(edge.transHigh(), MIN_TRANS));
				final Float known = bestCost.get(neighborKey);
				if (known != null && known <= cost) continue;

				bestCost.put(neighborKey, cost);
				bestNode.put(neighborKey, new Node(
						node.transHigh() * edge.transHigh(),
						node.transLow() * edge.transLow(),
						node.pathDist() + segment,
						entry.key(), edge.portalX(), edge.portalY(), edge.portalZ()));
				queue.add(new QueueEntry(neighborKey, cost));
			}
		}
		nodes = settled;
	}

	private static float distance(final float ax, final float ay, final float az,
			final float bx, final float by, final float bz) {
		final float dx = bx - ax;
		final float dy = by - ay;
		final float dz = bz - az;
		return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
	}
}
