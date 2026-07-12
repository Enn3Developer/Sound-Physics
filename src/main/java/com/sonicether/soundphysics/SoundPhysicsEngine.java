package com.sonicether.soundphysics;

import com.sonicether.soundphysics.efx.ApplyQueue;
import com.sonicether.soundphysics.efx.EfxPipeline;
import com.sonicether.soundphysics.field.CellKeys;
import com.sonicether.soundphysics.field.FieldStore;
import com.sonicether.soundphysics.field.ListenerField;
import com.sonicether.soundphysics.gpu.TraceContext;
import com.sonicether.soundphysics.scheduler.ActiveSources;
import com.sonicether.soundphysics.scheduler.AudioWorker;
import com.sonicether.soundphysics.shape.Estimator;
import com.sonicether.soundphysics.world.DirectMarch;
import com.sonicether.soundphysics.world.SectionCache;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Wires the modules together and owns their lifecycles. Singleton per client
 * run; created at FML init if a GL 4.3 context is available, otherwise the
 * mod leaves vanilla sound untouched (no CPU fallback). The play path through
 * here is pure memory reads — store query + estimate from cached measurements
 * + EFX apply.
 */
public final class SoundPhysicsEngine {

	private static volatile SoundPhysicsEngine instance;
	private static boolean initFailed;

	private final SectionCache sectionCache = new SectionCache();
	private final FieldStore field = new FieldStore();
	private final ListenerField listenerField = new ListenerField();
	private final ActiveSources sources = new ActiveSources();
	private final ApplyQueue applyQueue = new ApplyQueue();
	private final com.sonicether.soundphysics.scheduler.RainProbes rainProbes =
			new com.sonicether.soundphysics.scheduler.RainProbes();
	private final EfxPipeline gamePipeline = new EfxPipeline();
	private final AudioWorker worker;

	// Client thread only.
	private World lastWorld;

	public static SoundPhysicsEngine instance() {
		return instance;
	}

	/**
	 * FML init, CLIENT thread: creates the worker's shared GL
	 * context (context creation belongs on the thread that owns the game
	 * window — the sound-system hook below runs on the async Sound Library
	 * Loader thread and must never do this) and starts the worker. The GL 4.3
	 * probe completes on the worker after makeCurrent.
	 */
	public static void initEngine() {
		if (instance != null || initFailed) return;
		final TraceContext context = TraceContext.create();
		if (context == null) {
			initFailed = true; // logged once inside; vanilla sound
			return;
		}
		instance = new SoundPhysicsEngine(context);
		instance.worker.start();
	}

	/**
	 * Every sound-system (re)start, on the Sound Library Loader thread: a
	 * reload means a fresh AL context, so re-create the EFX objects. AL is
	 * thread-agnostic (process-global context), unlike GL above.
	 */
	static void bootstrap() {
		if (instance == null) return;
		if (instance.gamePipeline.init().isInitialized()) {
			instance.gamePipeline.applyReverbPresets();
		}

		// The ×4 loudness compensation only makes sense when the EFX bus is
		// actually filtering; without the engine, sound stays fully vanilla.
		SoundPhysics.globalVolumeMultiplier = instance.gamePipeline.isInitialized() ? 4.0f : 1.0f;
	}

	private SoundPhysicsEngine(final TraceContext context) {
		worker = new AudioWorker(context, sectionCache, field, listenerField, sources, applyQueue, gamePipeline,
				rainProbes);
	}

	// --- Client tick -----------------------------------------------------------

	public void onClientTick(final Minecraft mc) {
		final World world = mc.theWorld;
		if (world != lastWorld) {
			lastWorld = world;
			sectionCache.clear();
			field.clear();
			listenerField.clear();
			sources.clear();
			worker.markWorldChanged();
		}
		if (world == null || mc.thePlayer == null) return;
		// The listener sits at EYE level. Derived from the bounding box, not
		// posY: the 1.7.10 client player's posY is offset relative to the
		// server's, but boundingBox.minY is unambiguously the feet.
		final double eyeY = mc.thePlayer.boundingBox.minY + mc.thePlayer.getEyeHeight();
		sectionCache.tick(world, mc.thePlayer.posX, eyeY, mc.thePlayer.posZ);
		publishListenerState(mc, world, eyeY);
	}

	// Presentation-layer listener state: look vector (reverb panning is in
	// listener space, matching the AL listener orientation), ears-wet flag and
	// the snow air-absorption factor at the listener.
	private void publishListenerState(final Minecraft mc, final World world, final double eyeY) {
		final float yaw = (float) Math.toRadians(mc.thePlayer.rotationYaw);
		final float pitch = (float) Math.toRadians(mc.thePlayer.rotationPitch);
		ListenerState.forwardX = -MathHelper.sin(yaw) * MathHelper.cos(pitch);
		ListenerState.forwardY = -MathHelper.sin(pitch);
		ListenerState.forwardZ = MathHelper.cos(yaw) * MathHelper.cos(pitch);

		ListenerState.earsWet = mc.thePlayer.isInsideOfMaterial(Material.water);
		ListenerState.weatherAbsorption = snowAbsorptionAt(world,
				(int) Math.floor(mc.thePlayer.posX), (int) Math.floor(eyeY), (int) Math.floor(mc.thePlayer.posZ));
	}

