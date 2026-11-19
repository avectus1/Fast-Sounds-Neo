package com.misanthropy.linggango.fastsounds;

import com.mojang.blaze3d.audio.Library;
import net.minecraft.client.sounds.ChannelAccess;

public interface ImmediateHandles {
    ChannelAccess.ChannelHandle fastsounds$handleNow(Library.Pool pool);
}
