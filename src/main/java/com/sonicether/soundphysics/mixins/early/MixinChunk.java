package com.sonicether.soundphysics.mixins.early;

import com.sonicether.soundphysics.SoundPhysics;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * fillChunk is the client-side bulk path (full chunk data / multi-block-change
 * packets replace whole storage arrays without going through setBlock), so the
 * CPU section cache must re-copy the entire column afterwards.
 */
@Mixin(Chunk.class)
public abstract class MixinChunk {

    @Shadow
    public int xPosition;

    @Shadow
    public int zPosition;

    @Inject(method = "fillChunk([BIIZ)V", at = @At("RETURN"))
    private void soundphysics$onFillChunk(byte[] data, int lsbMask, int msbMask, boolean full, CallbackInfo ci) {
        SoundPhysics.onChunkFilled(this.xPosition, this.zPosition);
    }
}
