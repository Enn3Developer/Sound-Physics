package com.sonicether.soundphysics;

import me.eigenraven.lwjgl3ify.api.Lwjgl3Aware;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.SoundCategory;
import net.minecraft.entity.Entity;
import org.lwjgl.openal.AL10;
import paulscode.sound.SoundBuffer;
import paulscode.sound.SoundSystemConfig;

import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.regex.Pattern;

// Pure client-side static facade: everything the mixins and the coremod
// injector call lands here and is routed into SoundPhysicsEngine. No tracing
// happens on any of these paths — playing a sound is a cache read.
//
// The modid/modName/version/mcVersion constants stay here because the gradle
// token replacement (replaceGradleTokenInFile) rewrites @MODID@/@MODNAME@/
// @VERSION@ only in this file; SoundPhysicsMod's @Mod inlines them at compile
// time.
//
// Escape lwjgl3ify's org.lwjgl -> org.lwjglx redirect: this class calls the
// real LWJGL3 AL API directly (checkErrorLog).
@Lwjgl3Aware
public class SoundPhysics {

	public static final String modid = "@MODID@";
	public static final String modName = "@MODNAME@";
	public static final String version = "@VERSION@";
	public static final String mcVersion = "1.7.10";

	private static final Pattern rainPattern = Pattern.compile(".*rain.*");
	private static final Pattern stepPattern = Pattern.compile(".*step.*");
	private static final Pattern uiPattern = Pattern.compile(".*\\/ui\\/.*");
	private static final Pattern clickPattern = Pattern.compile(".*random.click.*");
	private static final Pattern noteBlockPattern = Pattern.compile(".*note/.*");

	private static final String logPrefix = "[SOUND PHYSICS]";

	private static SoundCategory lastSoundCategory;
	private static String lastSoundName;

	// Read by MixinSoundSystem / MixinSoundManager / the coremod injector.
	public static int attenuationModel = SoundSystemConfig.ATTENUATION_ROLLOFF;
	public static float globalRolloffFactor = Config.rolloffFactor;
	public static float globalVolumeMultiplier = 1.0f; // raised to 4 when the EFX bus is live
	public static float globalReverbMultiplier = 0.7f * Config.globalReverbGain;
	public static double soundDistanceAllowance = Config.soundDistanceAllowance;

	/**
	 * CALLED BY Mixin INJECTED CODE! Runs on the Sound Library Loader thread at
	 * every sound-system (re)start. Never allowed to throw — an escaped
	 * exception here kills Minecraft's sound loading thread and with it ALL
	 * game audio.
	 */
	public static void init() {
		try {
			SoundPhysicsEngine.bootstrap();
		} catch (final Exception e) {
			logError("Engine bootstrap failed; vanilla sound untouched: " + e);
			e.printStackTrace();
		}
	}

	public static void applyConfigChanges() {
		globalRolloffFactor = Config.rolloffFactor;
		globalReverbMultiplier = 0.7f * Config.globalReverbGain;
		soundDistanceAllowance = Config.soundDistanceAllowance;

		final SoundPhysicsEngine engine = SoundPhysicsEngine.instance();
		if (engine != null) engine.onConfigChanged();
	}

	/**
	 * CALLED BY Mixin INJECTED CODE!
	 */
	public static void setLastSoundCategory(final SoundCategory sc) {
		lastSoundCategory = sc;
	}

	/**
	 * CALLED BY Mixin INJECTED CODE!
	 */
	public static void setLastSoundName(final String soundName) {
		lastSoundName = soundName;
	}

	/**
	 * CALLED BY Mixin INJECTED CODE!
	 */
	// For sounds that get played normally
	public static void onPlaySound(final float posX, final float posY, final float posZ, final int sourceID) {
		onPlaySound(posX, posY, posZ, sourceID, lastSoundCategory, lastSoundName);
	}

	/**
	 * CALLED BY Mixin INJECTED CODE!
	 */
	// For sounds played using OpenAL directly, bypassing the minecraft sound system
	public static void onPlaySoundAL(final float posX, final float posY, final float posZ, final int sourceID) {
		onPlaySound(posX, posY, posZ, sourceID, SoundCategory.BLOCKS, "null");
	}

