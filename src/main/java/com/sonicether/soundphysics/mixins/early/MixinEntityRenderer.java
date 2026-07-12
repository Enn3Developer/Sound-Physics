package com.sonicether.soundphysics.mixins.early;

import com.sonicether.soundphysics.SoundPhysics;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Vanilla plays the rain loop at a randomly sampled rain-landing column near
 * the player (direction jumps every event) and applies its own quiet/pitched
 * hack when that lands above the head — which double-dips with the engine's
 * measured roof occlusion. Redirect both playSound calls so the engine can
 * anchor the sound to where rain audibly lands and keep muffling to itself.
 * The invocation owner is WorldClient (the static type of mc.theWorld), NOT
 * World — targeting World matches zero invocations and crashes mixin apply.
 */
@Mixin(EntityRenderer.class)
public abstract class MixinEntityRenderer {

    @Redirect(method = "addRainParticles",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/WorldClient;playSound(DDDLjava/lang/String;FFZ)V"))
    private void soundphysics$placeRainSound(WorldClient world, double x, double y, double z,
                                             String sound, float volume, float pitch, boolean distanceDelay) {
        SoundPhysics.onPlayRainSound(world, x, y, z, sound, volume, pitch, distanceDelay);
    }
}
