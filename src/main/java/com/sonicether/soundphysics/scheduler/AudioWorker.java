package com.sonicether.soundphysics.scheduler;

import com.sonicether.soundphysics.Config;
import com.sonicether.soundphysics.SoundEnvironment;
import com.sonicether.soundphysics.SoundPhysics;
import com.sonicether.soundphysics.efx.ApplyQueue;
import com.sonicether.soundphysics.efx.DynamicsState;
import com.sonicether.soundphysics.efx.EfxPipeline;
import com.sonicether.soundphysics.field.CellKeys;
import com.sonicether.soundphysics.field.CellProbe;
import com.sonicether.soundphysics.field.Edge;
import com.sonicether.soundphysics.field.FieldStore;
import com.sonicether.soundphysics.field.ListenerField;
import com.sonicether.soundphysics.gpu.Batch;
import com.sonicether.soundphysics.gpu.TraceContext;
import com.sonicether.soundphysics.gpu.TracePipeline;
import com.sonicether.soundphysics.gpu.VoxelStore;
import com.sonicether.soundphysics.instrumentation.Stats;
import com.sonicether.soundphysics.shape.Estimator;
import com.sonicether.soundphysics.world.Materials;
import com.sonicether.soundphysics.world.SectionCache;
import com.sonicether.soundphysics.world.SectionKeys;
import me.eigenraven.lwjgl3ify.api.Lwjgl3Aware;
import org.lwjgl.openal.AL10;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiConsumer;

