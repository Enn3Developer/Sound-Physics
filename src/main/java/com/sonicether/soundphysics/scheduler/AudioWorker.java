package com.sonicether.soundphysics.scheduler;

import com.sonicether.soundphysics.Config;
import com.sonicether.soundphysics.SoundEnvironment;
import com.sonicether.soundphysics.SoundPhysics;
import com.sonicether.soundphysics.efx.ApplyQueue;
import com.sonicether.soundphysics.efx.EfxPipeline;
import com.sonicether.soundphysics.gpu.Batch;
import com.sonicether.soundphysics.gpu.TraceContext;
import com.sonicether.soundphysics.gpu.TracePipeline;
import com.sonicether.soundphysics.gpu.VoxelStore;
import com.sonicether.soundphysics.instrumentation.Stats;
import com.sonicether.soundphysics.restir.Cell;
import com.sonicether.soundphysics.restir.CellKeys;
import com.sonicether.soundphysics.restir.ConnectivityCache;
import com.sonicether.soundphysics.restir.PathSample;
import com.sonicether.soundphysics.restir.ReservoirStore;
import com.sonicether.soundphysics.shape.Estimator;
import com.sonicether.soundphysics.world.Materials;
import com.sonicether.soundphysics.world.SectionCache;
import com.sonicether.soundphysics.world.SectionKeys;
import me.eigenraven.lwjgl3ify.api.Lwjgl3Aware;
import org.lwjgl.openal.AL10;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiConsumer;

/**
 * The audio worker: one thread, one loop, fixed rate,
 * independent of FPS. Owns the tracer's GL context and the single-writer side
 * of the reservoir store. Tracing never happens on a sound play path — this
 * loop keeps the cache warm and pushes changed params out through the EFX
 * apply queue (game sources) and the voice env sink (speakers).
 *
 * <p>AL notes: the game AL context is process-global, so liveness/position
 * polling and the apply-queue drain are safe from this thread (the voice
 * context is thread-local to the voice audio thread and never touched here).
 */
@Lwjgl3Aware
public final class AudioWorker extends Thread {

	private static final int MAX_BOUNCES = 4;
	private static final int UPLOADS_PER_TICK = 512;
	private static final int UPDATED_DRAIN_PER_TICK = 1024;
	private static final int CONNECTIVITY_PER_TICK = 64;
	private static final int CANDIDATES_PER_HOT_CELL = 32;
	private static final int PREFETCH_PER_NEIGHBOR = 8;
	private static final float REVALIDATION_MATCH_DIST_SQ = 1.0f;
	private static final long CELL_IDLE_EVICT_MILLIS = 30_000;
	private static final long CONNECTIVITY_EVICT_MILLIS = 120_000;
	private static final float GATE_REJECT_BELOW = 0.01f;

	private final TraceContext context;
	private final SectionCache sectionCache;
	private final ReservoirStore store;
	private final ConnectivityCache connectivity;
	private final ActiveSources sources;
	private final ApplyQueue applyQueue;
	private final EfxPipeline gamePipeline;

	private final VoxelStore voxels = new VoxelStore();
	private final TracePipeline pipeline = new TracePipeline();
	private final Batch batch = new Batch(TracePipeline.MAX_RAYS);
	private final Random rng = new Random();
	private final float[] palette = new float[256 * 4];
	private final float[] positionScratch = new float[3];

	private volatile boolean alive = true;
	private volatile boolean paletteDirty = true;
	private volatile boolean worldChanged;
	private volatile BiConsumer<UUID, SoundEnvironment> voiceSink = (id, env) -> {
	};

	private long tick;

	// Per-tick scratch, reused across ticks.
	private final List<HotCell> hotCells = new ArrayList<>();
	private final HashSet<Long> hotKeys = new HashSet<>();
	private final List<RevalEntry> revalEntries = new ArrayList<>();
	// Per-cell candidate measurement for one batch: delivered energy per bucket
	// (indices 0-3) and rays fired (index 4). Misses contribute only to rays.
	private final java.util.HashMap<Cell, float[]> candidateStats = new java.util.HashMap<>();
	private final AgeBuffer[] ages = { new AgeBuffer(), new AgeBuffer(), new AgeBuffer(), new AgeBuffer() };

	private record HotCell(long key, Cell cell) {
	}

