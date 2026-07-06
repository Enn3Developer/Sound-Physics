package com.sonicether.soundphysics;

/**
 * Immutable result of an environment evaluation: the four reverb send
 * gain/cutoff pairs, the direct path filter values and the air absorption
 * factor that get applied to an AL source by {@link EfxPipeline#apply}.
 */
public final class SoundEnvironment {

	private static final SoundEnvironment PASSTHROUGH = new SoundEnvironment(
			0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f);

	public final float sendGain0;
	public final float sendGain1;
	public final float sendGain2;
	public final float sendGain3;
	public final float sendCutoff0;
	public final float sendCutoff1;
	public final float sendCutoff2;
	public final float sendCutoff3;
	public final float directCutoff;
	public final float directGain;
	public final float airAbsorptionFactor;

	public SoundEnvironment(final float sendGain0, final float sendGain1, final float sendGain2,
			final float sendGain3, final float sendCutoff0, final float sendCutoff1, final float sendCutoff2,
			final float sendCutoff3, final float directCutoff, final float directGain,
			final float airAbsorptionFactor) {
		this.sendGain0 = sendGain0;
		this.sendGain1 = sendGain1;
		this.sendGain2 = sendGain2;
		this.sendGain3 = sendGain3;
		this.sendCutoff0 = sendCutoff0;
		this.sendCutoff1 = sendCutoff1;
		this.sendCutoff2 = sendCutoff2;
		this.sendCutoff3 = sendCutoff3;
		this.directCutoff = directCutoff;
		this.directGain = directGain;
		this.airAbsorptionFactor = airAbsorptionFactor;
	}

	/**
	 * Neutral environment: no reverb sends, unfiltered direct path. Used for
	 * sounds that skip evaluation (menu clicks, records, music, rain) and as
	 * the fallback when evaluation fails.
	 */
	public static SoundEnvironment passthrough() {
		return PASSTHROUGH;
	}
}