/**
 * The audio worker: one thread, one loop, fixed rate, independent of FPS.
 * Owns the tracer's GL context and the single-writer side of the transport
 * field. Each tick it bakes the field outward from the listener (anchors,
 * edge hero rays, cell probe rounds — nearest first, so the acoustics that
 * matter are warm first), validates the field's proposed path for every
 * playing source with real rays, rebuilds the listener's Dijkstra view, and
 * pushes changed params out through the EFX apply queue (game sources) and
 * the voice env sink (speakers). Tracing never happens on a sound play path.
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
	private static final int PROBE_RAYS_PER_ROUND = 16;
	private static final int MAX_EDGE_RAYS = 4;
	private static final int ANCHORS_PER_TICK = 256;
	private static final int PATH_MAX_PORTALS = 32;
	// Probe rounds age out so the EMA keeps converging (one round is noisy).
	// Hot cells — the listener's (drives reverb dynamics) and every cell with
	// a playing source (the only stats anyone can hear) — re-round fast.
	private static final long PROBE_MAX_AGE_TICKS = 40;
	private static final long HOT_PROBE_MAX_AGE_TICKS = 5;
	private static final int EVICT_INTERVAL_TICKS = 100;
	private static final int EVICT_MARGIN_CELLS = 3;

	private final TraceContext context;
	private final SectionCache sectionCache;
	private final FieldStore field;
	private final ListenerField listenerField;
	private final ActiveSources sources;
	private final ApplyQueue applyQueue;
	private final EfxPipeline gamePipeline;
	private final RainProbes rainProbes;

	private final VoxelStore voxels = new VoxelStore();
	private final TracePipeline pipeline = new TracePipeline();
	private final Batch batch = new Batch(TracePipeline.MAX_RAYS);
	private final Random rng = new Random();
	private final float[] palette = new float[256 * 4];
	private final float[] positionScratch = new float[3];
	private final float[] portalScratch = new float[PATH_MAX_PORTALS * 3];

	private volatile boolean alive = true;
	private volatile boolean paletteDirty = true;
	private volatile boolean worldChanged;
	private volatile BiConsumer<UUID, SoundEnvironment> voiceSink = (id, env) -> {
	};

	private long tick;

	// Bake sweep: cell offsets sorted by distance from the listener's cell,
	// rebuilt when the configured radius changes. Packed distSq|dx|dy|dz.
	private long[] sweepOffsets = new long[0];
	private int sweepRadiusCells = -1;

	// Per-tick accumulators, reused across ticks.
	private final List<ProbeRound> probeRounds = new ArrayList<>();
	private final List<EdgeBake> edgeBakes = new ArrayList<>();
	private final List<PathValidation> pathValidations = new ArrayList<>();
	private final java.util.HashSet<Long> hotCellKeys = new java.util.HashSet<>();

	public AudioWorker(final TraceContext context, final SectionCache sectionCache, final FieldStore field,
			final ListenerField listenerField, final ActiveSources sources, final ApplyQueue applyQueue,
			final EfxPipeline gamePipeline, final RainProbes rainProbes) {
		super("SoundPhysics worker");
		setDaemon(true);
		this.context = context;
		this.sectionCache = sectionCache;
		this.field = field;
		this.listenerField = listenerField;
		this.sources = sources;
		this.applyQueue = applyQueue;
		this.gamePipeline = gamePipeline;
		this.rainProbes = rainProbes;
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
		final long listenerKey = CellKeys.ofBlock(listenerX, listenerY, listenerZ);
		final int radiusCells = radiusCells();
		voxels.updateWindow(listenerX, listenerZ);

		// 1. Dirty/entering sections → GPU voxels + field invalidation.
		drainSectionUpdates();
		voxels.syncWindow(sectionCache, UPLOADS_PER_TICK);

		pollGameSources();

		// 2. Compose → dispatch → fence → consume (synchronous within the
		// tick; the fence blocks only this thread).
		composeBatch(listenerX, listenerY, listenerZ, listenerKey, radiusCells);
		if (batch.size() > 0) {
			pipeline.dispatch(batch, voxels);
			if (pipeline.awaitResults(100_000_000L)) {
				consumeResults();
				rainProbes.selectBest(listenerX, listenerY, listenerZ);
			} else {
				abortBatchAccumulators();
			}
		}

		// 3. The listener's view of the fresh field, then per-source params.
		listenerField.rebuild(field, listenerKey, listenerX, listenerY, listenerZ, radiusCells);
		estimateAndApply(listenerX, listenerY, listenerZ);
		updateReverbDynamics(listenerX, listenerY, listenerZ);
		applyQueue.drainTo(gamePipeline);

		if (tick % EVICT_INTERVAL_TICKS == 0) {
			field.evictBeyond(listenerKey, radiusCells + EVICT_MARGIN_CELLS);
		}
		publishStats();
	}

	private static int radiusCells() {
		return Math.max(2, (Config.fieldRadius + CellKeys.CELL_SIZE - 1) / CellKeys.CELL_SIZE);
	}

	// --- Stage 1: world data -------------------------------------------------

	private void drainSectionUpdates() {
		for (int i = 0; i < UPDATED_DRAIN_PER_TICK; i++) {
			final Long key = sectionCache.pollUpdated();
			if (key == null) return;
			voxels.onSectionUpdated(key, sectionCache.section(key));
			field.invalidateSection(SectionKeys.x(key), SectionKeys.y(key), SectionKeys.z(key));
		}
	}

	private void pollGameSources() {
		for (final ActiveSources.GameSource source : sources.gameSources()) {
			// Speed-of-sound delay: paused at play, resumed here when the
			// wavefront arrives; exempt from liveness pruning until then.
			if (source.resumeAtNanos != 0L) {
				if (System.nanoTime() < source.resumeAtNanos) continue;
				AL10.alSourcePlay(source.sourceId);
				source.resumeAtNanos = 0L;
			}
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

	// --- Stage 2: batch composition -------------------------------------------

	private void composeBatch(final float listenerX, final float listenerY, final float listenerZ,
			final long listenerKey, final int radiusCells) {
		batch.reset();
		probeRounds.clear();
		edgeBakes.clear();
		pathValidations.clear();
		hotCellKeys.clear();
		hotCellKeys.add(listenerKey);
		for (final ActiveSources.GameSource source : sources.gameSources()) {
			if (!source.directOnly) hotCellKeys.add(source.cellKey());
		}
		for (final ActiveSources.Speaker speaker : sources.speakerSources()) {
			hotCellKeys.add(speaker.cellKey());
		}

		final int emitters = sources.count();
		final int budget = Math.min(TracePipeline.MAX_RAYS,
				Math.min(Config.rayBudgetCap, Config.rayBudgetBase + Config.rayBudgetPerSource * emitters));

		// Direct rays: one bundle per playing source, straight-line occlusion.
		for (final ActiveSources.GameSource source : sources.gameSources()) {
			if (batch.full()) break;
			batch.addDirect(source.x, source.y, source.z, listenerX, listenerY, listenerZ, source, 0);
		}
		for (final ActiveSources.Speaker speaker : sources.speakerSources()) {
			if (batch.full()) break;
			batch.addDirect((float) speaker.x, (float) speaker.y, (float) speaker.z,
					listenerX, listenerY, listenerZ, speaker, 0);
		}
		// Rain anchor probes: candidate landing columns, scored by measured
		// transmission so an open door beats a nearer roof.
		final float[] probes = rainProbes.beginMeasure();
		for (int i = 0; i * 3 + 2 < probes.length && !batch.full(); i++) {
			batch.addDirect(probes[i * 3], probes[i * 3 + 1], probes[i * 3 + 2],
					listenerX, listenerY, listenerZ, rainProbes, i);
		}
		final int directCount = batch.size();

		// Path validation: the graph proposed a route for each playing source;
		// march its polyline for real. This is what caps the compounded
		// per-edge error — the graph proposes, the GPU confirms.
		for (final ActiveSources.GameSource source : sources.gameSources()) {
			if (source.directOnly) continue;
			composePathValidation(source.cellKey(), source.x, source.y, source.z,
					listenerX, listenerY, listenerZ, source, budget);
		}
		for (final ActiveSources.Speaker speaker : sources.speakerSources()) {
			composePathValidation(speaker.cellKey(), (float) speaker.x, (float) speaker.y, (float) speaker.z,
					listenerX, listenerY, listenerZ, speaker, budget);
		}
		final int pathCount = batch.size() - directCount;

		// Bake sweep: nearest cells first — anchors, edges, probe rounds.
		final int probeEdgeStart = batch.size();
		bakeSweep(listenerKey, radiusCells, budget);

		final Stats stats = Stats.INSTANCE;
		stats.rayBudget = budget;
		stats.raysDirect = directCount;
		stats.raysPath = pathCount;
		int edgeRays = 0;
		for (final EdgeBake bake : edgeBakes) edgeRays += bake.rays;
		stats.raysEdge = edgeRays;
		stats.raysProbe = batch.size() - probeEdgeStart - edgeRays;
	}

	private void composePathValidation(final long cellKey, final float sourceX, final float sourceY,
			final float sourceZ, final float listenerX, final float listenerY, final float listenerZ,
			final Object target, final int budget) {
		final long resolved = listenerField.resolve(cellKey);
		final int portals = resolved == ListenerField.NO_NODE ? -1
				: listenerField.portalChain(resolved, portalScratch);
		if (portals < 0 || budget - batch.size() < portals + 1) {
			commitPathTransmission(target, Float.NaN, Float.NaN);
			return;
		}
		final PathValidation validation = new PathValidation(target);
		float fromX = sourceX;
		float fromY = sourceY;
		float fromZ = sourceZ;
		for (int i = 0; i < portals; i++) {
			batch.addMarch(fromX, fromY, fromZ,
					portalScratch[i * 3], portalScratch[i * 3 + 1], portalScratch[i * 3 + 2],
					false, Batch.KIND_PATH, validation, 0);
			fromX = portalScratch[i * 3];
			fromY = portalScratch[i * 3 + 1];
			fromZ = portalScratch[i * 3 + 2];
		}
		batch.addMarch(fromX, fromY, fromZ, listenerX, listenerY, listenerZ,
				true, Batch.KIND_PATH, validation, 0);
		pathValidations.add(validation);
	}

	// Nearest cells first. Anchors come from the CPU section cache (budgeted —
	// a scan is 512 byte reads); edges and probe rounds spend the ray budget.
	// In steady state everything is fresh and this loop is a cheap walk; after
	// a block edit or a teleport it rebuilds the nearest acoustics first.
	private void bakeSweep(final long listenerKey, final int radiusCells, final int budget) {
		ensureSweepOffsets(radiusCells);
		final int listenerCellX = CellKeys.cellX(listenerKey);
		final int listenerCellY = CellKeys.cellY(listenerKey);
		final int listenerCellZ = CellKeys.cellZ(listenerKey);
		int anchorBudget = ANCHORS_PER_TICK;

		for (final long packed : sweepOffsets) {
			if (budget - batch.size() < MAX_EDGE_RAYS && anchorBudget <= 0) return;
			final int cellY = listenerCellY + unpackDy(packed);
			if (cellY < 0 || cellY > CellKeys.MAX_CELL_Y) continue;
			final long key = CellKeys.pack(listenerCellX + unpackDx(packed), cellY, listenerCellZ + unpackDz(packed));

			final CellProbe probe = field.probeOrCreate(key);
			anchorBudget -= refreshAnchorIfNeeded(probe, key, anchorBudget);
			if (!probe.hasAir()) continue;

			for (final long neighborKey : positiveNeighbors(key)) {
				if (Math.abs(CellKeys.cellX(neighborKey) - listenerCellX) > radiusCells
						|| Math.abs(CellKeys.cellY(neighborKey) - listenerCellY) > radiusCells
						|| Math.abs(CellKeys.cellZ(neighborKey) - listenerCellZ) > radiusCells) continue;
				if (CellKeys.cellY(neighborKey) < 0 || CellKeys.cellY(neighborKey) > CellKeys.MAX_CELL_Y) continue;
				final CellProbe neighbor = field.probeOrCreate(neighborKey);
				anchorBudget -= refreshAnchorIfNeeded(neighbor, neighborKey, anchorBudget);
				if (!neighbor.hasAir()) continue;
				final Edge edge = field.edgeOrCreate(key, neighborKey);
				if (!edge.needsBake()) continue;
				if (budget - batch.size() < MAX_EDGE_RAYS) continue;
				composeEdgeBake(key, probe, neighborKey, neighbor, edge);
			}

			final long maxAge = hotCellKeys.contains(key) ? HOT_PROBE_MAX_AGE_TICKS : PROBE_MAX_AGE_TICKS;
			if (!probe.needsProbe(tick, maxAge)) continue;
			if (budget - batch.size() < PROBE_RAYS_PER_ROUND) continue;
			composeProbeRound(probe);
		}
	}

	/** Returns 1 when an anchor scan was spent, 0 otherwise. */
	private int refreshAnchorIfNeeded(final CellProbe probe, final long key, final int anchorBudget) {
		if (anchorBudget <= 0) return 0;
		if (probe.airPoints() != null && !probe.anchorStale()) return 0;
		return field.refreshAirPoints(sectionCache, key) ? 1 : 0;
	}

	// The three positive-axis neighbors: every edge visited exactly once.
	private static long[] positiveNeighbors(final long key) {
		final int x = CellKeys.cellX(key);
		final int y = CellKeys.cellY(key);
		final int z = CellKeys.cellZ(key);
		return new long[] { CellKeys.pack(x + 1, y, z), CellKeys.pack(x, y + 1, z), CellKeys.pack(x, y, z + 1) };
	}

	private void composeEdgeBake(final long keyA, final CellProbe a, final long keyB, final CellProbe b,
			final Edge edge) {
		final float[] pointsA = a.airPoints();
		final float[] pointsB = b.airPoints();
		final int countA = pointsA.length / 3;
		final int countB = pointsB.length / 3;
		final int rays = Math.min(MAX_EDGE_RAYS, Math.max(countA, countB));

		edge.beginBake();
		final EdgeBake bake = new EdgeBake(edge, rays);
		for (int i = 0; i < rays && !batch.full(); i++) {
			final int ia = i % countA * 3;
			final int ib = i % countB * 3;
			computePortal(keyA, keyB, pointsA[ia], pointsA[ia + 1], pointsA[ia + 2],
					pointsB[ib], pointsB[ib + 1], pointsB[ib + 2], bake.portals, i);
			batch.addMarch(pointsA[ia], pointsA[ia + 1], pointsA[ia + 2],
					pointsB[ib], pointsB[ib + 1], pointsB[ib + 2], false, Batch.KIND_EDGE, bake, i);
		}
		edgeBakes.add(bake);
	}

	// Where the hero ray's segment crosses the shared face between the two
	// cells — the portal candidate. Clamped into the face rectangle so a
	// diagonal between off-axis air points can't claim a portal in a third
	// cell.
	private static void computePortal(final long keyA, final long keyB,
			final float ax, final float ay, final float az, final float bx, final float by, final float bz,
			final float[] out, final int index) {
		final int size = CellKeys.CELL_SIZE;
		final int dx = CellKeys.cellX(keyB) - CellKeys.cellX(keyA);
		final int dy = CellKeys.cellY(keyB) - CellKeys.cellY(keyA);
		final int axis = dx != 0 ? 0 : dy != 0 ? 1 : 2;
		final float plane = axis == 0
				? size * Math.max(CellKeys.cellX(keyA), CellKeys.cellX(keyB))
				: axis == 1
						? size * Math.max(CellKeys.cellY(keyA), CellKeys.cellY(keyB))
						: size * Math.max(CellKeys.cellZ(keyA), CellKeys.cellZ(keyB));

		final float originAxis = axis == 0 ? ax : axis == 1 ? ay : az;
		final float targetAxis = axis == 0 ? bx : axis == 1 ? by : bz;
		final float span = targetAxis - originAxis;
		final float t = Math.abs(span) < 1e-6f ? 0.5f : (plane - originAxis) / span;

		float px = ax + (bx - ax) * t;
		float py = ay + (by - ay) * t;
		float pz = az + (bz - az) * t;
		if (axis != 0) px = clampToFace(px, CellKeys.cellX(keyA), size);
		if (axis != 1) py = clampToFace(py, CellKeys.cellY(keyA), size);
		if (axis != 2) pz = clampToFace(pz, CellKeys.cellZ(keyA), size);
		out[index * 3] = px;
		out[index * 3 + 1] = py;
		out[index * 3 + 2] = pz;
	}

	private static float clampToFace(final float value, final int cellCoord, final int size) {
		return Math.max(cellCoord * size + 0.25f, Math.min(cellCoord * size + size - 0.25f, value));
	}

	private void composeProbeRound(final CellProbe probe) {
		probe.beginProbeRound();
		final ProbeRound round = new ProbeRound(probe);
		for (int i = 0; i < PROBE_RAYS_PER_ROUND && !batch.full(); i++) {
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
			batch.addChain(probe.anchorX(), probe.anchorY(), probe.anchorZ(),
					dx * inv, dy * inv, dz * inv, MAX_BOUNCES, Batch.KIND_PROBE, round, 0);
			round.rays++;
		}
		probeRounds.add(round);
	}

	// --- Stage 3: consume the completed batch ---------------------------------

	private void consumeResults() {
		for (int ray = 0; ray < batch.size(); ray++) {
			switch (batch.kind[ray]) {
				case Batch.KIND_PROBE -> consumeProbe(ray);
				case Batch.KIND_EDGE -> consumeEdge(ray);
				case Batch.KIND_PATH -> consumePath(ray);
				case Batch.KIND_DIRECT -> consumeDirect(ray);
				default -> throw new IllegalStateException("unknown ray kind " + batch.kind[ray]);
			}
		}
		for (final ProbeRound round : probeRounds) round.commit(tick);
		for (final EdgeBake bake : edgeBakes) bake.commit();
		for (final PathValidation validation : pathValidations) {
			commitPathTransmission(validation.target, validation.high, validation.low);
		}
		Stats.INSTANCE.probeRounds = probeRounds.size();
		Stats.INSTANCE.edgeBakes = edgeBakes.size();
		Stats.INSTANCE.pathValidations = pathValidations.size();
	}

	// Fence timeout: nothing landed. Everything staged this tick becomes
	// eligible again next tick instead of wedging in "pending".
	private void abortBatchAccumulators() {
		for (final ProbeRound round : probeRounds) round.probe.abortProbeRound();
		for (final EdgeBake bake : edgeBakes) bake.edge.abortBake();
		for (final PathValidation validation : pathValidations) {
			commitPathTransmission(validation.target, Float.NaN, Float.NaN);
		}
	}

	private void consumeProbe(final int ray) {
		final ProbeRound round = (ProbeRound) batch.tag[ray];
		if ((pipeline.resultFlags(pipeline.resultBase(ray, 0)) & TracePipeline.FLAG_VALID) == 0) {
			// No first bounce at all: this direction leads straight to open sky.
			round.escapes++;
			round.escapeX += batch.seedX[ray];
			round.escapeY += batch.seedY[ray];
			round.escapeZ += batch.seedZ[ray];
			return;
		}
		for (int prefix = 0; prefix < TracePipeline.RESULTS_PER_RAY; prefix++) {
			final int base = pipeline.resultBase(ray, prefix);
			if ((pipeline.resultFlags(base) & TracePipeline.FLAG_VALID) == 0) break;
			round.addHit(pipeline.resultFloat(base), pipeline.resultFloat(base + 1), pipeline.resultFloat(base + 2),
					pipeline.resultFloat(base + 3), pipeline.resultFloat(base + 4), pipeline.resultFloat(base + 5));
		}
	}

	private void consumeEdge(final int ray) {
		final int base = pipeline.resultBase(ray, 0);
		((EdgeBake) batch.tag[ray]).report(batch.meta[ray],
				pipeline.resultFloat(base + 6), pipeline.resultFloat(base + 4));
	}

	private void consumePath(final int ray) {
		final PathValidation validation = (PathValidation) batch.tag[ray];
		final int base = pipeline.resultBase(ray, 0);
		validation.high *= pipeline.resultFloat(base + 6);
		validation.low *= pipeline.resultFloat(base + 4);
	}

	private void consumeDirect(final int ray) {
		final int base = pipeline.resultBase(ray, 0);
		final float high = pipeline.resultFloat(base + 6);
		final float low = pipeline.resultFloat(base + 4);
		if (batch.tag[ray] instanceof ActiveSources.GameSource source) {
			logDirectDebug(source, high);
			source.directTransmission = high;
			source.directTransmissionLow = low;
		} else if (batch.tag[ray] instanceof ActiveSources.Speaker speaker) {
			speaker.directTransmission = high;
			speaker.directTransmissionLow = low;
		} else if (batch.tag[ray] == rainProbes) {
			rainProbes.report(batch.meta[ray], high, low);
		}
	}

	// Diagnosis aid, F3-flag gated: one line whenever a source's measured
	// direct transmission moves appreciably, with the exact ray endpoints.
	private void logDirectDebug(final ActiveSources.GameSource source, final float transmission) {
		if (!Config.debugInfoShow) return;
		if (Math.abs(transmission - source.directTransmission) < 0.05f) return;
		SoundPhysics.log(String.format(java.util.Locale.ROOT,
				"direct src=%d (%.2f, %.2f, %.2f) -> listener (%.2f, %.2f, %.2f) trans %.3f -> %.3f",
				source.sourceId, source.x, source.y, source.z,
				sectionCache.listenerX(), sectionCache.listenerY(), sectionCache.listenerZ(),
				source.directTransmission, transmission));
	}

	private static void commitPathTransmission(final Object target, final float high, final float low) {
		if (target instanceof ActiveSources.GameSource source) {
			source.pathTransHigh = high;
			source.pathTransLow = low;
		} else if (target instanceof ActiveSources.Speaker speaker) {
			speaker.pathTransHigh = high;
			speaker.pathTransLow = low;
		}
	}

	// --- Stage 4: estimate + apply -------------------------------------------

	// Exponential smoothing rate for audible parameters (per second): fast
	// enough to track walking through a doorway, slow enough that measurement
	// churn never reaches the ears as flicker.
	private static final float SMOOTH_RATE = 8.0f;

	private float smoothingAlpha() {
		return 1.0f - (float) Math.exp(-SMOOTH_RATE / Math.max(1, Config.workerRateHz));
	}

	private void estimateAndApply(final float listenerX, final float listenerY, final float listenerZ) {
		final float alpha = smoothingAlpha();
		for (final ActiveSources.GameSource source : sources.gameSources()) {
			final SoundEnvironment target = estimateSource(source.directOnly ? 0L : source.cellKey(),
					source.directOnly, source.x, source.y, source.z, source.pathTransHigh, source.pathTransLow,
					source.directTransmission, source.directTransmissionLow, listenerX, listenerY, listenerZ);
			source.smoothed = Estimator.smooth(source.smoothed, target, alpha);
			if (!Estimator.audiblyDiffers(source.lastApplied, source.smoothed)) continue;
			source.lastApplied = source.smoothed;
			applyQueue.push(source.sourceId, source.smoothed);
		}
		for (final ActiveSources.Speaker speaker : sources.speakerSources()) {
			final SoundEnvironment target = estimateSource(speaker.cellKey(), false,
					(float) speaker.x, (float) speaker.y, (float) speaker.z, speaker.pathTransHigh,
					speaker.pathTransLow, speaker.directTransmission, speaker.directTransmissionLow,
					listenerX, listenerY, listenerZ);
			speaker.smoothed = Estimator.smooth(speaker.smoothed, target, alpha);
			if (!Estimator.audiblyDiffers(speaker.lastApplied, speaker.smoothed)) continue;
			speaker.lastApplied = speaker.smoothed;
			voiceSink.accept(speaker.id, speaker.smoothed);
		}
	}

	private SoundEnvironment estimateSource(final long cellKey, final boolean directOnly,
			final float sourceX, final float sourceY, final float sourceZ,
			final float validatedHigh, final float validatedLow,
			final float directHigh, final float directLow,
			final float listenerX, final float listenerY, final float listenerZ) {
		final float dx = sourceX - listenerX;
		final float dy = sourceY - listenerY;
		final float dz = sourceZ - listenerZ;
		final float euclid = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
		// A direct-only source must not borrow the reverb of whatever cell
		// happens to share its position.
		if (directOnly) {
			return Estimator.estimate(null, 0.0f, 0.0f, euclid, euclid, directHigh, directLow);
		}

		// A source in a solid, airless cell (block-place clicks) radiates from
		// the adjacent air cell — that's the node and probe that apply.
		final long resolved = listenerField.resolve(cellKey);
		final ListenerField.Node node = resolved == ListenerField.NO_NODE ? null : listenerField.sample(resolved);
		float pathHigh = node == null ? 0.0f : node.transHigh();
		float pathLow = node == null ? 0.0f : node.transLow();
		final float pathDist = node == null ? euclid : node.pathDist();
		// The validated polyline beats the graph's edge product when it landed.
		if (!Float.isNaN(validatedHigh)) {
			pathHigh = validatedHigh;
			pathLow = validatedLow;
		}
		return Estimator.estimate(field.interpolatedStats(sourceX, sourceY, sourceZ),
				pathHigh, pathLow, pathDist, euclid, directHigh, directLow);
	}

	// --- Listener-environment reverb dynamics (directional reverb + material
	// brightness): the listener cell's probe stats — energy-weighted mean
	// reflection direction and surface reflectivity per bucket — rotated into
	// listener space and pushed to the reverb effects.

	private final float[] dynPan = new float[12];
	private final float[] dynHf = new float[4];
	private final float[] smoothedDynPan = new float[12];
	private final float[] smoothedDynHf = { 1.0f, 1.0f, 1.0f, 1.0f };
	private final float[] lastDynPan = new float[12];
	private final float[] lastDynHf = { 1.0f, 1.0f, 1.0f, 1.0f };

	private void updateReverbDynamics(final float listenerX, final float listenerY, final float listenerZ) {
		// Listener basis from the published look vector (matches the AL
		// listener orientation): x right, y up, z backward.
		final float fx = com.sonicether.soundphysics.ListenerState.forwardX;
		final float fy = com.sonicether.soundphysics.ListenerState.forwardY;
		final float fz = com.sonicether.soundphysics.ListenerState.forwardZ;
		float rightX = -fz;
		float rightZ = fx;
		final float rightLen = (float) Math.sqrt(rightX * rightX + rightZ * rightZ);
		if (rightLen < 1e-3f) {
			rightX = 1.0f;
			rightZ = 0.0f;
		} else {
			rightX /= rightLen;
			rightZ /= rightLen;
		}
		// up = right × forward, with right = (rightX, 0, rightZ).
		final float ux = -rightZ * fy;
		final float uy = rightZ * fx - rightX * fz;
		final float uz = rightX * fy;

		// Interpolated at the listener's exact position — the room's character
		// glides across cell boundaries instead of stepping.
		final CellProbe.Stats stats = field.interpolatedStats(listenerX, listenerY, listenerZ);
		for (int bucket = 0; bucket < CellProbe.BUCKETS; bucket++) {
			if (stats == null || stats.energy()[bucket] < 1e-5f) {
				dynPan[bucket * 3] = 0.0f;
				dynPan[bucket * 3 + 1] = 0.0f;
				dynPan[bucket * 3 + 2] = 0.0f;
				dynHf[bucket] = 1.0f;
				continue;
			}
			// The stored mean of unit directions: its length is the agreement —
			// omni fields pan to zero.
			final float mx = stats.dirX()[bucket];
			final float my = stats.dirY()[bucket];
			final float mz = stats.dirZ()[bucket];
			final float agreement = Math.min(1.0f, (float) Math.sqrt(mx * mx + my * my + mz * mz));
			final float scale = agreement * 0.8f; // keep magnitude inside EFX range

			dynPan[bucket * 3] = (mx * rightX + mz * rightZ) * scale;
			dynPan[bucket * 3 + 1] = (mx * ux + my * uy + mz * uz) * scale;
			dynPan[bucket * 3 + 2] = -(mx * fx + my * fy + mz * fz) * scale;
			// Surface brightness: stone/metal keep highs ringing, cloth kills them.
			dynHf[bucket] = 0.35f + 0.9f * stats.reflectivity()[bucket];
		}

		// Openings pull the late reverb: in a half-enclosed space (weight peaks
		// at 50% open, zero when sealed or fully outdoors) the tail leans
		// toward where the probe rays escape — the door, the cave mouth.
		if (stats != null && stats.escapeRatio() > 0.0f) {
			final float ratio = stats.escapeRatio();
			final float openingWeight = 4.0f * ratio * (1.0f - ratio) * 0.4f;
			final float len = (float) Math.sqrt(stats.escapeX() * stats.escapeX()
					+ stats.escapeY() * stats.escapeY() + stats.escapeZ() * stats.escapeZ());
			if (len > 1e-4f && openingWeight > 0.01f) {
				final float ex = stats.escapeX() / len;
				final float ey = stats.escapeY() / len;
				final float ez = stats.escapeZ() / len;
				for (int bucket = 2; bucket < CellProbe.BUCKETS; bucket++) {
					dynPan[bucket * 3] += (ex * rightX + ez * rightZ) * openingWeight;
					dynPan[bucket * 3 + 1] += (ex * ux + ey * uy + ez * uz) * openingWeight;
					dynPan[bucket * 3 + 2] += -(ex * fx + ey * fy + ez * fz) * openingWeight;
				}
			}
			Stats.INSTANCE.escapeRatio = ratio;
		}
		for (int i = 6; i < 12; i++) dynPan[i] = Math.max(-0.85f, Math.min(0.85f, dynPan[i]));

		// Same smoothing philosophy as the per-source envs: the room's
		// character glides, it never snaps.
		final float alpha = smoothingAlpha();
		for (int i = 0; i < 12; i++) smoothedDynPan[i] += alpha * (dynPan[i] - smoothedDynPan[i]);
		for (int i = 0; i < 4; i++) smoothedDynHf[i] += alpha * (dynHf[i] - smoothedDynHf[i]);

		float delta = 0.0f;
		for (int i = 0; i < 12; i++) delta = Math.max(delta, Math.abs(smoothedDynPan[i] - lastDynPan[i]));
		for (int i = 0; i < 4; i++) delta = Math.max(delta, Math.abs(smoothedDynHf[i] - lastDynHf[i]));
		if (delta < 0.02f) return;

		System.arraycopy(smoothedDynPan, 0, lastDynPan, 0, 12);
		System.arraycopy(smoothedDynHf, 0, lastDynHf, 0, 4);
		gamePipeline.updateReverbDynamics(smoothedDynPan, smoothedDynHf);
		DynamicsState.publish(smoothedDynPan, smoothedDynHf);
	}

	private void publishStats() {
		final Stats stats = Stats.INSTANCE;
		stats.workerTick = tick;
		stats.playingSources = sources.gameSources().size();
		stats.voiceSpeakers = sources.speakerSources().size();
		stats.cellsStored = field.cellCount();
		stats.edgesStored = field.edgeCount();
		stats.fieldNodes = listenerField.size();
	}

	// --- Sweep offsets ---------------------------------------------------------

	// Cell offsets within the radius, packed distSq(24)|dx+64(16)|dy+64(8)|dz+64
	// and sorted ascending — the natural sort IS the distance sort.
	private void ensureSweepOffsets(final int radiusCells) {
		if (radiusCells == sweepRadiusCells) return;
		sweepRadiusCells = radiusCells;

		int count = 0;
		final int span = radiusCells * 2 + 1;
		final long[] offsets = new long[span * span * span];
		for (int dx = -radiusCells; dx <= radiusCells; dx++) {
			for (int dy = -radiusCells; dy <= radiusCells; dy++) {
				for (int dz = -radiusCells; dz <= radiusCells; dz++) {
					final int distSq = dx * dx + dy * dy + dz * dz;
					if (distSq > radiusCells * radiusCells) continue;
					offsets[count++] = (long) distSq << 24
							| (long) (dx + 64) << 16 | (long) (dy + 64) << 8 | dz + 64;
				}
			}
		}
		sweepOffsets = java.util.Arrays.copyOf(offsets, count);
		java.util.Arrays.sort(sweepOffsets);
	}

	private static int unpackDx(final long packed) {
		return (int) (packed >> 16 & 0xFF) - 64;
	}

	private static int unpackDy(final long packed) {
		return (int) (packed >> 8 & 0xFF) - 64;
	}

	private static int unpackDz(final long packed) {
		return (int) (packed & 0xFF) - 64;
	}

	// --- Per-tick accumulators ---------------------------------------------------

	/** One cell's probe round: aggregates its chain-ray hits, commits as Stats. */
	private static final class ProbeRound {
		final CellProbe probe;
		final float[] energy = new float[CellProbe.BUCKETS];
		final float[] reflectivityE = new float[CellProbe.BUCKETS];
		final float[] distanceE = new float[CellProbe.BUCKETS];
		final float[] dirX = new float[CellProbe.BUCKETS];
		final float[] dirY = new float[CellProbe.BUCKETS];
		final float[] dirZ = new float[CellProbe.BUCKETS];
		int rays;
		int escapes;
		float escapeX;
		float escapeY;
		float escapeZ;

		ProbeRound(final CellProbe probe) {
			this.probe = probe;
		}

		void addHit(final float hitX, final float hitY, final float hitZ,
				final float distance, final float delivered, final float reflectivity) {
			final int bucket = Estimator.bucketOf(distance, reflectivity);
			energy[bucket] += delivered;
			reflectivityE[bucket] += reflectivity * delivered;
			distanceE[bucket] += distance * delivered;
			final float dx = hitX - probe.anchorX();
			final float dy = hitY - probe.anchorY();
			final float dz = hitZ - probe.anchorZ();
			final float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
			if (len < 1e-3f) return;
			dirX[bucket] += dx / len * delivered;
			dirY[bucket] += dy / len * delivered;
			dirZ[bucket] += dz / len * delivered;
		}

		void commit(final long tick) {
			if (rays == 0) {
				probe.abortProbeRound(); // every ray of this round was cut from the batch
				return;
			}
			final float[] outEnergy = new float[CellProbe.BUCKETS];
			final float[] outReflectivity = new float[CellProbe.BUCKETS];
			final float[] outDistance = new float[CellProbe.BUCKETS];
			final float[] outDirX = new float[CellProbe.BUCKETS];
			final float[] outDirY = new float[CellProbe.BUCKETS];
			final float[] outDirZ = new float[CellProbe.BUCKETS];
			for (int bucket = 0; bucket < CellProbe.BUCKETS; bucket++) {
				outEnergy[bucket] = energy[bucket] / rays;
				if (energy[bucket] <= 0.0f) continue;
				outReflectivity[bucket] = reflectivityE[bucket] / energy[bucket];
				outDistance[bucket] = distanceE[bucket] / energy[bucket];
				outDirX[bucket] = dirX[bucket] / energy[bucket];
				outDirY[bucket] = dirY[bucket] / energy[bucket];
				outDirZ[bucket] = dirZ[bucket] / energy[bucket];
			}
			final float escapeLen = (float) Math.sqrt(escapeX * escapeX + escapeY * escapeY + escapeZ * escapeZ);
			final float inv = escapeLen > 1e-4f ? 1.0f / escapeLen : 0.0f;
			probe.commitProbeRound(new CellProbe.Stats(outEnergy, outReflectivity, outDistance,
					outDirX, outDirY, outDirZ,
					(float) escapes / rays, escapeX * inv, escapeY * inv, escapeZ * inv), tick);
		}
	}

	/** One edge's hero-ray fan: keeps the best ray and its portal. */
	private static final class EdgeBake {
		final Edge edge;
		final int rays;
		final float[] portals;
		float bestHigh = -1.0f;
		float bestLow;
		int bestRay;

		EdgeBake(final Edge edge, final int rays) {
			this.edge = edge;
			this.rays = rays;
			portals = new float[rays * 3];
		}

		void report(final int ray, final float high, final float low) {
			if (high <= bestHigh) return;
			bestHigh = high;
			bestLow = low;
			bestRay = ray;
		}

		void commit() {
			if (bestHigh < 0.0f) {
				edge.abortBake(); // every ray of this fan was cut from the batch
				return;
			}
			edge.commitBake(bestHigh, bestLow,
					portals[bestRay * 3], portals[bestRay * 3 + 1], portals[bestRay * 3 + 2]);
		}
	}

	/** One source's path polyline: transmissions multiply across segments. */
	private static final class PathValidation {
		final Object target;
		float high = 1.0f;
		float low = 1.0f;

		PathValidation(final Object target) {
			this.target = target;
		}
	}
}
