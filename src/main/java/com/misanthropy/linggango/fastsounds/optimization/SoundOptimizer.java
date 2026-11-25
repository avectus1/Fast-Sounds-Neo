package com.misanthropy.linggango.fastsounds.optimization;

import java.util.List;
import java.util.Set;

import com.misanthropy.linggango.fastsounds.Config;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ByteOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.Util;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;

/**
 * Decides which sounds start and which playing sound to replace when the
 * channel pool is full. Same policy as FastSounds 0.5; differences from the
 * original/other ports are performance or correctness only:
 * <ul>
 *   <li>Whitelist and "critical sound" substring checks are computed once per
 *       sound id and cached, instead of allocating a String and scanning every
 *       list for every sound played (and for every candidate during eviction).</li>
 *   <li>Distances are measured from the camera (the actual audio listener),
 *       falling back to the player.</li>
 *   <li>Monotonic clock (Util.getMillis) instead of wall-clock time.</li>
 *   <li>Floor instead of truncation for the dedup grid, so -0.5 and 0.5 no
 *       longer share a cell.</li>
 * </ul>
 * All methods are called from the render thread, except
 * {@link #invalidateClassifications()} which may come from the config watcher.
 */
public final class SoundOptimizer {
    private static final Set<String> CRITICAL_SOUNDS = Set.of("player", "note_block", "creeper", "explode", "chest", "tnt", "portal");

    private static final byte KNOWN = 1;
    private static final byte CRITICAL = 1 << 1;
    private static final byte VOLUME_EXEMPT = 1 << 2;
    private static final byte DISTANCE_EXEMPT = 1 << 3;
    private static final byte COOLDOWN_EXEMPT = 1 << 4;
    private static final byte SAME_TICK_EXEMPT = 1 << 5;
    private static final byte EVICTION_EXEMPT = 1 << 6;

    private static final long CLEANUP_INTERVAL_MS = 2000L;
    private static final long ENTRY_LIFETIME_MS = 1000L;

    private final Long2LongOpenHashMap recentSounds = new Long2LongOpenHashMap();
    private final Object2IntOpenHashMap<ResourceLocation> tickSoundCounts = new Object2IntOpenHashMap<>();
    private final Object2ByteOpenHashMap<ResourceLocation> classifications = new Object2ByteOpenHashMap<>();
    private long lastCleanupTime;
    private long lastGameTick = -1L;

    public SoundOptimizer() {
        recentSounds.defaultReturnValue(0L);
        tickSoundCounts.defaultReturnValue(0);
        classifications.defaultReturnValue((byte) 0);
    }

    public synchronized void reset() {
        recentSounds.clear();
        tickSoundCounts.clear();
        lastCleanupTime = 0L;
        lastGameTick = -1L;
    }

    public synchronized void invalidateClassifications() {
        classifications.clear();
    }

    /** Periodically drops cooldown entries older than a second. */
    public synchronized void recordSoundProcessed() {
        long now = Util.getMillis();
        if (now - lastCleanupTime > CLEANUP_INTERVAL_MS) {
            long threshold = now - ENTRY_LIFETIME_MS;
            recentSounds.long2LongEntrySet().removeIf(e -> e.getLongValue() < threshold);
            lastCleanupTime = now;
        }
    }

    public synchronized boolean shouldPlaySound(SoundInstance sound) {
        if (sound == null) {
            return false;
        }
        SoundSource source = sound.getSource();
        if (isUntouchableSource(source) || sound.isRelative() || sound.getAttenuation() == SoundInstance.Attenuation.NONE) {
            return true;
        }

        ResourceLocation id = sound.getLocation();
        byte flags = classify(id);
        if ((flags & CRITICAL) != 0) {
            return true;
        }

        if (Config.enableVolumeCull && sound.getVolume() < 0.05F && (flags & VOLUME_EXEMPT) == 0) {
            return false;
        }

        double x = sound.getX();
        double y = sound.getY();
        double z = sound.getZ();

        if (Config.enableDistanceCull && (flags & DISTANCE_EXEMPT) == 0) {
            double distSqr = distanceSqrToListener(x, y, z);
            double max = Config.maxSoundDistance;
            if (distSqr > max * max) {
                return false;
            }
        }

        if (!isDedupSource(source)) {
            return true;
        }

        // 4x4 block column grid, same granularity as the original.
        int cellX = Mth.floor(x) >> 2;
        int cellZ = Mth.floor(z) >> 2;
        long key = (long) id.hashCode() * -7046029254386353131L
                ^ (long) cellX * 1099511628211L
                ^ (long) cellZ * -4417276706812531889L;

        long now = Util.getMillis();
        long last = recentSounds.get(key);
        if (last != 0L && now - last < cooldownFor(source) && (flags & COOLDOWN_EXEMPT) == 0) {
            return false;
        }

        if (Config.enableSameTickDedup && (flags & SAME_TICK_EXEMPT) == 0 && refreshTickIfNeeded()) {
            int count = tickSoundCounts.getInt(id);
            if (count >= Config.maxSameTickSounds) {
                return false;
            }
            tickSoundCounts.put(id, count + 1);
        }

        recentSounds.put(key, now);
        return true;
    }

