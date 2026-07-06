package com.sonicether.soundphysics;

import me.eigenraven.lwjgl3ify.api.Lwjgl3Aware;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.SoundCategory;
import net.minecraft.entity.Entity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL10;
import paulscode.sound.SoundBuffer;
import paulscode.sound.SoundSystemConfig;

import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.ListIterator;
import java.util.regex.Pattern;

// Pure client-side logic class. It no longer carries @Mod / @Mod.EventHandler /
// @SubscribeEvent — that lives on the server-safe entry class SoundPhysicsMod, whose
// ClientProxy drives the preInit/init/config work below. This class is class-loaded
// only on the client (via the early mixins and the ClientProxy), so it is free to
// reference client-only, paulscode and org.lwjgl types.
//
// The modid/modName/version/mcVersion constants stay here because the gradle token
// replacement (replaceGradleTokenInFile) rewrites @MODID@/@MODNAME@/@VERSION@ only in
// this file; SoundPhysicsMod's @Mod inlines them at compile time.
//
// Escape lwjgl3ify's org.lwjgl -> org.lwjglx redirect: this class calls the real LWJGL3 AL API directly.
// The annotation is per class FILE, so the AL-touching nested classes below carry their own copies.
@Lwjgl3Aware
public class SoundPhysics {

	public static final String modid = "@MODID@";
	public static final String modName = "@MODNAME@";
	public static final String version = "@VERSION@";
	public static final String mcVersion = "1.7.10";

	private static final Pattern rainPattern = Pattern.compile(".*rain.*");
	private static final Pattern stepPattern = Pattern.compile(".*step.*");
	private static final Pattern blockPattern = Pattern.compile(".*block.*");
	private static final Pattern uiPattern = Pattern.compile(".*\\/ui\\/.*");
	private static final Pattern clickPattern = Pattern.compile(".*random.click.*");
	private static final Pattern noteBlockPattern = Pattern.compile(".*note/.*");

	private static final String logPrefix = "[SOUND PHYSICS]";
	// EFX pipeline for the Minecraft/paulscode AL context; AL objects are
	// created in init(), on the sound system thread.
	private static final EfxPipeline efxPipeline = new EfxPipeline();

	private static Minecraft mc;

	private static SoundCategory lastSoundCategory;
	private static String lastSoundName;

	private static ProcThread proc_thread;
	private static volatile boolean thread_alive;
	private static volatile boolean thread_signal_death;
	private static volatile List<Source> source_list;
	public static int attenuationModel = SoundSystemConfig.ATTENUATION_ROLLOFF;
	public static float globalRolloffFactor = Config.rolloffFactor;
	public static float globalVolumeMultiplier = 4.0f;
	public static float globalReverbMultiplier = 0.7f * Config.globalReverbGain;
	public static double soundDistanceAllowance = Config.soundDistanceAllowance;

	/**
	 * CALLED BY Mixin INJECTED CODE!
	 */
	public static void init() {
		if (efxPipeline.init().isInitialized()) {
			applyConfigChanges();
		}
		mc = Minecraft.getMinecraft();
		setupThread();
	}

	@Lwjgl3Aware
	public static class Source {
		public int sourceID;
		public float posX;
		public float posY;
		public float posZ;
		public SoundCategory category;
		public String name;
		public int frequency;
		public int size;
		public int bufferID;

		public Source(int sid,float px,float py,float pz,SoundCategory cat,String n) {
			this.sourceID = sid;
			this.posX = px;
			this.posY = py;
			this.posZ = pz;
			this.category = cat;
			this.name = n;
			bufferID = AL10.alGetSourcei(sid, AL10.AL_BUFFER);
			size = AL10.alGetBufferi(bufferID, AL10.AL_SIZE);
			frequency = AL10.alGetBufferi(bufferID, AL10.AL_FREQUENCY);
		}
	}

