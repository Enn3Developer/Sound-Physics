package com.sonicether.soundphysics.gpu;

import com.sonicether.soundphysics.world.SectionCache;
import com.sonicether.soundphysics.world.SectionKeys;
import me.eigenraven.lwjgl3ify.api.Lwjgl3Aware;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;

import static org.lwjgl.opengl.GL43.*;

/**
 * GPU-resident voxel data: a 256³ {@code R8UI} texture of
 * material ids, toroidally addressed (texel = world block & 255), plus a 16³
 * coarse section-grid texture (0 = homogeneous air) for empty-space skipping,
 * plus the material palette UBO so config edits swap floats without touching
 * voxel data. The inner 8-chunk window is a 16-section toroidal box; sections
 * entering it as the listener moves upload straight from the CPU cache.
 * Worker thread only, context current.
 */
@Lwjgl3Aware
public final class VoxelStore {

	public static final int WINDOW_SECTIONS = 16;
	private static final int SLOTS = WINDOW_SECTIONS * WINDOW_SECTIONS * WINDOW_SECTIONS;
	private static final long NOT_RESIDENT = Long.MIN_VALUE;

	private int voxelTexture;
	private int sectionTexture;
	private int paletteUbo;

	// Which world section occupies each toroidal slot, and whether it was
	// assumed all-air because the CPU cache had not seen it yet (retried until
	// real data exists).
	private final long[] residentKey = new long[SLOTS];
	private final boolean[] assumedAir = new boolean[SLOTS];

	private final ByteBuffer sectionUpload = directBuffer(4096);
	private final ByteBuffer flagUpload = directBuffer(1);
	private final FloatBuffer paletteUpload = directBuffer(256 * 4 * 4).asFloatBuffer();

	private int windowMinSectionX;
	private int windowMinSectionZ;

	public void init() {
		Arrays.fill(residentKey, NOT_RESIDENT);
		glPixelStorei(GL_UNPACK_ALIGNMENT, 1);

		voxelTexture = createTexture(256, 256, 256);
		sectionTexture = createTexture(WINDOW_SECTIONS, WINDOW_SECTIONS, WINDOW_SECTIONS);

		// glTexStorage3D contents are undefined; zero the section grid so
		// never-touched slots read as air (the flag gates all voxel fetches).
		final ByteBuffer zeroGrid = directBuffer(SLOTS);
		glBindTexture(GL_TEXTURE_3D, sectionTexture);
		glTexSubImage3D(GL_TEXTURE_3D, 0, 0, 0, 0, WINDOW_SECTIONS, WINDOW_SECTIONS, WINDOW_SECTIONS,
				GL_RED_INTEGER, GL_UNSIGNED_BYTE, zeroGrid);

		paletteUbo = glGenBuffers();
		glBindBuffer(GL_UNIFORM_BUFFER, paletteUbo);
		glBufferData(GL_UNIFORM_BUFFER, 256 * 4 * 4, GL_DYNAMIC_DRAW);
	}

	/** Config edits swap palette floats; voxel data untouched. */
	public void uploadPalette(final float[] palette256x4) {
		paletteUpload.clear();
		paletteUpload.put(palette256x4).flip();
		glBindBuffer(GL_UNIFORM_BUFFER, paletteUbo);
		glBufferSubData(GL_UNIFORM_BUFFER, 0, paletteUpload);
	}

	/** Re-center the toroidal window on the listener (worker, each tick). */
	public void updateWindow(final double listenerX, final double listenerZ) {
		windowMinSectionX = (((int) Math.floor(listenerX)) >> 4) - WINDOW_SECTIONS / 2;
		windowMinSectionZ = (((int) Math.floor(listenerZ)) >> 4) - WINDOW_SECTIONS / 2;
	}

	public int windowMinBlockX() {
		return windowMinSectionX << 4;
	}

	public int windowMinBlockZ() {
		return windowMinSectionZ << 4;
	}

	public boolean inWindow(final long sectionKey) {
		final int dx = SectionKeys.x(sectionKey) - windowMinSectionX;
		final int dz = SectionKeys.z(sectionKey) - windowMinSectionZ;
		return dx >= 0 && dx < WINDOW_SECTIONS && dz >= 0 && dz < WINDOW_SECTIONS;
	}

