package com.misanthropy.linggango.fastsounds.mixin;

import java.util.concurrent.CompletableFuture;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.misanthropy.linggango.fastsounds.Config;
import com.misanthropy.linggango.fastsounds.optimization.ChannelPool;
import com.mojang.blaze3d.audio.Library;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = SoundEngine.class, priority = 1100)
public abstract class SoundEnginePlayMixin {
    /** Non-blocking play (FastSounds 0.6), as a WrapOperation so other mods can still hook this call. */
    @WrapOperation(
            method = "play(Lnet/minecraft/client/resources/sounds/SoundInstance;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/sounds/ChannelAccess;createHandle(Lcom/mojang/blaze3d/audio/Library$Pool;)Ljava/util/concurrent/CompletableFuture;"),
            require = 0
    )
    private CompletableFuture<ChannelAccess.ChannelHandle> fastsounds$handleWithoutWaiting(ChannelAccess access, Library.Pool pool,
            Operation<CompletableFuture<ChannelAccess.ChannelHandle>> original) {
        if (!Config.nonBlockingPlay) {
            return original.call(access, pool);
        }
        return CompletableFuture.completedFuture(((ChannelPool) access).fastsounds$handleNow(pool));
    }
}
