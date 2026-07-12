package com.sonicether.soundphysics.efx;

/**
 * Listener-environment reverb dynamics computed by the audio worker each
 * tick: per-bucket reflection pan vectors (listener space) and decay HF
 * ratios (material brightness). The worker applies them to the game pipeline
 * directly; the voice audio thread picks them up here by version, because its
 * AL context is thread-local and the worker must never touch it.
 */
public final class DynamicsState {

	private static final float[] pan = new float[12]; // 4 buckets × xyz
	private static final float[] decayHf = { 0.7f, 0.7f, 0.7f, 0.7f };
	private static volatile int version;

	private DynamicsState() {
	}

	public static synchronized void publish(final float[] pan12, final float[] decayHf4) {
		System.arraycopy(pan12, 0, pan, 0, 12);
		System.arraycopy(decayHf4, 0, decayHf, 0, 4);
		version++;
	}

	/** Copies the current state out; returns the version copied. */
	public static synchronized int copyInto(final float[] pan12, final float[] decayHf4) {
		System.arraycopy(pan, 0, pan12, 0, 12);
		System.arraycopy(decayHf, 0, decayHf4, 0, 4);
		return version;
	}

	public static int version() {
		return version;
	}
}
