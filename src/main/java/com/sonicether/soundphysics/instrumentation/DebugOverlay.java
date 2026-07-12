package com.sonicether.soundphysics.instrumentation;

import com.sonicether.soundphysics.Config;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.RenderGameOverlayEvent;

import java.util.List;
import java.util.Locale;

/**
 * F3 debug lines + periodic log counters (same spirit as the
 * old debugInfoShow). Wall-bleed through a bad edge and a stale probe both
 * present to a playtester as "reverb sounds a bit off" — these numbers make
 * them distinguishable.
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
				"[SoundPhysics] tick %d, load %.0f%%, %d src + %d voice",
				stats.workerTick, stats.workerLoadPct, stats.playingSources, stats.voiceSpeakers));
		left.add(String.format(Locale.ROOT,
				"[SoundPhysics] field: %d cells, %d edges, %d reachable | escape %.0f%%",
				stats.cellsStored, stats.edgesStored, stats.fieldNodes, 100.0f * stats.escapeRatio));
		left.add(String.format(Locale.ROOT,
				"[SoundPhysics] rays d%d p%d e%d pr%d / %d | bakes %d edge + %d probe, %d paths",
				stats.raysDirect, stats.raysPath, stats.raysEdge, stats.raysProbe, stats.rayBudget,
				stats.edgeBakes, stats.probeRounds, stats.pathValidations));

		if (--logCountdown > 0) return;
		logCountdown = LOG_EVERY_TICKS;
		com.sonicether.soundphysics.SoundPhysics.log(String.format(Locale.ROOT,
				"stats: tick=%d load=%.0f%% sources=%d field=%d/%d/%d rays=%d/%d bakes=%d+%d paths=%d",
				stats.workerTick, stats.workerLoadPct, stats.playingSources + stats.voiceSpeakers,
				stats.cellsStored, stats.edgesStored, stats.fieldNodes,
				stats.raysDirect + stats.raysPath + stats.raysEdge + stats.raysProbe, stats.rayBudget,
				stats.edgeBakes, stats.probeRounds, stats.pathValidations));
	}
}
