package com.misanthropy.linggango.fastsounds.mixin;

import com.misanthropy.linggango.fastsounds.Config;
import com.misanthropy.linggango.fastsounds.ImmediateHandles;
import com.mojang.blaze3d.audio.Library;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.concurrent.CompletableFuture;

@Mixin(value = SoundEngine.class, priority = 1100)
public abstract class SoundEnginePlayMixin {
    @Redirect(method = "play", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/sounds/ChannelAccess;createHandle(Lcom/mojang/blaze3d/audio/Library$Pool;)Ljava/util/concurrent/CompletableFuture;"), require = 0)
    private CompletableFuture<ChannelAccess.ChannelHandle> fastsounds$handleWithoutWaiting(ChannelAccess access, Library.Pool pool) {
        if (!Config.nonBlockingPlay) {
            return access.createHandle(pool);
        }
        return CompletableFuture.completedFuture(((ImmediateHandles) access).fastsounds$handleNow(pool));
    }
}
