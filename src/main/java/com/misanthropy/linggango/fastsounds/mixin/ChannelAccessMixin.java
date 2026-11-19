package com.misanthropy.linggango.fastsounds.mixin;

import com.misanthropy.linggango.fastsounds.ImmediateHandles;
import com.mojang.blaze3d.audio.Channel;
import com.mojang.blaze3d.audio.Library;
import net.minecraft.client.sounds.ChannelAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Set;
import java.util.concurrent.Executor;

@Mixin(ChannelAccess.class)
public abstract class ChannelAccessMixin implements ImmediateHandles {
    @Shadow @Final private Set<ChannelAccess.ChannelHandle> channels;
    @Shadow @Final Library library;
    @Shadow @Final Executor executor;

    @Override
    public ChannelAccess.ChannelHandle fastsounds$handleNow(Library.Pool pool) {
        ChannelAccess.ChannelHandle handle = ((ChannelAccess) (Object) this).new ChannelHandle(null);
        this.executor.execute(() -> {
            Channel channel = this.library.acquireChannel(pool);
            if (channel != null) {
                ((ChannelHandleAccessor) handle).fastsounds$setChannel(channel);
                this.channels.add(handle);
            } else {
                ((ChannelHandleAccessor) handle).fastsounds$setStopped(true);
            }
        });
        return handle;
    }
}
