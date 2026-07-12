package com.sonicether.soundphysics.gpu;

import com.sonicether.soundphysics.SoundPhysics;
import me.eigenraven.lwjgl3ify.api.Lwjgl3Aware;
import org.lwjgl.opengl.GL44;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;

import static org.lwjgl.opengl.GL43.*;

/**
 * The compute-shader ray pipeline: batch SSBO in, one
 * dispatch, results SSBO out, {@code glFenceSync} + persistent-mapped result
 * buffer (plain readback fallback when ARB_buffer_storage is absent). Each
 * chain ray writes up to {@link #RESULTS_PER_RAY} bounce-prefix results.
 * Worker thread only, context current.
 */
@Lwjgl3Aware
public final class TracePipeline {

	public static final int MAX_RAYS = 8192;
	public static final int RESULTS_PER_RAY = 4;
	public static final int RESULT_FLOATS = 8;
	public static final int FLAG_VALID = 1;

	private static final long RESULT_BYTES = MAX_RAYS * RESULTS_PER_RAY * RESULT_FLOATS * 4L;

	private int program;
	private int rayBuffer;
	private int resultBuffer;
	private long fence;
	private boolean persistent;

	private ByteBuffer resultStaging; // fallback readback target
	private FloatBuffer resultFloats;
	private IntBuffer resultInts;

	private int uniformWindowMin;
	private int uniformRayCount;

	public boolean init(final TraceContext context) {
		program = compileProgram();
		if (program == 0) return false;

		uniformWindowMin = glGetUniformLocation(program, "windowMin");
		uniformRayCount = glGetUniformLocation(program, "rayCount");

		rayBuffer = glGenBuffers();
		glBindBuffer(GL_SHADER_STORAGE_BUFFER, rayBuffer);
		glBufferData(GL_SHADER_STORAGE_BUFFER, MAX_RAYS * Batch.RAY_FLOATS * 4L, GL_DYNAMIC_DRAW);

		resultBuffer = glGenBuffers();
		glBindBuffer(GL_SHADER_STORAGE_BUFFER, resultBuffer);
		persistent = context.supportsBufferStorage();
		final ByteBuffer resultView;
		if (persistent) {
			GL44.glBufferStorage(GL_SHADER_STORAGE_BUFFER, RESULT_BYTES,
					GL_MAP_READ_BIT | GL44.GL_MAP_PERSISTENT_BIT | GL44.GL_MAP_COHERENT_BIT);
			resultView = glMapBufferRange(GL_SHADER_STORAGE_BUFFER, 0, RESULT_BYTES,
					GL_MAP_READ_BIT | GL44.GL_MAP_PERSISTENT_BIT | GL44.GL_MAP_COHERENT_BIT);
			if (resultView == null) {
				SoundPhysics.logError("Persistent mapping of the result buffer failed.");
				return false;
			}
		} else {
			glBufferData(GL_SHADER_STORAGE_BUFFER, RESULT_BYTES, GL_DYNAMIC_READ);
			resultStaging = ByteBuffer.allocateDirect((int) RESULT_BYTES).order(ByteOrder.nativeOrder());
			resultView = resultStaging;
		}
		resultFloats = resultView.asFloatBuffer();
		resultInts = resultView.asIntBuffer();
		return true;
	}

	public void dispatch(final Batch batch, final VoxelStore store) {
		glUseProgram(program);
		store.bind();

		glBindBuffer(GL_SHADER_STORAGE_BUFFER, rayBuffer);
		glBufferSubData(GL_SHADER_STORAGE_BUFFER, 0, batch.stagedBytes());
		glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, rayBuffer);
		glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, resultBuffer);

		glUniform3i(uniformWindowMin, store.windowMinBlockX(), 0, store.windowMinBlockZ());
		glUniform1i(uniformRayCount, batch.size());
		glDispatchCompute((batch.size() + 63) / 64, 1, 1);

		glMemoryBarrier(persistent ? GL44.GL_CLIENT_MAPPED_BUFFER_BARRIER_BIT : GL_BUFFER_UPDATE_BARRIER_BIT);
		fence = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
	}

	/** Block on the fence (worker's own thread, nobody else affected). */
	public boolean awaitResults(final long timeoutNanos) {
		final int status = glClientWaitSync(fence, GL_SYNC_FLUSH_COMMANDS_BIT, timeoutNanos);
		glDeleteSync(fence);
		if (status != GL_ALREADY_SIGNALED && status != GL_CONDITION_SATISFIED) return false;
		if (persistent) return true;

		glBindBuffer(GL_SHADER_STORAGE_BUFFER, resultBuffer);
		resultStaging.clear();
		glGetBufferSubData(GL_SHADER_STORAGE_BUFFER, 0, resultStaging);
		return true;
	}

	// --- Result views: base index = (ray × 4 + prefix) × 8 floats ------------

	public int resultBase(final int ray, final int prefix) {
		return (ray * RESULTS_PER_RAY + prefix) * RESULT_FLOATS;
	}

	public float resultFloat(final int index) {
		return resultFloats.get(index);
	}

	public int resultFlags(final int base) {
		return resultInts.get(base + 7);
	}

	private int compileProgram() {
		final String source;
		try (InputStream in = TracePipeline.class.getResourceAsStream("/assets/soundphysics/shaders/trace.comp")) {
			if (in == null) {
				SoundPhysics.logError("trace.comp missing from the jar.");
				return 0;
			}
			source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (final IOException e) {
			SoundPhysics.logError("Failed reading trace.comp: " + e);
			return 0;
		}

		final int shader = glCreateShader(GL_COMPUTE_SHADER);
		glShaderSource(shader, source);
		glCompileShader(shader);
		if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
			SoundPhysics.logError("trace.comp compile failed:\n" + glGetShaderInfoLog(shader));
			glDeleteShader(shader);
			return 0;
		}

		final int linked = glCreateProgram();
		glAttachShader(linked, shader);
		glLinkProgram(linked);
		glDeleteShader(shader);
		if (glGetProgrami(linked, GL_LINK_STATUS) == GL_FALSE) {
			SoundPhysics.logError("trace.comp link failed:\n" + glGetProgramInfoLog(linked));
			glDeleteProgram(linked);
			return 0;
		}
		return linked;
	}
}
