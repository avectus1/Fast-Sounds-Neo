package com.misanthropy.linggango.fastsounds;

import java.util.List;

import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Client config. Keys, defaults and ranges match FastSounds 0.6 so existing
 * fastsounds-client.toml files keep working.
 */
public final class Config {
    public static final ModConfigSpec SPEC;

    private static final ModConfigSpec.BooleanValue ENABLE_DYNAMIC_MIXING;
    private static final ModConfigSpec.IntValue MAX_CONCURRENT_SOUNDS;
    private static final ModConfigSpec.BooleanValue ENABLE_DISTANCE_CULL;
    private static final ModConfigSpec.IntValue MAX_SOUND_DISTANCE;
    private static final ModConfigSpec.BooleanValue ENABLE_VOLUME_CULL;
    private static final ModConfigSpec.BooleanValue ENABLE_SAME_TICK_DEDUP;
    private static final ModConfigSpec.IntValue MAX_SAME_TICK_SOUNDS;
    private static final ModConfigSpec.BooleanValue ENABLE_CAMERA_THROTTLE;
    private static final ModConfigSpec.BooleanValue NON_BLOCKING_PLAY;
    private static final ModConfigSpec.ConfigValue<List<? extends String>> DISTANCE_CULL_WHITELIST;
    private static final ModConfigSpec.ConfigValue<List<? extends String>> VOLUME_CULL_WHITELIST;
    private static final ModConfigSpec.ConfigValue<List<? extends String>> SAME_TICK_WHITELIST;
    private static final ModConfigSpec.ConfigValue<List<? extends String>> COOLDOWN_WHITELIST;
    private static final ModConfigSpec.ConfigValue<List<? extends String>> EVICTION_WHITELIST;

    // Baked values (read on the render thread every sound, so plain fields).
    public static volatile boolean enableDynamicMixing = true;
    public static volatile int maxConcurrentSounds = 230;
    public static volatile boolean enableDistanceCull = true;
    public static volatile int maxSoundDistance = 64;
    public static volatile boolean enableVolumeCull = true;
    public static volatile boolean enableSameTickDedup = true;
    public static volatile int maxSameTickSounds = 3;
    public static volatile boolean enableCameraThrottle = true;
    public static volatile boolean nonBlockingPlay = true;
    public static volatile List<? extends String> distanceCullWhitelist = List.of();
    public static volatile List<? extends String> volumeCullWhitelist = List.of("ambientsounds");
    public static volatile List<? extends String> sameTickWhitelist = List.of("ambientsounds");
    public static volatile List<? extends String> cooldownWhitelist = List.of("ambientsounds");
    public static volatile List<? extends String> evictionWhitelist = List.of("ambientsounds");

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.push("general");
        ENABLE_DYNAMIC_MIXING = b
                .comment("Master switch for sound culling, cooldowns and same-tick dedup.")
                .define("enableDynamicMixing", true);
        MAX_CONCURRENT_SOUNDS = b
                .comment("When this many sounds are playing, the farthest (or lowest priority) one is replaced, or the new one is dropped.")
                .defineInRange("maxConcurrentSounds", 230, 8, 245);
        ENABLE_DISTANCE_CULL = b
                .comment("Skip positional sounds farther than maxSoundDistance from the camera.")
                .define("enableDistanceCull", true);
        MAX_SOUND_DISTANCE = b
                .comment("Distance in blocks used by enableDistanceCull.")
                .defineInRange("maxSoundDistance", 64, 16, 256);
        ENABLE_VOLUME_CULL = b
                .comment("Skip positional sounds with a base volume below 0.05.")
                .define("enableVolumeCull", true);
        ENABLE_SAME_TICK_DEDUP = b
                .comment("Limit how many copies of the same sound can start in one game tick.")
                .define("enableSameTickDedup", true);
        MAX_SAME_TICK_SOUNDS = b
                .comment("Copies of the same sound allowed per game tick.")
                .defineInRange("maxSameTickSounds", 3, 1, 20);
        b.pop();

        b.push("optimizations");
        ENABLE_CAMERA_THROTTLE = b
                .comment("Throttles audio listener and camera calculations when the player is standing still.")
                .define("enableCameraThrottle", true);
        NON_BLOCKING_PLAY = b
                .comment("Start sounds without making the game wait for the sound thread. Vanilla stops the frame until the sound thread hands back a channel for every sound that starts, which adds up when many mobs walk or fight around you.")
                .define("nonBlockingPlay", true);
        // Kept only so configs from FastSounds 0.5 / other ports load without being rewritten.
        b.comment("Deprecated: has no effect on 1.21.1 (channel stop checks are a plain field read there,", "so staggering them only delayed freeing channels). Kept for config compatibility.")
                .define("enableStaggeredStopped", false);
        b.comment("Deprecated: has no effect. Kept for config compatibility.")
                .defineInRange("staggeredTickInterval", 5, 1, 20);
        b.pop();

        b.push("whitelists");
        b.comment("Entries are matched as substrings of the full sound id, e.g. \"minecraft:entity.zombie.ambient\".");
        DISTANCE_CULL_WHITELIST = b.defineListAllowEmpty("distanceCullWhitelist", List.of(), () -> "", o -> o instanceof String);
        VOLUME_CULL_WHITELIST = b.defineListAllowEmpty("volumeCullWhitelist", List.of("ambientsounds"), () -> "", o -> o instanceof String);
        SAME_TICK_WHITELIST = b.defineListAllowEmpty("sameTickWhitelist", List.of("ambientsounds"), () -> "", o -> o instanceof String);
        COOLDOWN_WHITELIST = b.defineListAllowEmpty("cooldownWhitelist", List.of("ambientsounds"), () -> "", o -> o instanceof String);
        EVICTION_WHITELIST = b.defineListAllowEmpty("evictionWhitelist", List.of("ambientsounds"), () -> "", o -> o instanceof String);
        b.pop();

        SPEC = b.build();
    }

    private Config() {
    }

    static void onConfig(ModConfigEvent event) {
        if (event.getConfig().getSpec() != SPEC) {
            return;
        }
        enableDynamicMixing = ENABLE_DYNAMIC_MIXING.get();
        maxConcurrentSounds = MAX_CONCURRENT_SOUNDS.get();
        enableDistanceCull = ENABLE_DISTANCE_CULL.get();
        maxSoundDistance = MAX_SOUND_DISTANCE.get();
        enableVolumeCull = ENABLE_VOLUME_CULL.get();
        enableSameTickDedup = ENABLE_SAME_TICK_DEDUP.get();
        maxSameTickSounds = MAX_SAME_TICK_SOUNDS.get();
        enableCameraThrottle = ENABLE_CAMERA_THROTTLE.get();
        nonBlockingPlay = NON_BLOCKING_PLAY.get();
        distanceCullWhitelist = List.copyOf(DISTANCE_CULL_WHITELIST.get());
        volumeCullWhitelist = List.copyOf(VOLUME_CULL_WHITELIST.get());
        sameTickWhitelist = List.copyOf(SAME_TICK_WHITELIST.get());
        cooldownWhitelist = List.copyOf(COOLDOWN_WHITELIST.get());
        evictionWhitelist = List.copyOf(EVICTION_WHITELIST.get());
        // Whitelists may have changed: drop cached per-sound classifications.
        Fastsounds.OPTIMIZER.invalidateClassifications();
    }
}