	private record RevalEntry(long key, Cell cell, int bucket, int slot, PathSample sample) {
	}

	public AudioWorker(final TraceContext context, final SectionCache sectionCache, final ReservoirStore store,
			final ConnectivityCache connectivity, final ActiveSources sources, final ApplyQueue applyQueue,
			final EfxPipeline gamePipeline) {
		super("SoundPhysics worker");
		setDaemon(true);
		this.context = context;
		this.sectionCache = sectionCache;
		this.store = store;
		this.connectivity = connectivity;
		this.sources = sources;
		this.applyQueue = applyQueue;
		this.gamePipeline = gamePipeline;
	}

	public void markPaletteDirty() {
		paletteDirty = true;
	}

	public void markWorldChanged() {
		worldChanged = true;
	}

	public void setVoiceSink(final BiConsumer<UUID, SoundEnvironment> sink) {
		voiceSink = sink;
	}

	public void shutdown() {
		alive = false;
	}

	@Override
	public void run() {
		if (!context.makeCurrentOnWorker()) return; // probe logged; vanilla sound
		voxels.init();
		if (!pipeline.init(context)) {
			SoundPhysics.logError("Tracer pipeline init failed; acoustic engine disabled, vanilla sound untouched.");
			return;
		}
		SoundPhysics.log("Acoustic worker running (GL compute tracer online).");

		while (alive) {
			final long startNanos = System.nanoTime();
			try {
				tickOnce();
			} catch (final Exception e) {
				SoundPhysics.logError("Worker tick failed: " + e);
				e.printStackTrace();
			}
			final long periodNanos = 1_000_000_000L / Math.max(1, Config.workerRateHz);
			final long elapsed = System.nanoTime() - startNanos;
			Stats.INSTANCE.workerLoadPct = 100.0f * elapsed / periodNanos;
			if (elapsed < periodNanos) LockSupport.parkNanos(periodNanos - elapsed);
		}
	}

	private void tickOnce() {
		tick++;
		final long nowMillis = System.currentTimeMillis();

		if (worldChanged) {
			worldChanged = false;
			voxels.invalidateAll();
		}
		if (paletteDirty) {
			paletteDirty = false;
			Materials.writePalette(palette);
			voxels.uploadPalette(palette);
		}

		final float listenerX = (float) sectionCache.listenerX();
		final float listenerY = (float) sectionCache.listenerY();
		final float listenerZ = (float) sectionCache.listenerZ();
		voxels.updateWindow(listenerX, listenerZ);

		// 1. Dirty/entering sections → GPU; connectivity refreshes for pairs
		// whose segment crosses a dirty section.
		drainSectionUpdates();
		voxels.syncWindow(sectionCache, UPLOADS_PER_TICK);

		pollGameSources();
		collectHotCells(nowMillis);

		// 4./5. Compose → dispatch → fence → consume (synchronous within the
		// tick; the fence blocks only this thread).
		composeBatch(listenerX, listenerY, listenerZ);
		if (batch.size() > 0) {
			pipeline.dispatch(batch, voxels);
			if (pipeline.awaitResults(100_000_000L)) consumeResults(nowMillis);
		}

		// 2b. Gated neighbor merges for hot cells (spatial reuse).
		mergeNeighbors(nowMillis);

		// 3. Re-run the estimator for playing sources; push changed params.
		estimateAndApply(listenerX, listenerY, listenerZ);
		applyQueue.drainTo(gamePipeline);

		if (tick % 100 == 0) {
			store.evictIdle(nowMillis, CELL_IDLE_EVICT_MILLIS);
			connectivity.evictOld(nowMillis, CONNECTIVITY_EVICT_MILLIS);
		}
		publishStats();
	}

	// --- Stage 1: world data -------------------------------------------------

	private void drainSectionUpdates() {
		for (int i = 0; i < UPDATED_DRAIN_PER_TICK; i++) {
			final Long key = sectionCache.pollUpdated();
			if (key == null) return;
			voxels.onSectionUpdated(key, sectionCache.section(key));
			connectivity.invalidateCrossing(SectionKeys.x(key), SectionKeys.y(key), SectionKeys.z(key));
		}
	}

