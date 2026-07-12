package com.sonicether.soundphysics.world;

import com.sonicether.soundphysics.Config;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;

/**
 * Block → acoustic material classification. Classification
 * happens once per block id at section copy time via a lazy LUT; the palette
 * {reflectivity, transmission} lives in a GPU UBO so config edits swap floats
 * without touching voxel data. Water is a material like any other: near-zero
 * reflectivity, heavy-but-partial transmission — underwater muffling emerges
 * from tracing, not from listener flags.
 */
public final class Materials {

	public static final byte AIR = 0;
	public static final byte STONE = 1;
	public static final byte WOOD = 2;
	public static final byte GROUND = 3;
	public static final byte PLANT = 4;
	public static final byte METAL = 5;
	public static final byte GLASS = 6;
	public static final byte CLOTH = 7;
	public static final byte SAND = 8;
	public static final byte SNOW = 9;
	public static final byte WATER = 10;
	// Non-opaque odds and ends (torches, fences, rails…): barely reflect,
	// barely block — the old tracer's 0.15 non-opaque occlusion factor reborn.
	public static final byte SPARSE = 11;

	public static final int COUNT = 12;

	// Per-block transmission factor (fraction of energy passing one block).
	// Tuning constants; reflectivities come from Config, these do not.
	private static final float[] TRANSMISSION = {
			1.0f, // air
			0.05f, // stone
			0.10f, // wood
			0.10f, // ground
			0.55f, // plant
			0.03f, // metal
			0.50f, // glass
			0.30f, // cloth
			0.10f, // sand
			0.40f, // snow
			0.65f, // water
			0.75f, // sparse
	};

	// Lazy block-id → material LUT; -1 = not yet classified. 1.7.10 block ids
	// are hard-capped at 4096.
	private final byte[] lutByBlockId = new byte[4096];

	public Materials() {
		java.util.Arrays.fill(lutByBlockId, (byte) -1);
	}

	public byte idFor(final int blockId) {
		final byte cached = lutByBlockId[blockId];
		if (cached >= 0) return cached;
		final byte classified = classify(Block.getBlockById(blockId));
		lutByBlockId[blockId] = classified;
		return classified;
	}

	/**
	 * Palette UBO contents: std140 array of vec4 {reflectivity, transmission,
	 * 0, 0}, one per material id. Reflectivities are the existing config
	 * values scaled by the global reflectance, exactly like the old
	 * getBlockReflectivity.
	 */
	public static void writePalette(final float[] out256x4) {
		final float[] reflectivity = {
				0.0f,
				Config.stoneReflectivity,
				Config.woodReflectivity,
				Config.groundReflectivity,
				Config.plantReflectivity,
				Config.metalReflectivity,
				Config.glassReflectivity,
				Config.clothReflectivity,
				Config.sandReflectivity,
				Config.snowReflectivity,
				0.02f, // water
				0.05f, // sparse
		};
		java.util.Arrays.fill(out256x4, 0.0f);
		for (int id = 0; id < COUNT; id++) {
			final float scaled = Math.min(1.0f, reflectivity[id] * Config.globalBlockReflectance);
			// Global block absorption steepens or flattens transmission the way
			// the old exp(-occlusion × absorption) coefficient did.
			final float transmission = (float) Math.pow(TRANSMISSION[id], Config.globalBlockAbsorption);
			out256x4[id * 4] = scaled;
			out256x4[id * 4 + 1] = id == AIR ? 1.0f : transmission;
		}
	}

	private static byte classify(final Block block) {
		if (block == null) return AIR;
		final Material blockMaterial = block.getMaterial();
		if (blockMaterial == Material.air) return AIR;
		if (blockMaterial == Material.water) return WATER;

		final Block.SoundType soundType = block.stepSound;
		if (soundType == Block.soundTypeGlass) return GLASS;
		if (blockMaterial == Material.plants || blockMaterial == Material.vine
				|| blockMaterial == Material.grass || blockMaterial == Material.leaves) return PLANT;

		// Non-opaque is not non-solid: stairs, slabs, doors, fences and chests
		// all fail isOpaqueCube but are acoustically walls — a stair roof must
		// reflect, or every village house reads as open sky. Only blocks that
		// don't even block movement (torches, rails, signs…) are SPARSE.
		if (!block.isOpaqueCube() && !blockMaterial.blocksMovement()) return SPARSE;

		if (soundType == Block.soundTypeStone || soundType == Block.soundTypePiston) return STONE;
		if (soundType == Block.soundTypeWood || soundType == Block.soundTypeLadder) return WOOD;
		if (soundType == Block.soundTypeGravel || soundType == Block.soundTypeGrass) return GROUND;
		if (soundType == Block.soundTypeMetal || soundType == Block.soundTypeAnvil) return METAL;
		if (soundType == Block.soundTypeCloth) return CLOTH;
		if (soundType == Block.soundTypeSand) return SAND;
		if (soundType == Block.soundTypeSnow) return SNOW;
		return STONE;
	}
}
