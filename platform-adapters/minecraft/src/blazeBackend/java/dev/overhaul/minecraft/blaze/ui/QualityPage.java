package dev.overhaul.minecraft.blaze.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Presets from Ultra low to Ultra, and the benchmark that measures each of them on this machine and names the
 * highest one it can hold. Presets write the video page's options, so the video page shows exactly what one set.
 */
final class QualityPage extends SettingsPage {
    private final Minecraft minecraft;
    private final Function<Consumer<BenchmarkResult>, String> benchmark;
    private BenchmarkResult result = BenchmarkResult.load();
    private String problem;

    /**
     * @param benchmark starts a run that hands its result to the given callback when every stage is measured; returns
     *                  why it cannot start, or null
     */
    QualityPage(Minecraft minecraft, Runnable refresh, Function<Consumer<BenchmarkResult>, String> benchmark) {
        super(refresh);
        this.minecraft = minecraft;
        this.benchmark = benchmark;
    }

    @Override
    String title() {
        return "Quality";
    }

    @Override
    List<SettingsRow> rows() {
        List<SettingsRow> rows = new ArrayList<>();
        rows.add(new SettingsRow.Header("Presets"));
        for (int i = QualityPreset.values().length - 1; i >= 0; i--) {
            QualityPreset preset = QualityPreset.values()[i];
            rows.add(new SettingsRow.Action(Component.literal(preset.label), preset.description + ". View distance " + preset.renderDistance() + " chunks",
                    () -> preset.apply(minecraft.options, true), () -> result != null && result.recommended() == preset ? "Recommended" : "")
                    .selectedWhen(() -> preset.matches(minecraft.options)));
        }
        rows.add(new SettingsRow.Header("Benchmark"));
        long seconds = BenchmarkRun.seconds();
        rows.add(new SettingsRow.Action(Component.literal("Run benchmark"),
                "Creates a test world with a fixed seed and tries every preset in it from Ultra down. The camera turns while each one loads its view, then turns again while every frame is timed under a storm of particles. Takes about "
                        + seconds + " seconds plus the world's creation; the world is deleted and your settings come back afterwards",
                this::start, () -> menu() ? seconds + " s" : "")
                .enabledWhen(this::menu)
                .note(() -> problem != null ? problem : menu() ? "" : "Runs from the main menu only; leave the world first"));
        rows.add(new BenchmarkChartRow(() -> result));
        if (result != null) {
            QualityPreset picked = result.recommended();
            rows.add(new SettingsRow.Action(Component.literal("Use " + picked.label), "Applies the preset the last benchmark recommended",
                    () -> picked.apply(minecraft.options, true), () -> picked.matches(minecraft.options) ? "In use" : "")
                    .enabledWhen(() -> !picked.matches(minecraft.options)));
        }
        return rows;
    }

    private boolean menu() {
        return minecraft.level == null;
    }

    void start() {
        problem = benchmark.apply(this::finished);
        refresh.run();
    }

    void finished(BenchmarkResult finished) {
        result = finished;
        refresh.run();
    }

    @Override
    String status() {
        QualityPreset current = QualityPreset.current(minecraft.options);
        return current == null ? "Custom settings; the video page shows every value" : "Using the " + current.label + " preset";
    }
}
