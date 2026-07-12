package com.sonicether.soundphysics.instrumentation;

import com.sonicether.soundphysics.Config;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.RenderGameOverlayEvent;

import java.util.List;
import java.util.Locale;

/**
 * F3 debug lines + periodic log counters (same spirit as the
 * old debugInfoShow). Wall-bleed and stale tails both present to a playtester
 * as "reverb sounds a bit off" — these numbers make them distinguishable.
 */
public final class DebugOverlay {

	private static final int LOG_EVERY_TICKS = 1200; // one counter line a minute at 20 TPS
	private int logCountdown = LOG_EVERY_TICKS;

	@SubscribeEvent
	public void onDebugOverlay(final RenderGameOverlayEvent.Text event) {
		if (!Config.debugInfoShow) return;
		if (!Minecraft.getMinecraft().gameSettings.showDebugInfo) return;

		final Stats stats = Stats.INSTANCE;
		final List<String> left = event.left;
		left.add("");
		left.add(String.format(Locale.ROOT,
				"[SoundPhysics] tick %d, load %.0f%%, %d src + %d voice, cells %d hot / %d stored",
				stats.workerTick, stats.workerLoadPct, stats.playingSources, stats.voiceSpeakers,
				stats.activeCells, stats.storedCells));
		left.add(String.format(Locale.ROOT,
				"[SoundPhysics] age p95/p99: %s | samples %d",
				perBucket(stats.ageP95, stats.ageP99), stats.agedSampleCount));
		left.add(String.format(Locale.ROOT,
				"[SoundPhysics] gate a/p/r: %d/%d/%d, conn hit %.0f%%",
				stats.gateAccepts, stats.gatePartials, stats.gateRejects,
				100.0f * stats.connectivityHitRate()));
		left.add(String.format(Locale.ROOT,
				"[SoundPhysics] rays c%d r%d f%d d%d n%d p%d / %d",
				stats.raysCandidates, stats.raysRevalidation, stats.raysFinalLegs,
				stats.raysDirect, stats.raysConnectivity, stats.raysPrefetch, stats.rayBudget));
		left.add(String.format(Locale.ROOT,
				"[SoundPhysics] occupancy %.1f %.1f %.1f %.1f | escape %.0f%%",
				stats.occupancy[0], stats.occupancy[1], stats.occupancy[2], stats.occupancy[3],
				100.0f * stats.escapeRatio));

		if (--logCountdown > 0) return;
		logCountdown = LOG_EVERY_TICKS;
		com.sonicether.soundphysics.SoundPhysics.log(String.format(Locale.ROOT,
				"stats: tick=%d load=%.0f%% sources=%d cells=%d/%d ageP99=%s gate=%d/%d/%d connHit=%.0f%% rays=%d/%d",
				stats.workerTick, stats.workerLoadPct, stats.playingSources + stats.voiceSpeakers,
				stats.activeCells, stats.storedCells,
				String.format(Locale.ROOT, "%d,%d,%d,%d", stats.ageP99[0], stats.ageP99[1], stats.ageP99[2], stats.ageP99[3]),
				stats.gateAccepts, stats.gatePartials, stats.gateRejects, 100.0f * stats.connectivityHitRate(),
				stats.raysCandidates + stats.raysRevalidation + stats.raysFinalLegs + stats.raysDirect
						+ stats.raysConnectivity + stats.raysPrefetch,
				stats.rayBudget));
	}

	private static String perBucket(final int[] p95, final int[] p99) {
		final StringBuilder sb = new StringBuilder();
		for (int bucket = 0; bucket < 4; bucket++) {
			if (bucket > 0) sb.append("  ");
			sb.append('b').append(bucket).append(' ').append(p95[bucket]).append('/').append(p99[bucket]);
		}
		return sb.toString();
	}
}
