package com.sonicether.soundphysics.world;

/**
 * Synchronous CPU transmission march over the section cache: the play path's
 * onset occlusion. Most Minecraft sounds are shorter than the worker's first
 * correction (one tick plus smoothing), so the filter a sound STARTS with is
 * the only one it ever wears — and the transport graph cannot provide it: the
 * graph knows the best path, not the straight line, and is blind inside a
 * cell. One voxel-resolution DDA answers exactly that question, immediately.
 *
 * <p>Deliberately simpler than the GPU tracer: voxel-count attenuation (no
 * occupancy halves, no diffraction bundle). Errs toward over-muffling for one
 * worker tick, which beats blipping through a wall. Origin and target voxels
 * are graced like the GPU marches. Thread-safe: reads the concurrent section
 * map, writes nothing.
 */
public final class DirectMarch {

	private static final int MAX_STEPS = 320;
	private static final float MIN_TRANSMISSION = 1e-4f;

	private DirectMarch() {
	}

	/** Two-band straight-line transmission; out[0] = high band, out[1] = low. */
	public static void trace(final SectionCache sections,
			final double fromX, final double fromY, final double fromZ,
			final double toX, final double toY, final double toZ, final float[] out) {
		out[0] = 1.0f;
		out[1] = 1.0f;
		final double dx = toX - fromX;
		final double dy = toY - fromY;
		final double dz = toZ - fromZ;
		final double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (length < 1e-3) return;

		int voxelX = floor(fromX);
		int voxelY = floor(fromY);
		int voxelZ = floor(fromZ);
		final int graceX = voxelX;
		final int graceY = voxelY;
		final int graceZ = voxelZ;
		final int targetX = floor(toX);
		final int targetY = floor(toY);
		final int targetZ = floor(toZ);

		// Amanatides & Woo, with t in world-distance units along the segment.
		final int stepX = dx > 0 ? 1 : -1;
		final int stepY = dy > 0 ? 1 : -1;
		final int stepZ = dz > 0 ? 1 : -1;
		double tMaxX = axisT(fromX, dx, voxelX, stepX, length);
		double tMaxY = axisT(fromY, dy, voxelY, stepY, length);
		double tMaxZ = axisT(fromZ, dz, voxelZ, stepZ, length);
		final double tDeltaX = Math.abs(dx) < 1e-9 ? Double.MAX_VALUE : length / Math.abs(dx);
		final double tDeltaY = Math.abs(dy) < 1e-9 ? Double.MAX_VALUE : length / Math.abs(dy);
		final double tDeltaZ = Math.abs(dz) < 1e-9 ? Double.MAX_VALUE : length / Math.abs(dz);

		for (int step = 0; step < MAX_STEPS; step++) {
			final boolean grace = (voxelX == graceX && voxelY == graceY && voxelZ == graceZ)
					|| (voxelX == targetX && voxelY == targetY && voxelZ == targetZ);
			if (!grace && !attenuate(sections, voxelX, voxelY, voxelZ, out)) return;
			if (voxelX == targetX && voxelY == targetY && voxelZ == targetZ) return;

			if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
				voxelX += stepX;
				tMaxX += tDeltaX;
			} else if (tMaxY <= tMaxZ) {
				voxelY += stepY;
				tMaxY += tDeltaY;
			} else {
				voxelZ += stepZ;
				tMaxZ += tDeltaZ;
			}
		}
	}

	// One voxel's attenuation; false = fully absorbed, stop marching.
	private static boolean attenuate(final SectionCache sections,
			final int voxelX, final int voxelY, final int voxelZ, final float[] out) {
		if (voxelY < 0 || voxelY > 255) return true; // outside the column: air
		final byte[] section = sections.section(SectionKeys.pack(voxelX >> 4, voxelY >> 4, voxelZ >> 4));
		if (section == null || section == SectionCache.ALL_AIR) return true; // unseen counts as air
		final int material = section[((voxelZ & 15) * 16 + (voxelY & 15)) * 16 + (voxelX & 15)] & 0x3F;
		if (material == 0) return true;
		out[0] *= Materials.cpuTransmissionHigh(material);
		out[1] *= Materials.cpuTransmissionLow(material);
		if (out[1] >= MIN_TRANSMISSION) return true;
		out[0] = 0.0f;
		out[1] = 0.0f;
		return false;
	}

	// Distance along the segment to the first voxel boundary on one axis.
	// (boundary − origin) carries the step's sign and so does delta, so the
	// quotient is non-negative.
	private static double axisT(final double origin, final double delta, final int voxel, final int step,
			final double length) {
		if (Math.abs(delta) < 1e-9) return Double.MAX_VALUE;
		final double boundary = step > 0 ? voxel + 1.0 : voxel;
		return (boundary - origin) * length / delta;
	}

	private static int floor(final double value) {
		return (int) Math.floor(value);
	}
}
