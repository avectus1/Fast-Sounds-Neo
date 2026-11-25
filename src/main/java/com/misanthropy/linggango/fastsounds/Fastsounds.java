package com.misanthropy.linggango.fastsounds;

import com.misanthropy.linggango.fastsounds.optimization.SoundOptimizer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;

/**
 * FastSounds by Misanthropy, NeoForge 1.21.1 port. Client only.
 */
@Mod(value = Fastsounds.MODID, dist = Dist.CLIENT)
public final class Fastsounds {
    public static final String MODID = "fastsounds";
    public static final SoundOptimizer OPTIMIZER = new SoundOptimizer();

    public Fastsounds(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, Config.SPEC);
        modBus.addListener(ModConfigEvent.Loading.class, Config::onConfig);
        modBus.addListener(ModConfigEvent.Reloading.class, Config::onConfig);
    }
}
