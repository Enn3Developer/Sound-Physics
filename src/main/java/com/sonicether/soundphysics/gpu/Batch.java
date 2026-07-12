package com.sonicether.soundphysics.gpu;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * CPU staging for one ray batch: typed add-methods write
 * the GPU ray records; parallel metadata arrays route each result slot back
 * to what requested it (cell/slot for revalidations, source for direct rays,
 * pair for connectivity…). Plain NIO, no GL — the scheduler composes off any
 * thread and {@link TracePipeline} uploads it.
 */
public final class Batch {

	public static final byte KIND_CANDIDATE = 0;
	public static final byte KIND_REVALIDATION = 1;
	public static final byte KIND_FINAL_LEG = 2;
	public static final byte KIND_DIRECT = 3;
	public static final byte KIND_CONNECTIVITY = 4;
	public static final byte KIND_PREFETCH = 5;

	// Must match trace.comp.
	private static final int TYPE_CHAIN = 0;
	private static final int TYPE_MARCH = 1;
	private static final int TYPE_DIRECT = 2;
	public static final int RAY_FLOATS = 8;

	private final int capacity;
	private final ByteBuffer bytes;
	private final FloatBuffer floats;
	private final IntBuffer ints;

	public final byte[] kind;
	public final long[] cellKey;
	public final int[] meta; // bucket << 8 | slot for revalidation/final-leg
	public final Object[] tag; // Cell / PairKey / source state / expected PathSample holder
	public final Object[] expected; // PathSample the slot held at compose time
	public final float[] seedX;
	public final float[] seedY;
	public final float[] seedZ;

	private int size;

	public Batch(final int capacity) {
		this.capacity = capacity;
		bytes = ByteBuffer.allocateDirect(capacity * RAY_FLOATS * 4).order(ByteOrder.nativeOrder());
		floats = bytes.asFloatBuffer();
		ints = bytes.asIntBuffer();
		kind = new byte[capacity];
		cellKey = new long[capacity];
		meta = new int[capacity];
		tag = new Object[capacity];
		expected = new Object[capacity];
		seedX = new float[capacity];
		seedY = new float[capacity];
		seedZ = new float[capacity];
	}

	public void reset() {
		size = 0;
	}

	public int size() {
		return size;
	}

	public boolean full() {
		return size >= capacity;
	}

	/** Bounce-chain ray (candidates, revalidations). Returns the slot index. */
	public int addChain(final float originX, final float originY, final float originZ,
			final float dirX, final float dirY, final float dirZ, final int maxBounces,
			final byte rayKind, final long rayCellKey, final Object rayTag, final int rayMeta,
			final Object expectedSample) {
		final int slot = writeCommon(originX, originY, originZ, TYPE_CHAIN,
				dirX, dirY, dirZ, rayKind, rayCellKey, rayTag, rayMeta, expectedSample);
		ints.put(slot * RAY_FLOATS + 7, maxBounces);
		seedX[slot] = dirX;
		seedY[slot] = dirY;
		seedZ[slot] = dirZ;
		return slot;
	}

	/**
	 * Straight transmission march (final legs, connectivity gates).
	 * {@code graceTarget} skips attenuation inside the target's voxel — set for
	 * listener-targeted rays (the listener's head being inside a voxel proves
	 * it isn't blocking their ears; think top-slab ceilings sharing the head's
	 * voxel row), never for connectivity gates (a neighbor cell centered in a
	 * wall must stay sealed).
	 */
	public int addMarch(final float originX, final float originY, final float originZ,
			final float targetX, final float targetY, final float targetZ, final boolean graceTarget,
			final byte rayKind, final long rayCellKey, final Object rayTag, final int rayMeta,
			final Object expectedSample) {
		final int slot = writeCommon(originX, originY, originZ, TYPE_MARCH,
				targetX, targetY, targetZ, rayKind, rayCellKey, rayTag, rayMeta, expectedSample);
		floats.put(slot * RAY_FLOATS + 7, graceTarget ? 1.0f : 0.0f);
		return slot;
	}

	/**
	 * Direct source→listener occlusion: the shader traces a nine-ray
	 * diffraction bundle in one invocation, so a lone block beside either
	 * endpoint doesn't read as a wall.
	 */
	public int addDirect(final float originX, final float originY, final float originZ,
			final float targetX, final float targetY, final float targetZ, final Object rayTag, final int rayMeta) {
		final int slot = writeCommon(originX, originY, originZ, TYPE_DIRECT,
				targetX, targetY, targetZ, KIND_DIRECT, 0L, rayTag, rayMeta, null);
		floats.put(slot * RAY_FLOATS + 7, 1.0f);
		return slot;
	}

	/** The staged ray records for upload; valid bytes = size × 32. */
	public ByteBuffer stagedBytes() {
		bytes.position(0).limit(size * RAY_FLOATS * 4);
		return bytes;
	}

	private int writeCommon(final float originX, final float originY, final float originZ, final int type,
			final float vecX, final float vecY, final float vecZ,
			final byte rayKind, final long rayCellKey, final Object rayTag, final int rayMeta,
			final Object expectedSample) {
		final int slot = size++;
		final int base = slot * RAY_FLOATS;
		floats.put(base, originX);
		floats.put(base + 1, originY);
		floats.put(base + 2, originZ);
		ints.put(base + 3, type);
		floats.put(base + 4, vecX);
		floats.put(base + 5, vecY);
		floats.put(base + 6, vecZ);
		kind[slot] = rayKind;
		cellKey[slot] = rayCellKey;
		tag[slot] = rayTag;
		meta[slot] = rayMeta;
		expected[slot] = expectedSample;
		return slot;
	}
}