	// Snow dampens sound: when it's snowing on the listener, raise the air
	// absorption with the storm's strength (inherited from the old tracer,
	// reduced to a listener-area check on the client tick).
	private static float snowAbsorptionAt(final World world, final int x, final int y, final int z) {
		if (Config.snowAirAbsorptionFactor <= 1.0f) return 1.0f;
		if (!world.isRaining()) return 1.0f;
		if (!world.canBlockSeeTheSky(x, y, z)) return 1.0f;
		if (world.getPrecipitationHeight(x, z) > y + 1) return 1.0f;
		final boolean snowing = world.func_147478_e(x, y, z, false)
				|| world.getBiomeGenForCoords(x, z).getEnableSnow();
		if (!snowing) return 1.0f;
		return Math.max(1.0f, Config.snowAirAbsorptionFactor * world.getRainStrength(1.0f));
	}

	// --- Play path: store query + estimate + apply, microseconds --------------

	// Speed of sound: sources farther than this get their start delayed.
	private static final float DELAY_MIN_DISTANCE = 20.0f;
	private static final float SPEED_OF_SOUND = 340.0f;

	public void onPlaySound(final float x, final float y, final float z, final int sourceId,
			final boolean directOnly) {
		final ActiveSources.GameSource source = sources.onPlay(sourceId, x, y, z, directOnly);
		if (!gamePipeline.isInitialized()) return;

		// Propagation delay: thunder arrives late. Pause now (play thread owns
		// the moment), the worker resumes when the wavefront gets here.
		final double dx = x - sectionCache.listenerX();
		final double dy = y - sectionCache.listenerY();
		final double dz = z - sectionCache.listenerZ();
		final double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (distance > DELAY_MIN_DISTANCE) {
			gamePipeline.pauseForDelay(sourceId);
			source.resumeAtNanos = System.nanoTime() + (long) (distance / SPEED_OF_SOUND * 1.0e9);
		}

		// Onset occlusion: a synchronous CPU march for the straight line. Most
		// sounds are shorter than the worker's first correction, so the filter
		// they start with is the one that matters — and it must be the LINE,
		// not the graph's best path (which is ~1.0 near the listener and blind
		// inside a cell). The GPU bundle refines whatever is still playing.
		// Rain re-plays through fresh sources constantly, so for rain this
		// onset IS the occlusion.
		final float[] onset = new float[2];
		DirectMarch.trace(sectionCache, x, y, z,
				sectionCache.listenerX(), sectionCache.listenerY(), sectionCache.listenerZ(), onset);
		source.directTransmission = onset[0];
		source.directTransmissionLow = onset[1];

		// The field supplies what the graph IS for: reverb character and the
		// best-path diffraction floor that keeps around-the-corner sources
		// audible. A direct-only source must not borrow the reverb of
		// whatever cell happens to share its position.
		// A sound from inside a solid block (block-place clicks) sits in a cell
		// that is no graph node; it radiates from the adjacent air cell.
		final float euclid = (float) distance;
		final long resolved = listenerField.resolve(CellKeys.ofBlock(x, y, z));
		final ListenerField.Node node = resolved == ListenerField.NO_NODE ? null : listenerField.sample(resolved);
		final float pathHigh = !directOnly && node != null ? node.transHigh() : 0.0f;
		final float pathLow = !directOnly && node != null ? node.transLow() : 0.0f;
		final float pathDist = node == null ? euclid : node.pathDist();
		gamePipeline.apply(sourceId, Estimator.estimate(
				directOnly || resolved == ListenerField.NO_NODE ? null : field.stats(resolved),
				pathHigh, pathLow, pathDist, euclid, onset[0], onset[1]));
	}

	/** Sounds that skip simulation (menu, records, music, rain): reset the reused AL source. */
	public void applyPassthrough(final int sourceId) {
		if (!gamePipeline.isInitialized()) return;
		gamePipeline.apply(sourceId, SoundEnvironment.passthrough());
	}

	// Rain placement: how far around the listener to look for exposed columns,
	// and the vertical window in which landing rain is considered audible.
	private static final int RAIN_SEARCH_RADIUS = 10;
	private static final int RAIN_MAX_ABOVE = 16;
	private static final int RAIN_MAX_BELOW = 10;

