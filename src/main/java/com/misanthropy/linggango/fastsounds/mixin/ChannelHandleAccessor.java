package com.misanthropy.linggango.fastsounds.mixin;

import com.mojang.blaze3d.audio.Channel;
import net.minecraft.client.sounds.ChannelAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ChannelAccess.ChannelHandle.class)
public interface ChannelHandleAccessor {
    @Accessor("channel")
    void fastsounds$setChannel(Channel channel);

    @Accessor("stopped")
    void fastsounds$setStopped(boolean stopped);
}
