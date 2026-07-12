package com.sonicether.soundphysics.instrumentation;

/**
 * Engine instrumentation, built from day one: wall-bleed through a bad edge
 * and a stale probe both present to a playtester as "reverb sounds a bit
 * off" — these numbers make them distinguishable.
 *
 * <p>All fields are written by the audio worker (single writer); the F3
 * overlay and the periodic logger read them from the client thread as plain
 * volatile snapshots.
 */
public final class Stats {

	public static final Stats INSTANCE = new Stats();

	// --- Batch composition breakdown: rays actually spent per stage vs budget.
	public volatile int raysDirect;
	public volatile int raysEdge;
	public volatile int raysProbe;
	public volatile int raysPath;
	public volatile int rayBudget;

	// --- Field shape: what's baked and what the listener can reach.
	public volatile int cellsStored;
	public volatile int edgesStored;
	public volatile int fieldNodes;
	public volatile int probeRounds; // committed this tick
	public volatile int edgeBakes; // committed this tick
	public volatile int pathValidations; // sources validated this tick

	// --- Listener-cell escape statistics: how "outside" the space reads.
	public volatile float escapeRatio;

	// --- Worker health.
	public volatile int playingSources;
	public volatile int voiceSpeakers;
	public volatile long workerTick;
	public volatile float workerLoadPct;

	private Stats() {
	}
}
