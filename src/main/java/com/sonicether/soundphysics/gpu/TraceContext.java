package com.sonicether.soundphysics.gpu;

import com.sonicether.soundphysics.SoundPhysics;
import me.eigenraven.lwjgl3ify.api.Lwjgl3Aware;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjglx.opengl.Display;
import org.lwjglx.opengl.Drawable;
import org.lwjglx.opengl.SharedDrawable;

/**
 * The audio worker's GL context. lwjgl3ify runs the game on
 * SDL3/GLFW behind its lwjglx compatibility layer and does not expose the
 * windowing API to mods (org.lwjgl.glfw is not even on the runtime
 * classpath), so a literal hidden GLFW window is impossible here. Instead the
 * worker gets an {@code org.lwjglx.opengl.SharedDrawable} — a context created
 * by the game's own backend, sharing the object namespace with the main
 * context but current only on the worker thread. The org.lwjglx references
 * are deliberate and explicit (that layer owns context creation); the real
 * LWJGL3 GL API stays under {@code @Lwjgl3Aware}.
 *
 * <p>Creation runs on the main thread at init; the GL 4.3 probe happens on
 * the worker after makeCurrent (a shared context has the main context's
 * version). Failing either leaves vanilla sound untouched — no CPU fallback.
 */
@Lwjgl3Aware
public final class TraceContext {

	private final Drawable drawable;
	private GLCapabilities capabilities;

	private TraceContext(final Drawable drawable) {
		this.drawable = drawable;
	}

	/** Main thread only (the game context must exist). Null on failure. */
	public static TraceContext create() {
		if (!Display.isCreated()) {
			SoundPhysics.logError("Display not created yet; acoustic tracer disabled.");
			return null;
		}
		try {
			return new TraceContext(new SharedDrawable(Display.getDrawable()));
		} catch (final Exception e) {
			SoundPhysics.logError("Shared GL context unavailable (" + e + "); acoustic tracer disabled, vanilla sound untouched.");
			return null;
		}
	}

	/** Worker thread, once, before any GL call. False → run vanilla sound. */
	public boolean makeCurrentOnWorker() {
		try {
			drawable.makeCurrent();
		} catch (final Exception e) {
			SoundPhysics.logError("Could not make the tracer context current: " + e);
			return false;
		}
		capabilities = GL.createCapabilities();
		if (!capabilities.OpenGL43) {
			SoundPhysics.logError("OpenGL 4.3 not available (compute shaders required); acoustic tracer disabled, vanilla sound untouched.");
			return false;
		}
		return true;
	}

	/**
	 * Persistent mapping needs ARB_buffer_storage (core in 4.4); universally
	 * present on 4.3-era drivers but probed anyway so we fail into the plain
	 * readback path instead of crashing.
	 */
	public boolean supportsBufferStorage() {
		return capabilities.OpenGL44 || capabilities.GL_ARB_buffer_storage;
	}
}