	@Lwjgl3Aware
	public static class ProcThread extends Thread {
		@Override
		public synchronized void run() {
			while (thread_alive) {
				while (!Config.dynamicEnvironementEvalutaion) {
					try {
						Thread.sleep(1000);
					} catch (Exception e) {
						logError(String.valueOf(e));
					}
				}
				synchronized (source_list) {
					//log("Updating env " + String.valueOf(source_list.size()));
					ListIterator<Source> iter = source_list.listIterator();
					while (iter.hasNext()) {
						Source source = iter.next();
						//log("Updating sound '" + source.name + "' SourceID:" + String.valueOf(source.sourceID));
						//boolean pl = sndHandler.isSoundPlaying(source.sound);
						//FloatBuffer pos = BufferUtils.createFloatBuffer(3);
						//AL10.alGetSource(source.sourceID,AL10.AL_POSITION,pos);
						//To try ^
						int state = AL10.alGetSourcei(source.sourceID, AL10.AL_SOURCE_STATE);
						//int byteoff = AL10.alGetSourcei(source.sourceID, AL11.AL_BYTE_OFFSET);
						//boolean finished = source.size == byteoff;
						if (state == AL10.AL_PLAYING) {
							FloatBuffer pos = BufferUtils.createFloatBuffer(3);
							AL10.alGetSourcefv(source.sourceID,AL10.AL_POSITION,pos);
							source.posX = pos.get(0);
							source.posY = pos.get(1);
							source.posZ = pos.get(2);
							evaluateEnvironment(source.sourceID,source.posX,source.posY,source.posZ,source.category,source.name);
						} else /*if (state == AL10.AL_STOPPED)*/ {
							iter.remove();
						}
					}
				}
				try {
					Thread.sleep(1000/Config.dynamicEnvironementEvalutaionFrequency);
				} catch (Exception e) {
					logError(String.valueOf(e));
				}
			}
			thread_signal_death = true;
		}
	}

	public static boolean source_check(Source s) {
		synchronized (source_list) {
			for (Source sn : source_list) {
				if (sn.sourceID == s.sourceID && sn.bufferID == s.bufferID &&
						sn.posX == s.posX && sn.posY == s.posY && sn.posZ == s.posZ) {
					return true;
				}
			}
		}
		return false;
	}

	/*@Mod.EventBusSubscriber
	public static class DebugDisplayEventHandler {
		@SubscribeEvent
		public static void onDebugOverlay(RenderGameOverlayEvent.Text event)
		{
			if(mc.gameSettings.showDebugInfo && Config.dynamicEnvironementEvalutaion && Config.debugInfoShow) {
				event.getLeft().add("");
				event.getLeft().add("[SoundPhysics] "+String.valueOf(source_list.size())+" Sources");
				event.getLeft().add("[SoundPhysics] Source list :");
				synchronized (source_list) {
					ListIterator<Source> iter = source_list.listIterator();
					while (iter.hasNext())  {
						Source s = iter.next();
						Vec3d tmp = new Vec3d(s.posX,s.posY,s.posZ);
						event.getLeft().add(String.valueOf(s.sourceID)+"-"+s.category.toString()+"-"+s.name+"-"+tmp.toString());
						/*int buffq = AL10.alGetSourcei(s.sourceID, AL10.AL_BUFFERS_QUEUED);
						int buffp = AL10.alGetSourcei(s.sourceID, AL10.AL_BUFFERS_PROCESSED);
						int sampoff = AL10.alGetSourcei(s.sourceID, AL11.AL_SAMPLE_OFFSET);
						int byteoff = AL10.alGetSourcei(s.sourceID, AL11.AL_BYTE_OFFSET);
						String k = "";
						if (sampoff!=0) {
							//k = String.valueOf(sampoff)+"/"+String.valueOf((byteoff/sampoff)*size)+" ";
							k = String.valueOf((float)sampoff/(float)s.frequency)+"/"+String.valueOf((float)((byteoff/sampoff)*s.size)/(float)s.frequency)+" ";
						} else {
							k = "0/? ";
						}
						event.getLeft().add(k+String.valueOf(buffp)+"/"+String.valueOf(buffq)+" "+String.valueOf(s.bufferID));
						event.getLeft().add("----");
					}
				}
			}
		}
	}*/

	private static synchronized void setupThread() {
		if (source_list == null) source_list = Collections.synchronizedList(new ArrayList<>());
		else source_list.clear();

		/*if (proc_thread != null) {
			thread_signal_death = false;
			thread_alive = false;
			while (!thread_signal_death);
		}*/
		if (proc_thread == null) {
			proc_thread = new ProcThread();
			thread_alive = true;
			proc_thread.start();
		}
	}