	private void pollGameSources() {
		for (final ActiveSources.GameSource source : sources.gameSources()) {
			final int state = AL10.alGetSourcei(source.sourceId, AL10.AL_SOURCE_STATE);
			if (state != AL10.AL_PLAYING) {
				sources.remove(source);
				continue;
			}
			AL10.alGetSourcefv(source.sourceId, AL10.AL_POSITION, positionScratch);
			source.x = positionScratch[0];
			source.y = positionScratch[1];
			source.z = positionScratch[2];
		}
		AL10.alGetError(); // clear any latch from source churn
	}

	private void collectHotCells(final long nowMillis) {
		hotCells.clear();
		hotKeys.clear();
		for (final ActiveSources.GameSource source : sources.gameSources()) addHotCell(source.cellKey(), nowMillis);
		for (final ActiveSources.Speaker speaker : sources.speakerSources()) addHotCell(speaker.cellKey(), nowMillis);
	}

	private void addHotCell(final long key, final long nowMillis) {
		if (!hotKeys.add(key)) return;
		final Cell cell = store.getOrCreate(key, nowMillis);
		cell.touch(nowMillis);
		hotCells.add(new HotCell(key, cell));
	}

	// --- Stage 4: batch composition (budget guarantees) -----------------------

	private void composeBatch(final float listenerX, final float listenerY, final float listenerZ) {
		batch.reset();

		final int emitters = sources.count();
		final int budget = Math.min(TracePipeline.MAX_RAYS,
				Math.min(Config.rayBudgetCap, Config.rayBudgetBase + Config.rayBudgetPerSource * emitters));
		final int revalFloor = budget * Config.revalidationFloorPct / 100;

		// Direct rays: one per playing source, never through the reservoir.
		for (final ActiveSources.GameSource source : sources.gameSources()) {
			if (batch.full()) break;
			batch.addMarch(source.x, source.y, source.z, listenerX, listenerY, listenerZ,
					Batch.KIND_DIRECT, 0L, source, 0, null);
		}
		for (final ActiveSources.Speaker speaker : sources.speakerSources()) {
			if (batch.full()) break;
			batch.addMarch((float) speaker.x, (float) speaker.y, (float) speaker.z,
					listenerX, listenerY, listenerZ, Batch.KIND_DIRECT, 0L, speaker, 0, null);
		}
		final int directCount = batch.size();

		// Connectivity refreshes: a cached trickle, not a per-batch cost.
		final List<ConnectivityCache.PairKey> pending = connectivity.drainPending(CONNECTIVITY_PER_TICK);
		for (final ConnectivityCache.PairKey pair : pending) {
			if (batch.full()) break;
			batch.addMarch(CellKeys.centerX(pair.low()), CellKeys.centerY(pair.low()), CellKeys.centerZ(pair.low()),
					CellKeys.centerX(pair.high()), CellKeys.centerY(pair.high()), CellKeys.centerZ(pair.high()),
					Batch.KIND_CONNECTIVITY, 0L, pair, 0, null);
		}
		final int connectivityCount = batch.size() - directCount;

		// Final legs: fresh lastHit→listener transmission for every sample in
		// cells with playing sources (this is what lets a stored sample serve a
		// moving listener).
		int finalLegCount = 0;
		for (final HotCell hot : hotCells) {
			final PathSample[][] samples = hot.cell().samples();
			for (int bucket = 0; bucket < Cell.BUCKETS; bucket++) {
				for (int slot = 0; slot < samples[bucket].length; slot++) {
					final PathSample sample = samples[bucket][slot];
					if (sample == null || batch.full()) continue;
					batch.addMarch(sample.lastHitX(), sample.lastHitY(), sample.lastHitZ(),
							listenerX, listenerY, listenerZ,
							Batch.KIND_FINAL_LEG, hot.key(), hot.cell(), bucket << 8 | slot, sample);
					finalLegCount++;
				}
			}
		}

		// Revalidation: floored slice, scheduled oldest-first so low-energy
		// tail samples can't be perpetually skipped (stale tails are the worst
		// artifact).
		collectRevalidationEntries();
		int remaining = budget - batch.size();
		final int revalAlloc = Math.min(revalEntries.size(), Math.max(revalFloor, remaining / 2));
		int revalCount = 0;
		for (final RevalEntry entry : revalEntries) {
			if (revalCount >= revalAlloc || batch.full()) break;
			batch.addChain(CellKeys.centerX(entry.key()), CellKeys.centerY(entry.key()), CellKeys.centerZ(entry.key()),
					entry.sample().seedDirX(), entry.sample().seedDirY(), entry.sample().seedDirZ(),
					entry.sample().bounces(), Batch.KIND_REVALIDATION, entry.key(), entry.cell(),
					entry.bucket() << 8 | entry.slot(), entry.sample());
			revalCount++;
		}

		// Fresh candidates for hot cells.
		remaining = budget - batch.size();
		int candidateCount = 0;
		if (!hotCells.isEmpty() && remaining > 0) {
			final int perCell = Math.min(CANDIDATES_PER_HOT_CELL, remaining / hotCells.size());
			for (final HotCell hot : hotCells) {
				for (int i = 0; i < perCell && !batch.full(); i++) {
					addCandidateRay(hot.key(), hot.cell(), Batch.KIND_CANDIDATE);
					candidateCount++;
				}
			}
		}

		// Neighbor prefetch with leftovers.
		int prefetchCount = 0;
		if (budget - batch.size() >= PREFETCH_PER_NEIGHBOR) {
			outer:
			for (final HotCell hot : hotCells) {
				for (final long neighborKey : CellKeys.neighbors6(hot.key())) {
					if (hotKeys.contains(neighborKey)) continue;
					if (budget - batch.size() < PREFETCH_PER_NEIGHBOR) break outer;
					final Cell neighbor = store.getOrCreate(neighborKey, System.currentTimeMillis());
					for (int i = 0; i < PREFETCH_PER_NEIGHBOR; i++) {
						addCandidateRay(neighborKey, neighbor, Batch.KIND_PREFETCH);
						prefetchCount++;
					}
				}
			}
		}

		final Stats stats = Stats.INSTANCE;
		stats.rayBudget = budget;
		stats.raysDirect = directCount;
		stats.raysConnectivity = connectivityCount;
		stats.raysFinalLegs = finalLegCount;
		stats.raysRevalidation = revalCount;
		stats.raysCandidates = candidateCount;
		stats.raysPrefetch = prefetchCount;
	}