    /**
     * Picks a playing sound to stop so {@code incoming} can take its channel,
     * or returns null if the incoming sound should be dropped instead.
     */
    public synchronized SoundInstance getSoundToEvict(Iterable<SoundInstance> playing, Set<SoundInstance> pending, SoundInstance incoming) {
        Listener listener = Listener.current();

        double farthestDist = -1.0;
        SoundInstance farthest = null;
        int lowestPriority = Integer.MAX_VALUE;
        SoundInstance lowest = null;

        for (SoundInstance candidate : playing) {
            if (candidate == null || pending.contains(candidate) || candidate.isRelative()
                    || candidate.getAttenuation() == SoundInstance.Attenuation.NONE) {
                continue;
            }
            SoundSource source = candidate.getSource();
            if (isUntouchableSource(source) || (classify(candidate.getLocation()) & EVICTION_EXEMPT) != 0) {
                continue;
            }
            if (listener != null) {
                double d = listener.distanceSqr(candidate.getX(), candidate.getY(), candidate.getZ());
                if (d > farthestDist) {
                    farthestDist = d;
                    farthest = candidate;
                }
            } else {
                int p = priority(source);
                if (p < lowestPriority) {
                    lowestPriority = p;
                    lowest = candidate;
                }
            }
        }

        if (listener != null) {
            double incomingDist = incoming != null && !incoming.isRelative() && incoming.getAttenuation() != SoundInstance.Attenuation.NONE
                    ? listener.distanceSqr(incoming.getX(), incoming.getY(), incoming.getZ())
                    : -1.0;
            return farthest != null && farthestDist > incomingDist ? farthest : null;
        }
        int incomingPriority = incoming != null ? priority(incoming.getSource()) : Integer.MAX_VALUE;
        return lowest != null && lowestPriority < incomingPriority ? lowest : null;
    }

    private byte classify(ResourceLocation id) {
        byte flags = classifications.getByte(id);
        if (flags != 0) {
            return flags;
        }
        String path = id.getPath();
        String full = id.toString();
        flags = KNOWN;
        for (String s : CRITICAL_SOUNDS) {
            if (path.contains(s)) {
                flags |= CRITICAL;
                break;
            }
        }
        if (matches(full, Config.volumeCullWhitelist)) flags |= VOLUME_EXEMPT;
        if (matches(full, Config.distanceCullWhitelist)) flags |= DISTANCE_EXEMPT;
        if (matches(full, Config.cooldownWhitelist)) flags |= COOLDOWN_EXEMPT;
        if (matches(full, Config.sameTickWhitelist)) flags |= SAME_TICK_EXEMPT;
        if (matches(full, Config.evictionWhitelist)) flags |= EVICTION_EXEMPT;
        classifications.put(id, flags);
        return flags;
    }

    private static boolean matches(String id, List<? extends String> list) {
        if (list == null) {
            return false;
        }
        for (String s : list) {
            if (!s.isEmpty() && id.contains(s)) {
                return true;
            }
        }
        return false;
    }

    private boolean refreshTickIfNeeded() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            tickSoundCounts.clear();
            return false;
        }
        long tick = level.getGameTime();
        if (tick != lastGameTick) {
            tickSoundCounts.clear();
            lastGameTick = tick;
        }
        return true;
    }

    private static double distanceSqrToListener(double x, double y, double z) {
        Listener l = Listener.current();
        return l == null ? 0.0 : l.distanceSqr(x, y, z);
    }

    private static boolean isUntouchableSource(SoundSource s) {
        return s == SoundSource.MASTER || s == SoundSource.MUSIC || s == SoundSource.RECORDS || s == SoundSource.VOICE;
    }

    private static boolean isDedupSource(SoundSource s) {
        return s == SoundSource.HOSTILE || s == SoundSource.NEUTRAL || s == SoundSource.PLAYERS
                || s == SoundSource.BLOCKS || s == SoundSource.AMBIENT || s == SoundSource.WEATHER;
    }

    private static long cooldownFor(SoundSource s) {
        return s == SoundSource.AMBIENT || s == SoundSource.WEATHER ? 200L : 100L;
    }

    private static int priority(SoundSource s) {
        if (s == null) {
            return 5;
        }
        return switch (s) {
            case AMBIENT -> 0;
            case WEATHER -> 1;
            case NEUTRAL -> 2;
            case BLOCKS -> 3;
            case HOSTILE -> 4;
            case PLAYERS -> 5;
            default -> 10;
        };
    }

    /** Where the audio listener is: the camera if it is set up, else the player. */
    private record Listener(double x, double y, double z) {
        static Listener current() {
            Minecraft mc = Minecraft.getInstance();
            Camera camera = mc.gameRenderer.getMainCamera();
            if (camera.isInitialized()) {
                var p = camera.getPosition();
                return new Listener(p.x, p.y, p.z);
            }
            Player player = mc.player;
            return player == null ? null : new Listener(player.getX(), player.getY(), player.getZ());
        }

        double distanceSqr(double px, double py, double pz) {
            double dx = x - px, dy = y - py, dz = z - pz;
            return dx * dx + dy * dy + dz * dz;
        }
    }
}
