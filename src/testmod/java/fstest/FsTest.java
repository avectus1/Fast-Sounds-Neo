package fstest;

import java.util.ArrayList;
import java.util.List;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

@Mod(value = "fstest", dist = Dist.CLIENT)
public final class FsTest {
    private static final Logger LOG = LogUtils.getLogger();
    private static final RandomSource RNG = RandomSource.create(1);

    private boolean worldRequested;
    private int inWorldTicks;
    private int failures;
    private final List<SoundInstance> burst = new ArrayList<>();

    public FsTest() {
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, e -> tick());
    }

    private void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            if (!worldRequested && mc.screen instanceof TitleScreen) {
                worldRequested = true;
                LOG.info("[FSTEST] sound engine: {}", mc.getSoundManager().getDebugString());
                LevelSettings settings = new LevelSettings("fstest", GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
                        new GameRules(), WorldDataConfiguration.DEFAULT);
                mc.createWorldOpenFlows().createFreshLevel("fstest" + System.currentTimeMillis(), settings,
                        new WorldOptions(1L, false, false), WorldPresets::createNormalWorldDimensions, mc.screen);
            }
            return;
        }
        if (mc.screen != null) {
            mc.setScreen(null);
        }
        inWorldTicks++;
        double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();

        switch (inWorldTicks) {
            case 60 -> {
                LOG.info("[FSTEST] in world, paused={} engine={}", mc.isPaused(), mc.getSoundManager().getDebugString());
                check("near hostile sound plays", play(SoundEvents.ZOMBIE_AMBIENT.getLocation(), SoundSource.HOSTILE, 1f, px + 2, py, pz), true);
                check("far (100 blocks) sound is culled", play(SoundEvents.ZOMBIE_HURT.getLocation(), SoundSource.HOSTILE, 1f, px + 100, py, pz), false);
                check("quiet (0.01 vol) sound is culled", play(SoundEvents.COW_AMBIENT.getLocation(), SoundSource.NEUTRAL, 0.01f, px + 2, py, pz), false);
                check("UI sound (no attenuation) always plays even if quiet",
                        playInstance(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1f, 0.01f)), true);
            }
            case 70 -> {
                ResourceLocation id = SoundEvents.PIG_AMBIENT.getLocation();
                check("cooldown: first copy plays", play(id, SoundSource.NEUTRAL, 1f, px + 3, py, pz), true);
                check("cooldown: same spot same moment blocked", play(id, SoundSource.NEUTRAL, 1f, px + 3, py, pz), false);
            }
            case 80 -> {
                ResourceLocation id = SoundEvents.SKELETON_AMBIENT.getLocation();
                int ok = 0;
                for (int i = 0; i < 6; i++) {
                    if (play(id, SoundSource.HOSTILE, 1f, px + 8 * i - 20, py, pz + 12)) ok++;
                }
                check("same-tick dedup: 3 of 6 copies in different spots play", ok == 3, true);
            }
            case 90 -> {
                ResourceLocation id = SoundEvents.PLAYER_HURT.getLocation();
                int ok = 0;
                for (int i = 0; i < 5; i++) {
                    if (play(id, SoundSource.PLAYERS, 1f, px + 1, py, pz)) ok++;
                }
                check("critical (player) sounds bypass cooldown/dedup: 5 of 5", ok == 5, true);
            }
            case 100 -> {
                // Burst of 300 distinct sounds, far to near, to exercise the channel cap and eviction.
                List<ResourceLocation> ids = new ArrayList<>();
                for (ResourceLocation id : BuiltInRegistries.SOUND_EVENT.keySet()) {
                    String p = id.getPath();
                    if (p.contains("music") || p.contains("player") || p.contains("note_block") || p.contains("creeper")
                            || p.contains("explode") || p.contains("chest") || p.contains("tnt") || p.contains("portal")) continue;
                    ids.add(id);
                    if (ids.size() == 300) break;
                }
                int n = ids.size();
                long t0 = System.nanoTime();
                for (int i = 0; i < n; i++) {
                    double r = 60.0 - i * (55.0 / n);
                    double a = i * 2.39996;
                    SoundInstance s = new SimpleSoundInstance(ids.get(i), SoundSource.BLOCKS, 1f, 1f, RNG, false, 0,
                            SoundInstance.Attenuation.LINEAR, px + Math.cos(a) * r, py, pz + Math.sin(a) * r, false);
                    mc.getSoundManager().play(s);
                    burst.add(s);
                }
                long us = (System.nanoTime() - t0) / 1000;
                int got = 0, empty = 0;
                for (SoundInstance s : burst) {
                    if (mc.getSoundManager().isActive(s)) got++;
                    else if (s.getSound() != null && s.getSound().getLocation().getPath().equals("empty")) empty++;
                    else LOG.info("[FSTEST] no channel: {}", s.getLocation());
                }
                n -= empty;
                LOG.info("[FSTEST] burst of {} sounds took {} us; {} got a channel; engine={}", n, us, got, mc.getSoundManager().getDebugString());
                check("every burst sound got a handle", got == n, true);
            }
            case 102 -> {
                String dbg = mc.getSoundManager().getDebugString();
                int active = Integer.parseInt(dbg.substring(dbg.indexOf(':') + 1, dbg.indexOf('/')).trim());
                LOG.info("[FSTEST] after burst engine={}", dbg);
                check("channel cap respected (<= 230 static + a few slack)", active <= 235, true);
                check("nearest burst sound is playing", mc.getSoundManager().isActive(burst.get(burst.size() - 1)), true);
            }
            case 110 -> {
                // Camera throttle: listener update path ran for many frames without errors; force a reload too.
                LOG.info("[FSTEST] reloading sound engine (destroy/init path)");
                mc.getSoundManager().reload();
            }
            case 160 -> {
                check("sounds still play after engine reload", play(SoundEvents.ZOMBIE_AMBIENT.getLocation(), SoundSource.HOSTILE, 1f, px + 2, py, pz), true);
                LOG.info("[FSTEST] DONE failures={}", failures);
                mc.stop();
            }
            default -> {
            }
        }
    }

    private boolean play(ResourceLocation id, SoundSource src, float vol, double x, double y, double z) {
        return playInstance(new SimpleSoundInstance(id, src, vol, 1f, RNG, false, 0, SoundInstance.Attenuation.LINEAR, x, y, z, false));
    }

    private boolean playInstance(SoundInstance s) {
        Minecraft.getInstance().getSoundManager().play(s);
        return Minecraft.getInstance().getSoundManager().isActive(s);
    }

    private void check(String name, boolean actual, boolean expected) {
        boolean pass = actual == expected;
        if (!pass) failures++;
        LOG.info("[FSTEST] {} {}", pass ? "PASS" : "FAIL", name);
    }
}