	private void addCandidateRay(final long key, final Cell cell, final byte kind) {
		// Uniform sphere direction; the CPU keeps the seed so the sample can be
		// re-traced deterministically forever after.
		float dx;
		float dy;
		float dz;
		float lengthSq;
		do {
			dx = (float) rng.nextGaussian();
			dy = (float) rng.nextGaussian();
			dz = (float) rng.nextGaussian();
			lengthSq = dx * dx + dy * dy + dz * dz;
		} while (lengthSq < 1e-6f);
		final float inv = (float) (1.0 / Math.sqrt(lengthSq));
		batch.addChain(CellKeys.centerX(key), CellKeys.centerY(key), CellKeys.centerZ(key),
				dx * inv, dy * inv, dz * inv, MAX_BOUNCES, kind, key, cell, 0, null);
	}

	// Oldest-first over hot cells and their neighbors; also feeds the per-bucket
	// age percentiles (a mean would hide the starved tail).
	private void collectRevalidationEntries() {
		revalEntries.clear();
		for (final AgeBuffer buffer : ages) buffer.reset();

		final HashSet<Long> visited = new HashSet<>();
		for (final HotCell hot : hotCells) {
			collectCellSamples(hot.key(), hot.cell(), visited);
			for (final long neighborKey : CellKeys.neighbors6(hot.key())) {
				final Cell neighbor = store.get(neighborKey);
				if (neighbor != null) collectCellSamples(neighborKey, neighbor, visited);
			}
		}
		revalEntries.sort(Comparator.comparingLong(entry -> entry.sample().lastValidatedTick()));
	}

	private void collectCellSamples(final long key, final Cell cell, final HashSet<Long> visited) {
		if (!visited.add(key)) return;
		final PathSample[][] samples = cell.samples();
		for (int bucket = 0; bucket < Cell.BUCKETS; bucket++) {
			for (int slot = 0; slot < samples[bucket].length; slot++) {
				final PathSample sample = samples[bucket][slot];
				if (sample == null) continue;
				revalEntries.add(new RevalEntry(key, cell, bucket, slot, sample));
				ages[bucket].add((int) (tick - sample.lastValidatedTick()));
			}
		}
	}

	// --- Stage 2: consume the completed batch ---------------------------------

