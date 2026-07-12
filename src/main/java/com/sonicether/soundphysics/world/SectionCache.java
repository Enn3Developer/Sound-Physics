package com.sonicether.soundphysics.world;

import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.NibbleArray;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The only module that touches {@link World}. Maintains a
 * 16-chunk-radius CPU cache of chunk sections as flat material-id arrays
 * (1 byte/block, GL upload order), copied when dirty or first seen, with
 * block→material classification at copy time. Publishes the listener as bare
 * coordinates — that is the entire listener model.
 *
 * <p>Main thread writes (tick/markDirty); the audio worker reads sections and
 * drains the updated-section queue for GPU uploads. Section arrays are
 * replaced wholesale, never mutated in place.
 */
public final class SectionCache {

	public static final int RADIUS_CHUNKS = 16;
	/** Sentinel for a section containing only air: grid-flag only, no texel upload. */
	public static final byte[] ALL_AIR = new byte[0];

	private static final int SPAN = RADIUS_CHUNKS * 2 + 1;
	private static final int COPY_BUDGET_PER_TICK = 64;
	private static final int SCAN_COLUMNS_PER_TICK = 16;
	private static final int PRUNE_INTERVAL_TICKS = 600;

	private final Materials materials = new Materials();
	private final ConcurrentHashMap<Long, byte[]> sections = new ConcurrentHashMap<>();
	private final ConcurrentLinkedQueue<Long> updatedQueue = new ConcurrentLinkedQueue<>();

	// Main-thread only: pending dirty sections, deduplicated.
	private final ArrayDeque<Long> dirtyQueue = new ArrayDeque<>();
	private final HashSet<Long> dirtySet = new HashSet<>();

	private volatile double listenerX;
	private volatile double listenerY;
	private volatile double listenerZ;

	private int scanCursor;
	private int tickCounter;

	// --- Worker-side API ---------------------------------------------------

	/** Cached material array for a section; null = never seen (treat as air). */
	public byte[] section(final long sectionKey) {
		return sections.get(sectionKey);
	}

	/** Next re-copied/new section for GPU upload consideration, or null. */
	public Long pollUpdated() {
		return updatedQueue.poll();
	}

	public double listenerX() {
		return listenerX;
	}

	public double listenerY() {
		return listenerY;
	}

	public double listenerZ() {
		return listenerZ;
	}

	// --- Main-thread API ---------------------------------------------------

	/** Client-world setBlock hook: re-copy that section next tick. */
	public void markBlockDirty(final int blockX, final int blockY, final int blockZ) {
		if (blockY < 0 || blockY > 255) return;
		enqueueDirty(SectionKeys.pack(blockX >> 4, blockY >> 4, blockZ >> 4));
	}

	/** Chunk.fillChunk hook (full chunk-data packets): re-copy the whole column. */
	public void markChunkDirty(final int chunkX, final int chunkZ) {
		for (int sectionY = 0; sectionY < 16; sectionY++) {
			enqueueDirty(SectionKeys.pack(chunkX, sectionY, chunkZ));
		}
	}

	/**
	 * Per-client-tick maintenance: publish the listener, re-copy dirty
	 * sections, advance the first-seen scan, and periodically prune sections
	 * that fell out of the radius.
	 */
	public void tick(final World world, final double lx, final double ly, final double lz) {
		listenerX = lx;
		listenerY = ly;
		listenerZ = lz;
		tickCounter++;

		int budget = COPY_BUDGET_PER_TICK;
		budget = processDirty(world, budget);
		scan(world, budget);
		if (tickCounter % PRUNE_INTERVAL_TICKS == 0) prune();
	}

	public void clear() {
		sections.clear();
		updatedQueue.clear();
		dirtyQueue.clear();
		dirtySet.clear();
		scanCursor = 0;
	}

	// --- Internals (main thread) --------------------------------------------

	private void enqueueDirty(final long key) {
		if (!withinRadius(key)) return;
		if (!dirtySet.add(key)) return;
		dirtyQueue.add(key);
	}

	private int processDirty(final World world, int budget) {
		while (budget > 0 && !dirtyQueue.isEmpty()) {
			final long key = dirtyQueue.poll();
			dirtySet.remove(key);
			if (!withinRadius(key)) continue;
			if (copySection(world, key)) budget--;
		}
		return budget;
	}