	/** A section the CPU cache re-copied: refresh it if it is resident. */
	public void onSectionUpdated(final long sectionKey, final byte[] data) {
		if (!inWindow(sectionKey)) return;
		if (residentKey[slotOf(sectionKey)] != sectionKey) return;
		upload(sectionKey, data);
	}

	/**
	 * Make sections entering the window resident, up to {@code maxUploads}.
	 * Slots whose resident key mismatches the world section that now maps
	 * there (window moved) re-upload from the CPU cache — no main-thread
	 * round trip.
	 */
	public int syncWindow(final SectionCache cache, final int maxUploads) {
		int uploads = 0;
		for (int sy = 0; sy < WINDOW_SECTIONS && uploads < maxUploads; sy++) {
			for (int dz = 0; dz < WINDOW_SECTIONS && uploads < maxUploads; dz++) {
				for (int dx = 0; dx < WINDOW_SECTIONS && uploads < maxUploads; dx++) {
					final long key = SectionKeys.pack(windowMinSectionX + dx, sy, windowMinSectionZ + dz);
					final int slot = slotOf(key);
					final byte[] data = cache.section(key);
					if (residentKey[slot] == key && !(assumedAir[slot] && data != null)) continue;
					residentKey[slot] = key;
					assumedAir[slot] = data == null;
					upload(key, data);
					uploads++;
				}
			}
		}
		return uploads;
	}

	/** World change: every resident section is junk; re-flood from the CPU cache. */
	public void invalidateAll() {
		Arrays.fill(residentKey, NOT_RESIDENT);
		Arrays.fill(assumedAir, false);
		final ByteBuffer zeroGrid = directBuffer(SLOTS);
		glBindTexture(GL_TEXTURE_3D, sectionTexture);
		glTexSubImage3D(GL_TEXTURE_3D, 0, 0, 0, 0, WINDOW_SECTIONS, WINDOW_SECTIONS, WINDOW_SECTIONS,
				GL_RED_INTEGER, GL_UNSIGNED_BYTE, zeroGrid);
	}

	public void bind() {
		glActiveTexture(GL_TEXTURE0);
		glBindTexture(GL_TEXTURE_3D, voxelTexture);
		glActiveTexture(GL_TEXTURE1);
		glBindTexture(GL_TEXTURE_3D, sectionTexture);
		glBindBufferBase(GL_UNIFORM_BUFFER, 2, paletteUbo);
	}

	private void upload(final long sectionKey, final byte[] data) {
		final int slotX = SectionKeys.x(sectionKey) & 15;
		final int slotY = SectionKeys.y(sectionKey) & 15;
		final int slotZ = SectionKeys.z(sectionKey) & 15;

		final boolean air = data == null || data.length == 0;
		flagUpload.clear();
		flagUpload.put((byte) (air ? 0 : 1)).flip();
		glBindTexture(GL_TEXTURE_3D, sectionTexture);
		glTexSubImage3D(GL_TEXTURE_3D, 0, slotX, slotY, slotZ, 1, 1, 1,
				GL_RED_INTEGER, GL_UNSIGNED_BYTE, flagUpload);
		if (air) return; // flag-only: the shader never fetches voxels of air sections

		sectionUpload.clear();
		sectionUpload.put(data).flip();
		glBindTexture(GL_TEXTURE_3D, voxelTexture);
		glTexSubImage3D(GL_TEXTURE_3D, 0, slotX * 16, slotY * 16, slotZ * 16, 16, 16, 16,
				GL_RED_INTEGER, GL_UNSIGNED_BYTE, sectionUpload);
	}

	private static int slotOf(final long sectionKey) {
		final int slotX = SectionKeys.x(sectionKey) & 15;
		final int slotY = SectionKeys.y(sectionKey) & 15;
		final int slotZ = SectionKeys.z(sectionKey) & 15;
		return (slotY * WINDOW_SECTIONS + slotZ) * WINDOW_SECTIONS + slotX;
	}

	private static int createTexture(final int width, final int height, final int depth) {
		final int texture = glGenTextures();
		glBindTexture(GL_TEXTURE_3D, texture);
		glTexStorage3D(GL_TEXTURE_3D, 1, GL_R8UI, width, height, depth);
		glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
		return texture;
	}

	private static ByteBuffer directBuffer(final int bytes) {
		return ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
	}
}