	private void consumeResults(final long nowMillis) {
		candidateStats.clear();
		for (int ray = 0; ray < batch.size(); ray++) {
			switch (batch.kind[ray]) {
				case Batch.KIND_CANDIDATE, Batch.KIND_PREFETCH -> consumeCandidate(ray);
				case Batch.KIND_REVALIDATION -> consumeRevalidation(ray);
				case Batch.KIND_FINAL_LEG -> consumeFinalLeg(ray);
				case Batch.KIND_DIRECT -> consumeDirect(ray);
				case Batch.KIND_CONNECTIVITY -> consumeConnectivity(ray, nowMillis);
				default -> throw new IllegalStateException("unknown ray kind " + batch.kind[ray]);
			}
		}
		// Measured energy density per cell: the estimator's magnitude source
		// (misses count, so open sky reads dry).
		for (final var entry : candidateStats.entrySet()) {
			entry.getKey().updateBucketEnergy(entry.getValue(), (int) entry.getValue()[4]);
		}
	}

	private void consumeCandidate(final int ray) {
		final Cell cell = (Cell) batch.tag[ray];
		final float[] stats = candidateStats.computeIfAbsent(cell, c -> new float[5]);
		stats[4]++;
		for (int prefix = 0; prefix < TracePipeline.RESULTS_PER_RAY; prefix++) {
			final int base = pipeline.resultBase(ray, prefix);
			if ((pipeline.resultFlags(base) & TracePipeline.FLAG_VALID) == 0) break;

			final float distance = pipeline.resultFloat(base + 3);
			final float energy = pipeline.resultFloat(base + 4);
			final float chainReflectivity = pipeline.resultFloat(base + 5);
			final int bucket = Estimator.bucketOf(distance, chainReflectivity);
			stats[bucket] += energy;
			final PathSample sample = new PathSample(
					batch.seedX[ray], batch.seedY[ray], batch.seedZ[ray], prefix + 1,
					pipeline.resultFloat(base), pipeline.resultFloat(base + 1), pipeline.resultFloat(base + 2),
					distance, energy, chainReflectivity, 1.0f, tick);
			cell.offerCandidate(bucket, sample, sample.weight(), rng);
		}
	}

	private void consumeRevalidation(final int ray) {
		final Cell cell = (Cell) batch.tag[ray];
		final PathSample expected = (PathSample) batch.expected[ray];
		final int bucket = batch.meta[ray] >> 8;
		final int slot = batch.meta[ray] & 0xFF;

		final int base = pipeline.resultBase(ray, expected.bounces() - 1);
		if ((pipeline.resultFlags(base) & TracePipeline.FLAG_VALID) == 0) {
			cell.dropSlot(bucket, slot, expected);
			return;
		}

		final float hitX = pipeline.resultFloat(base);
		final float hitY = pipeline.resultFloat(base + 1);
		final float hitZ = pipeline.resultFloat(base + 2);
		final float dx = hitX - expected.lastHitX();
		final float dy = hitY - expected.lastHitY();
		final float dz = hitZ - expected.lastHitZ();
		if (dx * dx + dy * dy + dz * dz > REVALIDATION_MATCH_DIST_SQ) {
			// Mismatched chain (world changed, or a merged sample that doesn't
			// survive its new origin): lose weight and fall out.
			cell.dropSlot(bucket, slot, expected);
			return;
		}

		cell.replaceSlot(bucket, slot, expected, expected.revalidated(hitX, hitY, hitZ,
				pipeline.resultFloat(base + 3), pipeline.resultFloat(base + 4),
				pipeline.resultFloat(base + 5), tick));
	}

	private void consumeFinalLeg(final int ray) {
		final Cell cell = (Cell) batch.tag[ray];
		final PathSample expected = (PathSample) batch.expected[ray];
		final float transmission = pipeline.resultFloat(pipeline.resultBase(ray, 0) + 6);
		cell.replaceSlot(batch.meta[ray] >> 8, batch.meta[ray] & 0xFF, expected,
				expected.withLegTransmission(transmission));
	}

	private void consumeDirect(final int ray) {
		final float transmission = pipeline.resultFloat(pipeline.resultBase(ray, 0) + 6);
		if (batch.tag[ray] instanceof ActiveSources.GameSource source) source.directTransmission = transmission;
		else if (batch.tag[ray] instanceof ActiveSources.Speaker speaker) speaker.directTransmission = transmission;
	}

