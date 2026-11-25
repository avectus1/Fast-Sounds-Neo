package com.misanthropy.linggango.fastsounds.mixin;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

import com.misanthropy.linggango.fastsounds.Config;
import com.misanthropy.linggango.fastsounds.Fastsounds;
import com.misanthropy.linggango.fastsounds.optimization.ChannelPool;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = SoundEngine.class, priority = 999)
public abstract class SoundEngineMixin {
    @Unique
    private static final double fastsounds$POS_EPSILON = 1.0E-4;
    @Unique
    private static final float fastsounds$DIR_EPSILON = 1.0E-4F;

    @Shadow
    @Final
    private Map<SoundInstance, ChannelAccess.ChannelHandle> instanceToChannel;
    @Shadow
    @Final
    private ChannelAccess channelAccess;
    @Shadow
    private boolean loaded;

    /** Sounds we already asked to stop; their channel is released on the next tick. */
    @Unique
    private final Set<SoundInstance> fastsounds$pendingEvictions = Collections.newSetFromMap(new IdentityHashMap<>());

    @Unique
    private double fastsounds$lastCamX = Double.NaN;
    @Unique
    private double fastsounds$lastCamY;
    @Unique
    private double fastsounds$lastCamZ;
    @Unique
    private float fastsounds$lastLookX, fastsounds$lastLookY, fastsounds$lastLookZ;
    @Unique
    private float fastsounds$lastUpX, fastsounds$lastUpY, fastsounds$lastUpZ;

    /**
     * Runs after NeoForge's PlaySoundEvent and after the sound is resolved,
     * right before a channel is requested.
     */
    @Inject(
            method = "play(Lnet/minecraft/client/resources/sounds/SoundInstance;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/resources/sounds/SoundInstance;getSound()Lnet/minecraft/client/resources/sounds/Sound;"),
            cancellable = true,
            require = 1,
            allow = 1
    )
    private void fastsounds$onPlaySound(SoundInstance sound, CallbackInfo ci) {
        if (sound == null || Minecraft.getInstance().isPaused()) {
            return;
        }

        Fastsounds.OPTIMIZER.recordSoundProcessed();
        if (!fastsounds$pendingEvictions.isEmpty()) {
            fastsounds$pendingEvictions.removeIf(s -> !instanceToChannel.containsKey(s));
        }

        if (Config.enableDynamicMixing && !Fastsounds.OPTIMIZER.shouldPlaySound(sound)) {
            ci.cancel();
            return;
        }

        // Evicted sounds stay in instanceToChannel for up to 20 ticks, but their
        // OpenAL source is already back in the pool (see ChannelAccessMixin).
        if (instanceToChannel.size() - fastsounds$pendingEvictions.size() < Config.maxConcurrentSounds) {
            return;
        }

        SoundInstance victim = Fastsounds.OPTIMIZER.getSoundToEvict(instanceToChannel.keySet(), fastsounds$pendingEvictions, sound);
        ChannelAccess.ChannelHandle handle = victim == null ? null : instanceToChannel.get(victim);
        if (handle != null) {
            fastsounds$pendingEvictions.add(victim);
            ((ChannelPool) channelAccess).fastsounds$releaseNow(handle);
        } else {
            ci.cancel();
        }
    }

    /**
     * Vanilla allocates a ListenerTransform + two Vec3 and queues a task on the
     * sound thread every frame. Skip that while the camera hasn't moved.
     */
    @Inject(method = "updateSource(Lnet/minecraft/client/Camera;)V", at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private void fastsounds$throttleUpdateSource(Camera camera, CallbackInfo ci) {
        if (!loaded || !Config.enableCameraThrottle || camera == null || !camera.isInitialized()) {
            fastsounds$lastCamX = Double.NaN;
            return;
        }

        Vec3 pos = camera.getPosition();
        Vector3f look = camera.getLookVector();
        Vector3f up = camera.getUpVector();

        if (!Double.isNaN(fastsounds$lastCamX)
                && Math.abs(fastsounds$lastCamX - pos.x) < fastsounds$POS_EPSILON
                && Math.abs(fastsounds$lastCamY - pos.y) < fastsounds$POS_EPSILON
                && Math.abs(fastsounds$lastCamZ - pos.z) < fastsounds$POS_EPSILON
                && Math.abs(fastsounds$lastLookX - look.x) < fastsounds$DIR_EPSILON
                && Math.abs(fastsounds$lastLookY - look.y) < fastsounds$DIR_EPSILON
                && Math.abs(fastsounds$lastLookZ - look.z) < fastsounds$DIR_EPSILON
                && Math.abs(fastsounds$lastUpX - up.x) < fastsounds$DIR_EPSILON
                && Math.abs(fastsounds$lastUpY - up.y) < fastsounds$DIR_EPSILON
                && Math.abs(fastsounds$lastUpZ - up.z) < fastsounds$DIR_EPSILON) {
            ci.cancel();
            return;
        }

        fastsounds$lastCamX = pos.x;
        fastsounds$lastCamY = pos.y;
        fastsounds$lastCamZ = pos.z;
        fastsounds$lastLookX = look.x;
        fastsounds$lastLookY = look.y;
        fastsounds$lastLookZ = look.z;
        fastsounds$lastUpX = up.x;
        fastsounds$lastUpY = up.y;
        fastsounds$lastUpZ = up.z;
    }

    /** Sound engine reload (F3+T, device change): forget all cached state. */
    @Inject(method = "destroy()V", at = @At("HEAD"), require = 1, allow = 1)
    private void fastsounds$resetAfterDestroy(CallbackInfo ci) {
        fastsounds$lastCamX = Double.NaN;
        fastsounds$pendingEvictions.clear();
        Fastsounds.OPTIMIZER.reset();
    }
}
