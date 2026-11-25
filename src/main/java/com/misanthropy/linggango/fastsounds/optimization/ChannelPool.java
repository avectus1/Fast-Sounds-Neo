package com.misanthropy.linggango.fastsounds.optimization;

import com.mojang.blaze3d.audio.Library;
import net.minecraft.client.sounds.ChannelAccess;

/** Implemented on ChannelAccess by mixin. */
public interface ChannelPool {
    /** Returns a handle right away; the OpenAL source is attached on the sound thread. */
    ChannelAccess.ChannelHandle fastsounds$handleNow(Library.Pool pool);

    /** Stops the handle's source and returns it to the pool before any later channel request. */
    void fastsounds$releaseNow(ChannelAccess.ChannelHandle handle);
}