	private void consumeConnectivity(final int ray, final long nowMillis) {
		final ConnectivityCache.PairKey pair = (ConnectivityCache.PairKey) batch.tag[ray];
		connectivity.complete(pair, pipeline.resultFloat(pipeline.resultBase(ray, 0) + 6), nowMillis);
	}

	// --- Gated merges (spatial reuse + cold-start inheritance) ---------------

	private void mergeNeighbors(final long nowMillis) {
		int accepts = 0;
		int partials = 0;
		int rejects = 0;
		for (final HotCell hot : hotCells) {
			for (final long neighborKey : CellKeys.neighbors6(hot.key())) {
				final Cell neighbor = store.get(neighborKey);
				if (neighbor == null) continue;

				final ConnectivityCache.Entry gate = connectivity.gate(hot.key(), neighborKey);
				if (gate == null) continue; // gate ray in flight; merge deferred one tick
				final float transmission = gate.transmission();
				if (transmission < GATE_REJECT_BELOW) {
					rejects++;
					continue;
				}
				if (transmission < 0.9f) partials++;
				else accepts++;
				store.mergeFrom(hot.cell(), neighbor, transmission, rng);
				neighbor.touch(nowMillis);
			}
		}
		Stats.INSTANCE.gateAccepts = accepts;
		Stats.INSTANCE.gatePartials = partials;
		Stats.INSTANCE.gateRejects = rejects;
	}

	// --- Stage 3: estimate + apply -------------------------------------------

	private void estimateAndApply(final float listenerX, final float listenerY, final float listenerZ) {
		for (final ActiveSources.GameSource source : sources.gameSources()) {
			final Cell cell = store.get(source.cellKey());
			final SoundEnvironment env = cell == null
					? Estimator.estimate(null, null, source.directTransmission, listenerX, listenerY, listenerZ)
					: Estimator.estimate(cell.samples(), cell.bucketEnergy(), source.directTransmission,
							listenerX, listenerY, listenerZ);
			if (!Estimator.audiblyDiffers(source.lastApplied, env)) continue;
			source.lastApplied = env;
			applyQueue.push(source.sourceId, env);
		}
		for (final ActiveSources.Speaker speaker : sources.speakerSources()) {
			final Cell cell = store.get(speaker.cellKey());
			final SoundEnvironment env = cell == null
					? Estimator.estimate(null, null, speaker.directTransmission, listenerX, listenerY, listenerZ)
					: Estimator.estimate(cell.samples(), cell.bucketEnergy(), speaker.directTransmission,
							listenerX, listenerY, listenerZ);
			if (!Estimator.audiblyDiffers(speaker.lastApplied, env)) continue;
			speaker.lastApplied = env;
			voiceSink.accept(speaker.id, env);
		}
	}

	private void publishStats() {
		final Stats stats = Stats.INSTANCE;
		stats.workerTick = tick;
		stats.playingSources = sources.gameSources().size();
		stats.voiceSpeakers = sources.speakerSources().size();
		stats.activeCells = hotCells.size();
		stats.storedCells = store.size();

		int agedTotal = 0;
		for (int bucket = 0; bucket < Cell.BUCKETS; bucket++) {
			stats.ageP95[bucket] = ages[bucket].percentile(95);
			stats.ageP99[bucket] = ages[bucket].percentile(99);
			agedTotal += ages[bucket].size();

			float occupancy = 0.0f;
			for (final HotCell hot : hotCells) occupancy += hot.cell().occupancy(bucket);
			stats.occupancy[bucket] = hotCells.isEmpty() ? 0.0f : occupancy / hotCells.size();
		}
		stats.agedSampleCount = agedTotal;
	}

	/** Growable int buffer with percentile readout; reused every tick. */
	private static final class AgeBuffer {
		private int[] values = new int[256];
		private int size;

		void reset() {
			size = 0;
		}

		void add(final int value) {
			if (size == values.length) values = java.util.Arrays.copyOf(values, size * 2);
			values[size++] = value;
		}

		int size() {
			return size;
		}

		int percentile(final int pct) {
			if (size == 0) return 0;
			java.util.Arrays.sort(values, 0, size);
			return values[Math.min(size - 1, size * pct / 100)];
		}
	}
}