	// Sweep the 33×33 column grid around the listener a few columns per tick,
	// copying sections not yet cached (chunk loads, world entry, radius shift).
	private void scan(final World world, int budget) {
		final int listenerChunkX = ((int) Math.floor(listenerX)) >> 4;
		final int listenerChunkZ = ((int) Math.floor(listenerZ)) >> 4;

		for (int column = 0; column < SCAN_COLUMNS_PER_TICK && budget > 0; column++) {
			scanCursor = (scanCursor + 1) % (SPAN * SPAN);
			final int chunkX = listenerChunkX - RADIUS_CHUNKS + scanCursor % SPAN;
			final int chunkZ = listenerChunkZ - RADIUS_CHUNKS + scanCursor / SPAN;

			final Chunk chunk = world.getChunkFromChunkCoords(chunkX, chunkZ);
			if (!chunk.isChunkLoaded) continue;

			for (int sectionY = 0; sectionY < 16 && budget > 0; sectionY++) {
				final long key = SectionKeys.pack(chunkX, sectionY, chunkZ);
				if (sections.containsKey(key)) continue;
				if (copySection(world, key)) budget--;
			}
		}
	}

	private boolean copySection(final World world, final long key) {
		final Chunk chunk = world.getChunkFromChunkCoords(SectionKeys.x(key), SectionKeys.z(key));
		if (!chunk.isChunkLoaded) return false;

		final ExtendedBlockStorage storage = chunk.getBlockStorageArray()[SectionKeys.y(key)];
		final byte[] data = storage == null || storage.isEmpty() ? ALL_AIR : classifySection(storage);
		sections.put(key, data);
		updatedQueue.add(key);
		return true;
	}

	// Copy + classify one 16³ section into GL upload order (x fastest, then y
	// rows, then z slices). Texel = material | occupancy << 6; slabs and
	// stairs mark which half of the voxel they fill (from metadata) so the
	// tracer can pass sound through the open half. Returns ALL_AIR when
	// nothing acoustic was found.
	private byte[] classifySection(final ExtendedBlockStorage storage) {
		final byte[] lsb = storage.getBlockLSBArray();
		final NibbleArray msb = storage.getBlockMSBArray();
		final NibbleArray meta = storage.getMetadataArray();
		final byte[] out = new byte[4096];
		boolean allAir = true;

		for (int z = 0; z < 16; z++) {
			for (int y = 0; y < 16; y++) {
				for (int x = 0; x < 16; x++) {
					final int storageIndex = y << 8 | z << 4 | x;
					int blockId = lsb[storageIndex] & 0xFF;
					if (msb != null) blockId |= msb.get(x, y, z) << 8;
					if (blockId == 0) continue;
					final byte material = materials.idFor(blockId);
					if (material == Materials.AIR) continue;
					out[(z * 16 + y) * 16 + x] = (byte) (material | occupancyOf(blockId, meta, x, y, z) << 6);
					allAir = false;
				}
			}
		}
		return allAir ? ALL_AIR : out;
	}

	private int occupancyOf(final int blockId, final NibbleArray meta, final int x, final int y, final int z) {
		final byte shape = materials.shapeFor(blockId);
		if (shape == Materials.SHAPE_FULL || meta == null) return 0;
		final int blockMeta = meta.get(x, y, z);
		if (shape == Materials.SHAPE_SLAB) return (blockMeta & 8) != 0 ? Materials.OCC_TOP : Materials.OCC_BOTTOM;
		return (blockMeta & 4) != 0 ? Materials.OCC_TOP : Materials.OCC_BOTTOM;
	}

	private boolean withinRadius(final long key) {
		final int listenerSectionX = ((int) Math.floor(listenerX)) >> 4;
		final int listenerSectionZ = ((int) Math.floor(listenerZ)) >> 4;
		return Math.abs(SectionKeys.x(key) - listenerSectionX) <= RADIUS_CHUNKS
				&& Math.abs(SectionKeys.z(key) - listenerSectionZ) <= RADIUS_CHUNKS;
	}

	private void prune() {
		final int listenerSectionX = ((int) Math.floor(listenerX)) >> 4;
		final int listenerSectionZ = ((int) Math.floor(listenerZ)) >> 4;
		final int keepRadius = RADIUS_CHUNKS + 2;
		sections.keySet().removeIf(key -> Math.abs(SectionKeys.x(key) - listenerSectionX) > keepRadius
				|| Math.abs(SectionKeys.z(key) - listenerSectionZ) > keepRadius);
	}
}
