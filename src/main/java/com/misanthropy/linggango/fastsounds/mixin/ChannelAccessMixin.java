package com.misanthropy.linggango.fastsounds.mixin;

import java.util.Set;
import java.util.concurrent.Executor;

import com.misanthropy.linggango.fastsounds.optimization.ChannelPool;
import com.mojang.blaze3d.audio.Channel;
import com.mojang.blaze3d.audio.Library;
import net.minecraft.client.sounds.ChannelAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(ChannelAccess.class)
public abstract class ChannelAccessMixin implements ChannelPool {
    @Shadow
    @Final
    private Set<ChannelAccess.ChannelHandle> channels;
    @Shadow
    @Final
    Library library;
    @Shadow
    @Final
    Executor executor;

    /**
     * From FastSounds 0.6: vanilla blocks the render thread on createHandle().join()
     * for every sound that starts. Hand back an empty handle instead and attach the
     * source on the sound thread. All later work on the handle is queued on the same
     * single-threaded executor, so it always runs after the source is attached.
     */
    @Override
    public ChannelAccess.ChannelHandle fastsounds$handleNow(Library.Pool pool) {
        ChannelAccess.ChannelHandle handle = ((ChannelAccess) (Object) this).new ChannelHandle(null);
        executor.execute(() -> {
            Channel channel = library.acquireChannel(pool);
            if (channel != null) {
                ((ChannelHandleAccessor) handle).fastsounds$setChannel(channel);
                channels.add(handle);
            } else {
                // Pool full: vanilla drops the sound; mark it stopped so the engine cleans it up.
                ((ChannelHandleAccessor) handle).fastsounds$setStopped(true);
            }
        });
        return handle;
    }

    /**
     * Evicted sounds give their source back right away. Vanilla only releases
     * stopped channels on the next tick, so in a burst the pool filled up and new
     * sounds failed even though older ones had just been stopped.
     */
    @Override
    public void fastsounds$releaseNow(ChannelAccess.ChannelHandle handle) {
        executor.execute(() -> {
            // Only release handles vanilla hasn't already released (scheduleTick/clear remove them).
            if (channels.remove(handle)) {
                handle.release();
            }
        });
    }
}
