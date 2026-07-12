package com.sonicether.soundphysics.world;

/**
 * Chunk-section addressing (16³ blocks). Same 26/12/26 signed packing as cell
 * keys, in section units.
 */
public final class SectionKeys {

	private static final int XZ_BITS = 26;
	private static final int Y_BITS = 12;
	private static final long XZ_MASK = (1L << XZ_BITS) - 1;
	private static final long Y_MASK = (1L << Y_BITS) - 1;

	private SectionKeys() {
	}

	public static long pack(final int sectionX, final int sectionY, final int sectionZ) {
		return ((sectionX & XZ_MASK) << (Y_BITS + XZ_BITS)) | ((sectionY & Y_MASK) << XZ_BITS)
				| (sectionZ & XZ_MASK);
	}

	public static int x(final long key) {
		return (int) (key << (64 - (XZ_BITS + Y_BITS + XZ_BITS)) >> (64 - XZ_BITS));
	}

	public static int y(final long key) {
		return (int) (key << (64 - (Y_BITS + XZ_BITS)) >> (64 - Y_BITS));
	}

	public static int z(final long key) {
		return (int) (key << (64 - XZ_BITS) >> (64 - XZ_BITS));
	}
}
