package com.sonicether.soundphysics.scheduler;

import com.sonicether.soundphysics.SoundEnvironment;
import com.sonicether.soundphysics.restir.CellKeys;

import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Everything currently emitting sound: game AL sources (registered on the
 * play path, liveness-polled by the worker) and gtnh-voice speakers
 * (positions updated on the client tick, lifecycle-managed by the voice
 * bridge). Speakers are sources like any other: their cells
 * get batch priority while speaking, estimates come from the same
 * store/estimator, no dedicated ray budget.
 */
public final class ActiveSources {

	public static final class GameSource {
		public final int sourceId;
		public volatile float x;
		public volatile float y;
		public volatile float z;
		// Direct occlusion only, no reverb cell (rain: a distributed ambient
		// source — per-droplet reverb is meaningless, muffling through a roof
		// is not).
		public volatile boolean directOnly;
		public volatile float directTransmission = 1.0f;
		public volatile float directTransmissionLow = 1.0f;
		// Speed-of-sound start delay: paused at play, resumed by the worker.
		public volatile long resumeAtNanos;
		// Worker-only.
		SoundEnvironment lastApplied;
		SoundEnvironment smoothed;

		GameSource(final int sourceId) {
			this.sourceId = sourceId;
		}

		public long cellKey() {
			return CellKeys.ofBlock(x, y, z);
		}
	}

	public static final class Speaker {
		public final UUID id;
		public volatile double x;
		public volatile double y;
		public volatile double z;
		public volatile float directTransmission = 1.0f;
		public volatile float directTransmissionLow = 1.0f;
		// Worker-only.
		SoundEnvironment lastApplied;
		SoundEnvironment smoothed;

		Speaker(final UUID id) {
			this.id = id;
		}

		public long cellKey() {
			return CellKeys.ofBlock(x, y, z);
		}
	}

	private final ConcurrentHashMap<Integer, GameSource> game = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<UUID, Speaker> speakers = new ConcurrentHashMap<>();

	/**
	 * Play path: register/refresh a game source at its play position. AL source
	 * ids are recycled by paulscode, so a (re)play resets the per-source
	 * measurements; the next worker tick re-measures.
	 */
	public GameSource onPlay(final int sourceId, final float x, final float y, final float z,
			final boolean directOnly) {
		final GameSource source = game.computeIfAbsent(sourceId, GameSource::new);
		source.x = x;
		source.y = y;
		source.z = z;
		source.directOnly = directOnly;
		source.directTransmission = 1.0f;
		source.directTransmissionLow = 1.0f;
		source.resumeAtNanos = 0L;
		source.lastApplied = null;
		source.smoothed = null;
		return source;
	}

	public void remove(final GameSource source) {
		game.remove(source.sourceId);
	}

	public Collection<GameSource> gameSources() {
		return game.values();
	}

	/** Voice bridge, client tick: positional speakers only. */
	public void updateSpeaker(final UUID id, final double x, final double y, final double z) {
		final Speaker speaker = speakers.computeIfAbsent(id, Speaker::new);
		speaker.x = x;
		speaker.y = y;
		speaker.z = z;
	}

	public void removeSpeaker(final UUID id) {
		speakers.remove(id);
	}

	public Collection<Speaker> speakerSources() {
		return speakers.values();
	}

	public int count() {
		return game.size() + speakers.size();
	}

	public void clear() {
		game.clear();
		speakers.clear();
	}
}
