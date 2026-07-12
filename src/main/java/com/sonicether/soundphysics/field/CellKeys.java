package com.sonicether.soundphysics.field;

/**
 * Transport-field cell addressing: the world quantizes to 4³-block cells, the
 * nodes of the acoustic graph. 4 divides 16, so a cell always lies inside a
 * single chunk section — anchors and invalidation stay section-local. 4 also
 * resolves room-scale geometry: an 8³ cell swallowed a typical house wall and
 * its single anchor blurred inside with outside.
 * Keys pack signed cell coordinates into a long, MC-BlockPos style: 26 bits x,
 * 12 bits y, 26 bits z. Pure math, no MC types.
 */
public final class CellKeys {

	/** Blocks per cell edge. Structural: section alignment relies on 4 | 16. */
	public static final int CELL_SIZE = 4;

	public static final int CELLS_PER_SECTION = 16 / CELL_SIZE;

	/** Highest valid cell Y (1.7.10 world column is 0..255). */
	public static final int MAX_CELL_Y = 256 / CELL_SIZE - 1;

	private static final int XZ_BITS = 26;
	private static final int Y_BITS = 12;
	private static final long XZ_MASK = (1L << XZ_BITS) - 1;
	private static final long Y_MASK = (1L << Y_BITS) - 1;

	private CellKeys() {
	}

	public static long pack(final int cellX, final int cellY, final int cellZ) {
		return ((cellX & XZ_MASK) << (Y_BITS + XZ_BITS)) | ((cellY & Y_MASK) << XZ_BITS) | (cellZ & XZ_MASK);
	}

	public static long ofBlock(final double blockX, final double blockY, final double blockZ) {
		return pack(Math.floorDiv((int) Math.floor(blockX), CELL_SIZE),
				Math.floorDiv((int) Math.floor(blockY), CELL_SIZE),
				Math.floorDiv((int) Math.floor(blockZ), CELL_SIZE));
	}

	public static int cellX(final long key) {
		return (int) (key << (64 - (XZ_BITS + Y_BITS + XZ_BITS)) >> (64 - XZ_BITS));
	}

	public static int cellY(final long key) {
		return (int) (key << (64 - (Y_BITS + XZ_BITS)) >> (64 - Y_BITS));
	}

	public static int cellZ(final long key) {
		return (int) (key << (64 - XZ_BITS) >> (64 - XZ_BITS));
	}

	/** World-space center of the cell; the anchor fallback and eviction metric. */
	public static float centerX(final long key) {
		return cellX(key) * CELL_SIZE + CELL_SIZE * 0.5f;
	}

	public static float centerY(final long key) {
		return cellY(key) * CELL_SIZE + CELL_SIZE * 0.5f;
	}

	public static float centerZ(final long key) {
		return cellZ(key) * CELL_SIZE + CELL_SIZE * 0.5f;
	}

	/** The six face-adjacent neighbor cells — the edges of the transport graph. */
	public static long[] neighbors6(final long key) {
		final int x = cellX(key);
		final int y = cellY(key);
		final int z = cellZ(key);
		return new long[] {
				pack(x - 1, y, z), pack(x + 1, y, z),
				pack(x, y - 1, z), pack(x, y + 1, z),
				pack(x, y, z - 1), pack(x, y, z + 1) };
	}
}