	public static void applyConfigChanges() {
		globalRolloffFactor = Config.rolloffFactor;
		globalReverbMultiplier = 0.7f * Config.globalReverbGain;
		soundDistanceAllowance = Config.soundDistanceAllowance;

		// Set the global reverb parameters and apply them to the effects and
		// effect slots (no-op until the pipeline is initialized)
		efxPipeline.applyReverbPresets();
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
	// For sounds that get played using OpenAL directly or just not using the minecraft sound system
	public static void onPlaySoundAL(final float posX, final float posY, final float posZ, final int sourceID) {
		onPlaySound(posX, posY, posZ, sourceID, SoundCategory.BLOCKS, "null");
	}

	/**
	 * CALLED BY Mixin INJECTED CODE!
	 */
	public static void onPlaySound(final float posX, final float posY, final float posZ, final int sourceID, SoundCategory soundCat, String soundName) {
		//log(String.valueOf(posX)+" "+String.valueOf(posY)+" "+String.valueOf(posZ)+" - "+String.valueOf(sourceID)+" - "+soundCat.toString()+" - "+soundName);
		if (Config.noteBlockEnable && soundCat == SoundCategory.RECORDS && noteBlockPattern.matcher(soundName).matches()) soundCat = SoundCategory.BLOCKS;
		evaluateEnvironment(sourceID, posX, posY, posZ,soundCat,soundName);
		if (!Config.dynamicEnvironementEvalutaion) return;
		if ((mc.thePlayer == null | mc.theWorld == null | posY <= 0 | soundCat == SoundCategory.RECORDS 
		| soundCat == SoundCategory.MUSIC) || (Config.skipRainOcclusionTracing && rainPattern.matcher(soundName).matches())) return;
		Source tmp = new Source(sourceID,posX,posY,posZ,soundCat,soundName);
		if (source_check(tmp)) return;
		source_list.add(tmp);
	}

	/**
	 * CALLED BY Mixin INJECTED CODE!
	 */
	public static SoundBuffer onLoadSound(SoundBuffer buff, String filename) {
		if (buff == null || buff.audioFormat.getChannels() == 1 || !Config.autoSteroDownmix) return buff;
		if (mc.thePlayer == null | mc.theWorld == null | lastSoundCategory == SoundCategory.RECORDS 
		| lastSoundCategory == SoundCategory.MUSIC | uiPattern.matcher(filename).matches() | clickPattern.matcher(filename).matches()) {
			if (Config.autoSteroDownmixLogging) log("Not converting sound '"+filename+"'("+buff.audioFormat.toString()+")");
			return buff;
		}
		AudioFormat orignalformat = buff.audioFormat;
		int bits = orignalformat.getSampleSizeInBits();
		boolean bigendian = orignalformat.isBigEndian();
		AudioFormat monoformat = new AudioFormat(orignalformat.getEncoding(), orignalformat.getSampleRate(), bits,
												1, orignalformat.getFrameSize(), orignalformat.getFrameRate(), bigendian);
		if (Config.autoSteroDownmixLogging) log("Converting sound '"+filename+"'("+ orignalformat +") to mono ("+ monoformat +")");

		ByteBuffer bb = ByteBuffer.wrap(buff.audioData,0,buff.audioData.length);
		bb.order(bigendian ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
		if (bits == 8) {
			for (int i = 0; i < buff.audioData.length; i+=2) {
				bb.put(i/2,(byte)((bb.get(i)+bb.get(i+1))/2));
			}
		} else if (bits == 16) {
			for (int i = 0; i < buff.audioData.length; i+=4) {
				bb.putShort((i/2),(short)((bb.getShort(i)+bb.getShort(i+2))/2));
			}
		}
		buff.audioFormat = monoformat;
		buff.trimData(buff.audioData.length/2);
		return buff;
	}

	/**
	 * CALLED BY Mixin INJECTED CODE!
	 */
	public static double calculateEntitySoundOffset(final Entity entity, final String name) {
		if (name != null) {
			if (stepPattern.matcher(name).matches()) {
				return 0;
			}
		}
		return entity.getEyeHeight();
	}

	// Unused
	private static int isSnowingAt(Vec3 position)
	{
		return isSnowingAt(mc.theWorld, position, true);
	}

	// Copy of isRainingAt (1.12.2)
	private static int isSnowingAt(final World world, final Vec3 position, final boolean check_rain)
	{
		if (check_rain && !world.isRaining()) return 0;
		if (!world.canBlockSeeTheSky((int)position.xCoord,(int)position.yCoord,(int)position.zCoord)) return 0;
		if (world.getPrecipitationHeight((int)position.xCoord,(int)position.zCoord) > position.yCoord) return 0;

		/*boolean cansnow = mc.theWorld.canSnowAt(position, false);
		if (mc.theWorld.getBiome(position).getEnableSnow() && cansnow) return true;
		else if (cansnow) return true;
		else return false;*/
							//canSnowAt() but the name isn't there
		return (world.func_147478_e((int)position.xCoord,(int)position.yCoord,(int)position.zCoord, false) |
			world.getBiomeGenForCoords((int)position.xCoord,(int)position.zCoord).getEnableSnow()) ? 1 : 0;
	}

	private static float getBlockReflectivity(final Block block) {
		final Block.SoundType soundType = block.stepSound;
		final Material blockMaterial = block.getMaterial();

		float reflectivity = 0.5f;

		if (soundType == Block.soundTypeStone || soundType == Block.soundTypePiston) {
			reflectivity = Config.stoneReflectivity;
		} else if (soundType == Block.soundTypeWood) {
			reflectivity = Config.woodReflectivity;
		} else if (soundType == Block.soundTypeGravel || soundType == Block.soundTypeGrass) {
			if (blockMaterial == Material.plants || blockMaterial == Material.vine
				|| blockMaterial == Material.grass || blockMaterial == Material.leaves) reflectivity = Config.plantReflectivity;
			else reflectivity = Config.groundReflectivity;
		} else if (soundType == Block.soundTypeMetal) {
			reflectivity = Config.metalReflectivity;
		} else if (soundType == Block.soundTypeGlass) {
			reflectivity = Config.glassReflectivity;
		} else if (soundType == Block.soundTypeCloth) {
			reflectivity = Config.clothReflectivity;
		} else if (soundType == Block.soundTypeSand) {
			reflectivity = Config.sandReflectivity;
		} else if (soundType == Block.soundTypeSnow) {
			reflectivity = Config.snowReflectivity;
		} else if (soundType == Block.soundTypeLadder) {
			reflectivity = Config.woodReflectivity;
		} else if (soundType == Block.soundTypeAnvil) {
			reflectivity = Config.metalReflectivity;
		}

		reflectivity *= Config.globalBlockReflectance;

		return reflectivity;
	}

	/*private static EnumFacing getFacingFromSide(final int side) {
		switch (side) {
			case 0: return EnumFacing.DOWN;
			case 1: return EnumFacing.UP;
			case 2: return EnumFacing.EAST;
			case 3: return EnumFacing.WEST;
			case 4: return EnumFacing.NORTH;
			case 5: return EnumFacing.SOUTH;
			default: return EnumFacing.UP;
		}
	}*/

	private static Vec3 getNormalFromFacing(final int sideHit) {
		EnumFacing facing = EnumFacing.getFront(sideHit);//getFacingFromSide(sideHit);
		return Vec3.createVectorHelper(facing.getFrontOffsetX(),facing.getFrontOffsetY(),facing.getFrontOffsetZ());
	}

	private static Vec3 reflect(final Vec3 dir, final Vec3 normal) {
		final double dot2 = dir.dotProduct(normal) * 2;

		final double x = dir.xCoord - dot2 * normal.xCoord;
		final double y = dir.yCoord - dot2 * normal.yCoord;
		final double z = dir.zCoord - dot2 * normal.zCoord;

		return Vec3.createVectorHelper(x, y, z);
	}

	private static Vec3 offsetSoundByName(final double soundX, final double soundY, final double soundZ,
			final Vec3 playerPos, final String name, final SoundCategory category) {
		double offsetX = 0.0;
		double offsetY = 0.0;
		double offsetZ = 0.0;
		double offsetTowardsPlayer = 0.0;

		double tempNormX;
		double tempNormY;
		double tempNormZ;

		if (soundY % 1.0 < 0.001 || stepPattern.matcher(name).matches()) {
			offsetY = 0.13;
		}

		if (category == SoundCategory.BLOCKS || blockPattern.matcher(name).matches() || 
			!mc.theWorld.isAirBlock((int)Math.floor(soundX),(int)Math.floor(soundY),(int)Math.floor(soundZ))) {
			// The ray will probably hit the block that it's emitting from
			// before
			// escaping. Offset the ray start position towards the player by the
			// diagonal half length of a cube

			tempNormX = playerPos.xCoord - soundX;
			tempNormY = playerPos.yCoord - soundY;
			tempNormZ = playerPos.zCoord - soundZ;
			final double length = Math.sqrt(tempNormX * tempNormX + tempNormY * tempNormY + tempNormZ * tempNormZ);
			tempNormX /= length;
			tempNormY /= length;
			tempNormZ /= length;
			// 0.867 > square root of 0.5^2 * 3
			offsetTowardsPlayer = 0.867;
			offsetX += tempNormX * offsetTowardsPlayer;
			offsetY += tempNormY * offsetTowardsPlayer;
			offsetZ += tempNormZ * offsetTowardsPlayer;
		}

		return Vec3.createVectorHelper(soundX + offsetX, soundY + offsetY, soundZ + offsetZ);
	}

	// Game-specific entry point: guards, sound-name heuristics and source
	// offsetting, then core compute, then apply through the EFX pipeline.
	// Called from both the ProcThread and the paulscode thread; the shared
	// send filters are a pre-existing benign race, kept lock-free on purpose.
	private static void evaluateEnvironment(final int sourceID, final float posX, final float posY, final float posZ, final SoundCategory category, final String name) {
		try {
			if (mc.thePlayer == null | mc.theWorld == null | posY <= 0 | category == SoundCategory.RECORDS
					| category == SoundCategory.MUSIC) {
				// posY <= 0 as a condition has to be there: Ingame
				// menu clicks do have a player and world present
				efxPipeline.apply(sourceID, SoundEnvironment.passthrough());
				return;
			}

			final boolean isRain = rainPattern.matcher(name).matches();

			if (Config.skipRainOcclusionTracing && isRain) {
				efxPipeline.apply(sourceID, SoundEnvironment.passthrough());
				return;
			}

			final Vec3 playerPos = Vec3.createVectorHelper(mc.thePlayer.posX, mc.thePlayer.posY + mc.thePlayer.getEyeHeight(), mc.thePlayer.posZ);
			final Vec3 soundPos = offsetSoundByName(posX, posY, posZ, playerPos, name, category);

			efxPipeline.apply(sourceID, computeEnvironment(mc.theWorld, playerPos, soundPos, isRain));
		} catch (Exception e) {
			logError("Error while evaluation environment:");
			e.printStackTrace();
			efxPipeline.apply(sourceID, SoundEnvironment.passthrough());
		}
	}

	// Voice path entry point (proximity chat reverb + occlusion). AL-free like
	// computeEnvironment: runs on the client tick thread and returns a FULL
	// environment (isRain=false) — occlusion AND the golden-angle reverb ray cast —
	// reusing the exact same math and Config values as game sounds. The full cast is
	// raycast-heavy, so the voice integration budgets how many speakers it calls per
	// tick; keep this AL-free so it never has to touch the voice audio thread.
	public static SoundEnvironment computeVoiceEnvironment(final World world, final Vec3 playerPos, final Vec3 speakerPos) {
		return computeEnvironment(world, playerPos, speakerPos, false);
	}

	// Core environment compute: pure with respect to AL (no AL10/AL11/EFX10
	// calls); world raycasts and Config reads only. This is the method a
	// later voice path can call with a speaker position instead of a
	// sound-event position.
	private static SoundEnvironment computeEnvironment(final World world, final Vec3 playerPos, final Vec3 soundPos, final boolean isRain) {
		final float absorptionCoeff = Config.globalBlockAbsorption * 3.0f;
		final Vec3 normalToPlayer = playerPos.subtract(soundPos).normalize();

		final float airAbsorptionFactor = computeAirAbsorptionFactor(world, playerPos, soundPos);
		final float occlusionAccumulation = traceDirectOcclusion(world, playerPos, soundPos, normalToPlayer);

		float directCutoff = (float) Math.exp(-occlusionAccumulation * absorptionCoeff);
		final float directGain = (float) Math.pow(directCutoff, 0.1);

		if (mc.thePlayer.isInsideOfMaterial(Material.water)) {
			directCutoff *= 1.0f - Config.underwaterFilter;
		}

		if (isRain) {
			return new SoundEnvironment(0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f, 1.0f,
					directCutoff, directGain, airAbsorptionFactor);
		}

		final int numRays = Config.environmentEvaluationRays;
		final int rayBounces = Config.environmentEvaluationRaysBounces;

		final ReverbRays rays = castReverbRays(world, playerPos, soundPos, numRays, rayBounces);
		return shapeEnvironment(rays, occlusionAccumulation, absorptionCoeff, directCutoff, airAbsorptionFactor,
				numRays, rayBounces);
	}

	// Snow dampens sounds: rate how snowed-in the player, the sound and the
	// point between them are, and raise the air absorption accordingly.
	private static float computeAirAbsorptionFactor(final World world, final Vec3 playerPos, final Vec3 soundPos) {
		if (!world.isRaining()) return 1.0f;

		final Vec3 middlePos = playerPos.addVector(soundPos.xCoord, soundPos.yCoord, soundPos.zCoord);
		middlePos.xCoord = middlePos.xCoord*0.5d;
		middlePos.yCoord = middlePos.yCoord*0.5d;
		middlePos.zCoord = middlePos.zCoord*0.5d;

		final int snowingPlayer = isSnowingAt(world, playerPos, false);
		final int snowingSound = isSnowingAt(world, soundPos, false);
		final int snowingMiddle = isSnowingAt(world, middlePos, false);
		final float snowFactor = snowingPlayer * 0.25f + snowingMiddle * 0.5f + snowingSound * 0.25f;

		if (snowFactor <= 0.0f) return 1.0f;
		return Math.max(Config.snowAirAbsorptionFactor*world.getRainStrength(1.0f)*snowFactor, 1.0f);
	}

	// Direct path occlusion: march up to 10 ray segments from the sound
	// towards the player, accumulating occlusion for every block hit.
	private static float traceDirectOcclusion(final World world, final Vec3 playerPos, final Vec3 soundPos, final Vec3 normalToPlayer) {
		Vec3 rayOrigin = soundPos;

		float occlusionAccumulation = 0.0f;

		for (int i = 0; i < 10; i++) {
			final MovingObjectPosition rayHit = world.rayTraceBlocks(rayOrigin, playerPos, true);
			if (rayHit == null) break;

			final Block blockHit = world.getBlock(rayHit.blockX,rayHit.blockY,rayHit.blockZ);

			float blockOcclusion = 1.0f;

			if (!blockHit.isOpaqueCube()) {
				// log("not a solid block!");
				blockOcclusion *= 0.15f;
			}

			occlusionAccumulation += blockOcclusion;

			rayOrigin = Vec3.createVectorHelper(rayHit.hitVec.xCoord + normalToPlayer.xCoord * 0.1, rayHit.hitVec.yCoord + normalToPlayer.yCoord * 0.1,
					rayHit.hitVec.zCoord + normalToPlayer.zCoord * 0.1);
		}

		return occlusionAccumulation;
	}

	// Accumulated raw output of the reverb ray cast, before shaping.
	private static class ReverbRays {
		float sendGain0;
		float sendGain1;
		float sendGain2;
		float sendGain3;
		float sharedAirspace;
		final float[] bounceReflectivityRatio;

		ReverbRays(final int rayBounces) {
			bounceReflectivityRatio = new float[rayBounces];
		}

		// Crossfade the reflection energy of one bounce into the four reverb
		// sends by its delay.
		void accumulateSend(final float totalRayDistance, final float energyTowardsPlayer, final float blockReflectivity, final float rcpTotalRays) {
			final float reflectionDelay = (float) Math.max(totalRayDistance, 0.0) * 0.12f * blockReflectivity;

			final float cross0 = 1.0f - MathHelper.clamp_float(Math.abs(reflectionDelay - 0.0f), 0.0f, 1.0f);
			final float cross1 = 1.0f - MathHelper.clamp_float(Math.abs(reflectionDelay - 1.0f), 0.0f, 1.0f);
			final float cross2 = 1.0f - MathHelper.clamp_float(Math.abs(reflectionDelay - 2.0f), 0.0f, 1.0f);
			final float cross3 = MathHelper.clamp_float(reflectionDelay - 2.0f, 0.0f, 1.0f);

			sendGain0 += cross0 * energyTowardsPlayer * 6.4f * rcpTotalRays;
			sendGain1 += cross1 * energyTowardsPlayer * 12.8f * rcpTotalRays;
			sendGain2 += cross2 * energyTowardsPlayer * 12.8f * rcpTotalRays;
			sendGain3 += cross3 * energyTowardsPlayer * 12.8f * rcpTotalRays;
		}
	}

	// Shoot rays around the sound on a golden-angle sphere and bounce each
	// hit to estimate reverb energy and shared airspace.
	private static ReverbRays castReverbRays(final World world, final Vec3 playerPos, final Vec3 soundPos, final int numRays, final int rayBounces) {
		final float phi = 1.618033988f;
		final float gAngle = phi * (float) Math.PI * 2.0f;
		final float maxDistance = 256.0f;

		final float rcpTotalRays = 1.0f / (numRays * rayBounces);

		final ReverbRays result = new ReverbRays(rayBounces);

		for (int i = 0; i < numRays; i++) {
			final float fiN = (float) i / numRays;
			final float longitude = gAngle * (float) i;
			final float latitude = (float) Math.asin(fiN * 2.0f - 1.0f);

			final Vec3 rayDir = Vec3.createVectorHelper(Math.cos(latitude) * Math.cos(longitude),
					Math.cos(latitude) * Math.sin(longitude), Math.sin(latitude));

			final Vec3 rayStart = Vec3.createVectorHelper(soundPos.xCoord, soundPos.yCoord, soundPos.zCoord);

			final Vec3 rayEnd = Vec3.createVectorHelper(rayStart.xCoord + rayDir.xCoord * maxDistance, rayStart.yCoord + rayDir.yCoord * maxDistance,
					rayStart.zCoord + rayDir.zCoord * maxDistance);

			final MovingObjectPosition rayHit = world.rayTraceBlocks(rayStart, rayEnd, true);
			if (rayHit == null) continue;

			castBounces(world, playerPos, soundPos, rayDir, rayHit, rayBounces, maxDistance, rcpTotalRays, result);
		}

		return result;
	}

	// Secondary ray bounces for one primary hit, accumulating into result.
	private static void castBounces(final World world, final Vec3 playerPos, final Vec3 soundPos, final Vec3 rayDir,
			final MovingObjectPosition rayHit, final int rayBounces, final float maxDistance, final float rcpTotalRays,
			final ReverbRays result) {
		final double rayLength = soundPos.distanceTo(rayHit.hitVec);

		Block lastHitBlock = world.getBlock(rayHit.blockX,rayHit.blockY,rayHit.blockZ);
		Vec3 lastHitPos = rayHit.hitVec;
		Vec3 lastHitNormal = getNormalFromFacing(rayHit.sideHit);
		Vec3 lastRayDir = rayDir;

		float totalRayDistance = (float) rayLength;

		for (int j = 0; j < rayBounces; j++) {
			final Vec3 newRayDir = reflect(lastRayDir, lastHitNormal);
			final Vec3 newRayStart = Vec3.createVectorHelper(lastHitPos.xCoord + lastHitNormal.xCoord * 0.01,
					lastHitPos.yCoord + lastHitNormal.yCoord * 0.01, lastHitPos.zCoord + lastHitNormal.zCoord * 0.01);
			final Vec3 newRayEnd = Vec3.createVectorHelper(newRayStart.xCoord + newRayDir.xCoord * maxDistance,
					newRayStart.yCoord + newRayDir.yCoord * maxDistance, newRayStart.zCoord + newRayDir.zCoord * maxDistance);

			final MovingObjectPosition newRayHit = world.rayTraceBlocks(newRayStart, newRayEnd, true);

			float energyTowardsPlayer = 0.25f;
			final float blockReflectivity = getBlockReflectivity(lastHitBlock);
			energyTowardsPlayer *= blockReflectivity * 0.75f + 0.25f;

			// Nowhere to bounce off of, stop bouncing!
			if (newRayHit == null) {
				totalRayDistance += lastHitPos.distanceTo(playerPos);
				result.accumulateSend(totalRayDistance, energyTowardsPlayer, blockReflectivity, rcpTotalRays);
				break;
			}

			final double newRayLength = lastHitPos.distanceTo(newRayHit.hitVec);

			result.bounceReflectivityRatio[j] += blockReflectivity;

			totalRayDistance += newRayLength;

			lastHitPos = newRayHit.hitVec;
			lastHitNormal = getNormalFromFacing(newRayHit.sideHit);
			lastRayDir = newRayDir;
			lastHitBlock = world.getBlock(newRayHit.blockX,newRayHit.blockY,newRayHit.blockZ);

			// Cast one final ray towards the player. If it's
			// unobstructed, then the sound source and the player
			// share airspace.
			if (!Config.simplerSharedAirspaceSimulation || j == rayBounces - 1) {
				final Vec3 finalRayStart = Vec3.createVectorHelper(lastHitPos.xCoord + lastHitNormal.xCoord * 0.01,
						lastHitPos.yCoord + lastHitNormal.yCoord * 0.01, lastHitPos.zCoord + lastHitNormal.zCoord * 0.01);

				final MovingObjectPosition finalRayHit = world.rayTraceBlocks(finalRayStart, playerPos, true);

				if (finalRayHit == null) {
					// log("Secondary ray hit the player!");
					result.sharedAirspace += 1.0f;
				}
			}

			result.accumulateSend(totalRayDistance, energyTowardsPlayer, blockReflectivity, rcpTotalRays);
		}
	}

	// Final send gain/cutoff shaping: normalize the ray results, blend in
	// shared airspace and fold everything into a SoundEnvironment.
	private static SoundEnvironment shapeEnvironment(final ReverbRays rays, final float occlusionAccumulation,
			final float absorptionCoeff, final float directCutoffIn, final float airAbsorptionFactor,
			final int numRays, final int rayBounces) {
		final float rcpTotalRays = 1.0f / (numRays * rayBounces);
		final float rcpPrimaryRays = 1.0f / numRays;

		final float[] bounceReflectivityRatio = rays.bounceReflectivityRatio;

		// Pre-existing behavior kept as-is: indices [0..3] are hardcoded even
		// though the array is sized by Config.environmentEvaluationRaysBounces
		// (latent crash if that config is ever < 4).
		bounceReflectivityRatio[0] = bounceReflectivityRatio[0] / numRays;
		bounceReflectivityRatio[1] = bounceReflectivityRatio[1] / numRays;
		bounceReflectivityRatio[2] = bounceReflectivityRatio[2] / numRays;
		bounceReflectivityRatio[3] = bounceReflectivityRatio[3] / numRays;

		float sharedAirspace = rays.sharedAirspace;

		sharedAirspace *= 64.0f;

		if (Config.simplerSharedAirspaceSimulation) {
			sharedAirspace *= rcpPrimaryRays;
		} else {
			sharedAirspace *= rcpTotalRays;
		}

		final float sharedAirspaceWeight0 = MathHelper.clamp_float(sharedAirspace / 20.0f, 0.0f, 1.0f);
		final float sharedAirspaceWeight1 = MathHelper.clamp_float(sharedAirspace / 15.0f, 0.0f, 1.0f);
		final float sharedAirspaceWeight2 = MathHelper.clamp_float(sharedAirspace / 10.0f, 0.0f, 1.0f);
		final float sharedAirspaceWeight3 = MathHelper.clamp_float(sharedAirspace / 10.0f, 0.0f, 1.0f);

		float sendCutoff0 = (float) Math.exp(-occlusionAccumulation * absorptionCoeff * 1.0f) * (1.0f - sharedAirspaceWeight0)
				+ sharedAirspaceWeight0;
		float sendCutoff1 = (float) Math.exp(-occlusionAccumulation * absorptionCoeff * 1.0f) * (1.0f - sharedAirspaceWeight1)
				+ sharedAirspaceWeight1;
		float sendCutoff2 = (float) Math.exp(-occlusionAccumulation * absorptionCoeff * 1.5f) * (1.0f - sharedAirspaceWeight2)
				+ sharedAirspaceWeight2;
		float sendCutoff3 = (float) Math.exp(-occlusionAccumulation * absorptionCoeff * 1.5f) * (1.0f - sharedAirspaceWeight3)
				+ sharedAirspaceWeight3;

		// attempt to preserve directionality when airspace is shared by
		// allowing some of the dry signal through but filtered
		final float averageSharedAirspace = (sharedAirspaceWeight0 + sharedAirspaceWeight1 + sharedAirspaceWeight2
				+ sharedAirspaceWeight3) * 0.25f;
		final float directCutoff = Math.max((float) Math.pow(averageSharedAirspace, 0.5) * 0.2f, directCutoffIn);

		final float directGain = (float) Math.pow(directCutoff, 0.1);

		float sendGain0 = rays.sendGain0;
		float sendGain1 = rays.sendGain1;
		float sendGain2 = rays.sendGain2;
		float sendGain3 = rays.sendGain3;

		sendGain1 *= bounceReflectivityRatio[1];
		sendGain2 *= (float) Math.pow(bounceReflectivityRatio[2], 3.0);
		sendGain3 *= (float) Math.pow(bounceReflectivityRatio[3], 4.0);

		sendGain0 = MathHelper.clamp_float(sendGain0, 0.0f, 1.0f);
		sendGain1 = MathHelper.clamp_float(sendGain1, 0.0f, 1.0f);
		sendGain2 = MathHelper.clamp_float(sendGain2 * 1.05f - 0.05f, 0.0f, 1.0f);
		sendGain3 = MathHelper.clamp_float(sendGain3 * 1.05f - 0.05f, 0.0f, 1.0f);

		sendGain0 *= (float) Math.pow(sendCutoff0, 0.1);
		sendGain1 *= (float) Math.pow(sendCutoff1, 0.1);
		sendGain2 *= (float) Math.pow(sendCutoff2, 0.1);
		sendGain3 *= (float) Math.pow(sendCutoff3, 0.1);

		if (mc.thePlayer.isInWater()) {
			sendCutoff0 *= 0.4f;
			sendCutoff1 *= 0.4f;
			sendCutoff2 *= 0.4f;
			sendCutoff3 *= 0.4f;
		}

		return new SoundEnvironment(sendGain0, sendGain1, sendGain2, sendGain3, sendCutoff0, sendCutoff1, sendCutoff2,
				sendCutoff3, directCutoff, directGain, airAbsorptionFactor);
	}

	public static void log(final String message) {
		System.out.println(logPrefix.concat(" : ").concat(message));
	}

	public static void logError(final String errorMessage) {
		System.out.println(logPrefix.concat(" [ERROR] : ").concat(errorMessage));
	}

	protected static boolean checkErrorLog(final String errorMessage) {
		final int error = AL10.alGetError();
		if (error == AL10.AL_NO_ERROR) {
			return false;
		}

		String errorName = switch (error) {
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
