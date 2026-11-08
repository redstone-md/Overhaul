package dev.overhaul.minecraft.blaze.ui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.GraphicsStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.ParticleStatus;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;

/**
 * Measures every preset from Ultra down in the benchmark world. Each stage first turns the camera once around while the
 * new view distance loads, then turns it again while it times every frame under a storm of particles. The frame limit,
 * vsync and HUD are lifted for the run and put back afterwards with the player's own options and view.
 */
final class BenchmarkRun {
    /** The first stage settles longer, while the fresh world generates the land around the spawn. */
    static final long WARMUP = 24_000_000_000L, SETTLE = 8_000_000_000L, MEASURE = 12_000_000_000L;
    private static final int PARTICLES = 96;
    private static final ParticleOptions[] STORM = {ParticleTypes.CLOUD, ParticleTypes.FLAME, ParticleTypes.CAMPFIRE_COSY_SMOKE, ParticleTypes.END_ROD};
    private final Minecraft minecraft;
    private final Options options;
    private final List<QualityPreset> order = highestFirst();
    private final List<BenchmarkResult.Stage> stages = new ArrayList<>();
    private final RandomSource random = RandomSource.create();
    private final Snapshot snapshot;
    private int stage = -1;
    private long phaseStart, last;
    private boolean measuring, restored;
    private long[] frames = new long[8192];
    private int count;

    private record Snapshot(int renderDistance, int simulationDistance, GraphicsStatus graphics, CloudStatus clouds, boolean ambientOcclusion,
                            ParticleStatus particles, int biomeBlend, boolean entityShadows, double entityDistance, int framerate, boolean vsync,
                            boolean hideGui, float yaw, float pitch) {
    }

    BenchmarkRun(Minecraft minecraft) {
        this.minecraft = minecraft;
        this.options = minecraft.options;
        LocalPlayer player = minecraft.player;
        snapshot = new Snapshot(options.renderDistance().get(), options.simulationDistance().get(), options.graphicsMode().get(), options.cloudStatus().get(),
                options.ambientOcclusion().get(), options.particles().get(), options.biomeBlendRadius().get(), options.entityShadows().get(),
                options.entityDistanceScaling().get(), options.framerateLimit().get(), options.enableVsync().get(), options.hideGui,
                player == null ? 0 : player.getYRot(), player == null ? 0 : player.getXRot());
        options.framerateLimit().set(260);
        options.enableVsync().set(false);
        options.hideGui = true;
        next(System.nanoTime());
    }

    /** Called once per rendered frame; false once every stage is measured or the world is gone. */
    boolean frame() {
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null) return false;
        long now = System.nanoTime();
        long length = phaseLength();
        double fraction = Math.min(1, (now - phaseStart) / (double) length);
        turn(player, snapshot.yaw() + 360f * (float) fraction);
        if (measuring) {
            record(now - last);
            storm(player);
        }
        last = now;
        if (now - phaseStart < length) return true;
        if (!measuring) {
            measuring = true;
            phaseStart = now;
            count = 0;
            return true;
        }
        stages.add(measure(order.get(stage)));
        return next(now);
    }

    private static List<QualityPreset> highestFirst() {
        List<QualityPreset> order = new ArrayList<>(Arrays.asList(QualityPreset.values()));
        java.util.Collections.reverse(order);
        return order;
    }

    private boolean next(long now) {
        if (++stage >= order.size()) return false;
        order.get(stage).apply(options, false);
        measuring = false;
        phaseStart = now;
        last = now;
        return true;
    }

    private void turn(LocalPlayer player, float yaw) {
        player.setYRot(yaw);
        player.yRotO = yaw;
        player.setXRot(0);
        player.xRotO = 0;
    }

    private void record(long nanos) {
        if (count == frames.length) frames = Arrays.copyOf(frames, count * 2);
        frames[count++] = nanos;
    }

    /** Client-side particles around the player; the particle option of the stage thins them out as the game does. */
    private void storm(LocalPlayer player) {
        for (int i = 0; i < PARTICLES; i++) {
            double x = player.getX() + (random.nextDouble() - 0.5) * 24, y = player.getY() + random.nextDouble() * 8, z = player.getZ() + (random.nextDouble() - 0.5) * 24;
            minecraft.level.addParticle(STORM[i % STORM.length], x, y, z, 0, 0.02, 0);
        }
    }

    private BenchmarkResult.Stage measure(QualityPreset preset) {
        if (count == 0) return new BenchmarkResult.Stage(preset, 0, 0);
        long[] sorted = Arrays.copyOf(frames, count);
        Arrays.sort(sorted);
        long total = 0;
        for (long nanos : sorted) total += nanos;
        long slow = sorted[Math.min(count - 1, (int) (count * 0.99))];
        return new BenchmarkResult.Stage(preset, count * 1e9 / Math.max(1, total), 1e9 / Math.max(1, slow));
    }

    QualityPreset preset() {
        return order.get(Math.min(stage, order.size() - 1));
    }

    int stage() {
        return Math.min(stage, order.size() - 1);
    }

    int stageCount() {
        return order.size();
    }

    boolean measuring() {
        return measuring;
    }

    private long phaseLength() {
        return measuring ? MEASURE : stage == 0 ? WARMUP : SETTLE;
    }

    /** The whole run in seconds. */
    static long seconds() {
        return (WARMUP - SETTLE + QualityPreset.values().length * (SETTLE + MEASURE)) / 1_000_000_000L;
    }

    /** How far the whole run has come, from 0 to 1. */
    double progress() {
        int at = stage();
        long before = at == 0 ? 0 : WARMUP + MEASURE + (at - 1) * (SETTLE + MEASURE);
        long within = Math.min(System.nanoTime() - phaseStart, phaseLength()) + (measuring ? (at == 0 ? WARMUP : SETTLE) : 0);
        return Math.min(1, (before + within) / (seconds() * 1e9));
    }

    /** Null until every stage is measured. */
    BenchmarkResult result() {
        return stages.size() == order.size() ? BenchmarkResult.of(stages) : null;
    }

    /** Puts back the player's options and view; safe to call more than once. */
    void restore() {
        if (restored) return;
        restored = true;
        options.renderDistance().set(snapshot.renderDistance());
        options.simulationDistance().set(snapshot.simulationDistance());
        if (options.graphicsMode().get() != snapshot.graphics()) options.graphicsMode().set(snapshot.graphics());
        options.cloudStatus().set(snapshot.clouds());
        if (options.ambientOcclusion().get() != snapshot.ambientOcclusion()) options.ambientOcclusion().set(snapshot.ambientOcclusion());
        options.particles().set(snapshot.particles());
        if (options.biomeBlendRadius().get() != snapshot.biomeBlend()) options.biomeBlendRadius().set(snapshot.biomeBlend());
        options.entityShadows().set(snapshot.entityShadows());
        options.entityDistanceScaling().set(snapshot.entityDistance());
        options.framerateLimit().set(snapshot.framerate());
        options.enableVsync().set(snapshot.vsync());
        options.hideGui = snapshot.hideGui();
        LocalPlayer player = minecraft.player;
        if (player != null) {
            player.setYRot(snapshot.yaw());
            player.yRotO = snapshot.yaw();
            player.setXRot(snapshot.pitch());
            player.xRotO = snapshot.pitch();
        }
    }
}
