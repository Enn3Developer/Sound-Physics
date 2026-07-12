package com.sonicether.soundphysics;

/**
 * Presentation-layer listener state beyond the bare position the tracer uses:
 * look direction (reverb panning is expressed in listener space), the
 * ears-wet flag (underwater lowpass is presentation, not simulation) and the
 * weather air-absorption factor. Written on the client tick; read by the
 * audio worker, the play path and the voice audio thread — all volatile
 * primitives, torn triples are inaudible.
 */
public final class ListenerState {

	public static volatile float forwardX;
	public static volatile float forwardY;
	public static volatile float forwardZ = 1.0f;

	/** Listener head in water: one lowpass at apply time, keyed on this alone. */
	public static volatile boolean earsWet;

	/** ≥ 1; raises AL air absorption while it snows on the listener. */
	public static volatile float weatherAbsorption = 1.0f;

	private ListenerState() {
	}
}
