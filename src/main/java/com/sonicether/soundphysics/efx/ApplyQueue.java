package com.sonicether.soundphysics.efx;

import com.sonicether.soundphysics.SoundEnvironment;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Decouples the estimator from AL: the audio worker pushes
 * changed params here after re-estimating playing sources, and drains the
 * queue with AL calls at the end of its loop tick (the game AL context is
 * process-global, so the worker may touch it; the play path still applies
 * directly at play time).
 */
public final class ApplyQueue {

	private record Pending(int sourceId, SoundEnvironment env) {
	}

	private final ConcurrentLinkedQueue<Pending> queue = new ConcurrentLinkedQueue<>();

	public void push(final int sourceId, final SoundEnvironment env) {
		queue.add(new Pending(sourceId, env));
	}

	public void drainTo(final EfxPipeline pipeline) {
		if (!pipeline.isInitialized()) {
			queue.clear();
			return;
		}
		for (Pending pending; (pending = queue.poll()) != null; ) {
			pipeline.apply(pending.sourceId(), pending.env());
		}
	}
}
