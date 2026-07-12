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
import net.minecraft.client.Minecraft;
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
	}

	// --- Play path: store query + estimate + apply, microseconds --------------

	public void onPlaySound(final float x, final float y, final float z, final int sourceId) {
		final ActiveSources.GameSource source = sources.onPlay(sourceId, x, y, z);
		final long now = System.currentTimeMillis();
		final Cell cell = store.getOrCreate(source.cellKey(), now);
		cell.touch(now);
		if (!gamePipeline.isInitialized()) return;
		gamePipeline.apply(sourceId, Estimator.estimate(cell.samples(), cell.bucketEnergy(),
				source.directTransmission, (float) sectionCache.listenerX(),
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
