package com.sonicether.soundphysics;

import com.sonicether.soundphysics.efx.ApplyQueue;
import com.sonicether.soundphysics.efx.EfxPipeline;
import com.sonicether.soundphysics.gpu.TraceContext;
import com.sonicether.soundphysics.restir.Cell;
import com.sonicether.soundphysics.restir.ConnectivityCache;
import com.sonicether.soundphysics.restir.ReservoirStore;
import com.sonicether.soundphysics.scheduler.ActiveSources;
import com.sonicether.soundphysics.scheduler.AudioWorker;
import com.sonicether.soundphysics.shape.Estimator;
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
	private final ReservoirStore store;
	private final ConnectivityCache connectivity = new ConnectivityCache();
	private final ActiveSources sources = new ActiveSources();
	private final ApplyQueue applyQueue = new ApplyQueue();
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
		store = new ReservoirStore(Config.reservoirSlots);
		worker = new AudioWorker(context, sectionCache, store, connectivity, sources, applyQueue, gamePipeline);
	}

	// --- Client tick -----------------------------------------------------------

	public void onClientTick(final Minecraft mc) {
		final World world = mc.theWorld;
		if (world != lastWorld) {
			lastWorld = world;
			sectionCache.clear();
			store.clear();
			connectivity.clear();
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

	public void onPlaySound(final float x, final float y, final float z, final int sourceId) {
		final ActiveSources.GameSource source = sources.onPlay(sourceId, x, y, z);
		final long now = System.currentTimeMillis();
		final Cell cell = store.getOrCreate(source.cellKey(), now);
		cell.adoptOrigin(x, y + 0.3f, z);
		cell.touch(now);
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
		gamePipeline.apply(sourceId, Estimator.estimate(cell.samples(), cell.bucketEnergy(),
				source.directTransmission, source.directTransmissionLow, (float) sectionCache.listenerX(),
				(float) sectionCache.listenerY(), (float) sectionCache.listenerZ()));
	}

	/** Sounds that skip simulation (menu, records, music, rain): reset the reused AL source. */
	public void applyPassthrough(final int sourceId) {
		if (!gamePipeline.isInitialized()) return;
		gamePipeline.apply(sourceId, SoundEnvironment.passthrough());
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