	/**
	 * CALLED BY Mixin INJECTED CODE!
	 */
	public static void onPlaySound(final float posX, final float posY, final float posZ, final int sourceID,
			SoundCategory soundCat, final String soundName) {
		final SoundPhysicsEngine engine = SoundPhysicsEngine.instance();
		if (engine == null) return; // vanilla sound

		if (Config.noteBlockEnable && soundCat == SoundCategory.RECORDS && soundName != null
				&& noteBlockPattern.matcher(soundName).matches()) {
			soundCat = SoundCategory.BLOCKS;
		}

		// Non-world sounds get an explicit passthrough: AL sources are recycled,
		// so the previous sound's filters must not linger on this source.
		final Minecraft mc = Minecraft.getMinecraft();
		final boolean skip = mc.thePlayer == null || mc.theWorld == null || posY <= 0
				|| soundCat == SoundCategory.RECORDS || soundCat == SoundCategory.MUSIC
				|| (soundName != null && rainPattern.matcher(soundName).matches());
		if (skip) {
			engine.applyPassthrough(sourceID);
			return;
		}

		engine.onPlaySound(posX, posY, posZ, sourceID);
	}

	/**
	 * CALLED BY Mixin INJECTED CODE!
	 */
	public static SoundBuffer onLoadSound(final SoundBuffer buff, final String filename) {
		if (buff == null || buff.audioFormat.getChannels() == 1 || !Config.autoSteroDownmix) return buff;
		final Minecraft mc = Minecraft.getMinecraft();
		if (mc.thePlayer == null | mc.theWorld == null | lastSoundCategory == SoundCategory.RECORDS
				| lastSoundCategory == SoundCategory.MUSIC | uiPattern.matcher(filename).matches()
				| clickPattern.matcher(filename).matches()) {
			if (Config.autoSteroDownmixLogging) log("Not converting sound '" + filename + "'(" + buff.audioFormat + ")");
			return buff;
		}
		final AudioFormat originalFormat = buff.audioFormat;
		final int bits = originalFormat.getSampleSizeInBits();
		final boolean bigendian = originalFormat.isBigEndian();
		final AudioFormat monoFormat = new AudioFormat(originalFormat.getEncoding(), originalFormat.getSampleRate(),
				bits, 1, originalFormat.getFrameSize(), originalFormat.getFrameRate(), bigendian);
		if (Config.autoSteroDownmixLogging) {
			log("Converting sound '" + filename + "'(" + originalFormat + ") to mono (" + monoFormat + ")");
		}

		final ByteBuffer bb = ByteBuffer.wrap(buff.audioData, 0, buff.audioData.length);
		bb.order(bigendian ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
		if (bits == 8) {
			for (int i = 0; i < buff.audioData.length; i += 2) {
				bb.put(i / 2, (byte) ((bb.get(i) + bb.get(i + 1)) / 2));
			}
		} else if (bits == 16) {
			for (int i = 0; i < buff.audioData.length; i += 4) {
				bb.putShort(i / 2, (short) ((bb.getShort(i) + bb.getShort(i + 2)) / 2));
			}
		}
		buff.audioFormat = monoFormat;
		buff.trimData(buff.audioData.length / 2);
		return buff;
	}

	/**
	 * CALLED BY Mixin INJECTED CODE!
	 */
	public static double calculateEntitySoundOffset(final Entity entity, final String name) {
		if (name != null && stepPattern.matcher(name).matches()) return 0;
		return entity.getEyeHeight();
	}

	/**
	 * CALLED BY Mixin INJECTED CODE! Client-world block change → CPU section
	 * cache dirty mark.
	 */
	public static void onBlockChanged(final int x, final int y, final int z) {
		final SoundPhysicsEngine engine = SoundPhysicsEngine.instance();
		if (engine != null) engine.onBlockChanged(x, y, z);
	}

	/**
	 * CALLED BY Mixin INJECTED CODE! Full chunk-data packet → whole column dirty.
	 */
	public static void onChunkFilled(final int chunkX, final int chunkZ) {
		final SoundPhysicsEngine engine = SoundPhysicsEngine.instance();
		if (engine != null) engine.onChunkFilled(chunkX, chunkZ);
	}

	public static void log(final String message) {
		System.out.println(logPrefix.concat(" : ").concat(message));
	}

	public static void logError(final String errorMessage) {
		System.out.println(logPrefix.concat(" [ERROR] : ").concat(errorMessage));
	}

	public static boolean checkErrorLog(final String errorMessage) {
		final int error = AL10.alGetError();
		if (error == AL10.AL_NO_ERROR) {
			return false;
		}

		final String errorName = switch (error) {
			case AL10.AL_INVALID_NAME -> "AL_INVALID_NAME";
			case AL10.AL_INVALID_ENUM -> "AL_INVALID_ENUM";
			case AL10.AL_INVALID_VALUE -> "AL_INVALID_VALUE";
			case AL10.AL_INVALID_OPERATION -> "AL_INVALID_OPERATION";
			case AL10.AL_OUT_OF_MEMORY -> "AL_OUT_OF_MEMORY";
			default -> Integer.toString(error);
		};

		logError(errorMessage + " OpenAL error " + errorName);
		return true;
	}
}