	/**
	 * Rain-loop placement (client thread, from the EntityRenderer redirect).
	 * Rain is distributed: the perceived origin is the best ACOUSTIC path, not
	 * the nearest landing point — an open door beats a closer roof through
	 * solid planks. Candidate columns (nearest exposed column per horizontal
	 * sector + overhead) go to the worker's rain probes, which score them by
	 * traced transmission; the sound plays from the measured winner. Until the
	 * first measurement lands (~1 worker tick), fall back to the nearest
	 * candidate. Volume and pitch are always the vanilla "normal" values —
	 * occlusion is measured, not guessed.
	 */
	public void playRainSound(final World world, final double vanillaX, final double vanillaY,
			final double vanillaZ, final String soundName, final boolean distanceDelay) {
		final double px = sectionCache.listenerX();
		final double py = sectionCache.listenerY();
		final double pz = sectionCache.listenerZ();
		final int baseX = (int) Math.floor(px);
		final int baseZ = (int) Math.floor(pz);
		final int eyeY = (int) Math.floor(py);

		// Nearest exposed column per horizontal sector (8) + overhead (slot 8).
		final double[] slotDistSq = new double[9];
		final float[] slotPos = new float[9 * 3];
		java.util.Arrays.fill(slotDistSq, Double.MAX_VALUE);

		for (int dx = -RAIN_SEARCH_RADIUS; dx <= RAIN_SEARCH_RADIUS; dx++) {
			for (int dz = -RAIN_SEARCH_RADIUS; dz <= RAIN_SEARCH_RADIUS; dz++) {
				final int columnX = baseX + dx;
				final int columnZ = baseZ + dz;
				final int height = world.getPrecipitationHeight(columnX, columnZ);
				if (height > eyeY + RAIN_MAX_ABOVE || height < eyeY - RAIN_MAX_BELOW) continue;
				if (!world.getBiomeGenForCoords(columnX, columnZ).canSpawnLightningBolt()) continue;

				final double soundX = columnX + 0.5;
				final double soundY = height + 0.1;
				final double soundZ = columnZ + 0.5;
				final double ddx = soundX - px;
				final double ddy = soundY - py;
				final double ddz = soundZ - pz;
				final double distSq = ddx * ddx + ddy * ddy + ddz * ddz;

				final int slot = Math.abs(dx) <= 2 && Math.abs(dz) <= 2
						? 8
						: (int) ((Math.atan2(ddz, ddx) + Math.PI) / (2.0 * Math.PI) * 8.0) & 7;
				if (distSq >= slotDistSq[slot]) continue;
				slotDistSq[slot] = distSq;
				slotPos[slot * 3] = (float) soundX;
				slotPos[slot * 3 + 1] = (float) soundY;
				slotPos[slot * 3 + 2] = (float) soundZ;
			}
		}

		// Compact the filled slots into the probe set and find the nearest
		// candidate as the pre-measurement fallback.
		final float[] probes = new float[9 * 3];
		int probeCount = 0;
		double nearestSq = Double.MAX_VALUE;
		double fallbackX = vanillaX;
		double fallbackY = vanillaY;
		double fallbackZ = vanillaZ;
		for (int slot = 0; slot < 9; slot++) {
			if (slotDistSq[slot] == Double.MAX_VALUE) continue;
			probes[probeCount * 3] = slotPos[slot * 3];
			probes[probeCount * 3 + 1] = slotPos[slot * 3 + 1];
			probes[probeCount * 3 + 2] = slotPos[slot * 3 + 2];
			probeCount++;
			if (slotDistSq[slot] < nearestSq) {
				nearestSq = slotDistSq[slot];
				fallbackX = slotPos[slot * 3];
				fallbackY = slotPos[slot * 3 + 1];
				fallbackZ = slotPos[slot * 3 + 2];
			}
		}
		rainProbes.setCandidates(java.util.Arrays.copyOf(probes, probeCount * 3));

		final float[] best = rainProbes.freshBest();
		final double soundX = best != null ? best[0] : fallbackX;
		final double soundY = best != null ? best[1] : fallbackY;
		final double soundZ = best != null ? best[2] : fallbackZ;
		world.playSound(soundX, soundY, soundZ, soundName, 0.2f, 1.0f, distanceDelay);
	}

	// --- World hooks -----------------------------------------------------------

	public void onBlockChanged(final int x, final int y, final int z) {
		sectionCache.markBlockDirty(x, y, z);
	}

	public void onChunkFilled(final int chunkX, final int chunkZ) {
		sectionCache.markChunkDirty(chunkX, chunkZ);
	}

	// --- Config ----------------------------------------------------------------

	public void onConfigChanged() {
		worker.markPaletteDirty();
		gamePipeline.applyReverbPresets();
	}

	// --- Voice bridge: speakers are sources like any other ---------------------

	public void setVoiceSink(final BiConsumer<UUID, SoundEnvironment> sink) {
		worker.setVoiceSink(sink);
	}

	public void updateSpeaker(final UUID id, final double x, final double y, final double z) {
		sources.updateSpeaker(id, x, y, z);
	}

	public void removeSpeaker(final UUID id) {
		sources.removeSpeaker(id);
	}
}
