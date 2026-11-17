package dev.overhaul.minecraft.blaze.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.GraphicsStatus;
import net.minecraft.client.Options;
import net.minecraft.client.ParticleStatus;

/**
 * A preset from Ultra low to Ultra, and under it every option a preset writes, so a setup changed by hand shows as
 * custom with each value in view.
 */
final class QualityPage extends SettingsPage {
    private final GameOptions game;
    private final Options options;
    private final Supplier<BenchmarkResult> benchmark;

    /** @param benchmark the last benchmark's result, or null when none ran */
    QualityPage(GameOptions game, Runnable refresh, Supplier<BenchmarkResult> benchmark) {
        super("quality", SettingsIcon.QUALITY, refresh);
        this.game = game;
        this.options = game.options;
        this.benchmark = benchmark;
    }

    @Override
    List<SettingsRow> rows() {
        QualityPreset[] presets = QualityPreset.values();
        List<SettingsRow> rows = new ArrayList<>();
        rows.add(new SettingsRow.Header(text("section.preset")));
        rows.add(new SettingsRow.Choice<>(text("preset.name"), text("preset.desc"), IntStream.range(0, presets.length).boxed().toList(), new Preset(),
                index -> index < 0 ? text("custom") : presets[index].label())
                .note(() -> staged() == null ? null : staged().description()));
        rows.add(new SettingsRow.Action(text("recommended.name"), text("recommended.desc"), SettingsIcon.CHECK,
                () -> recommended() == null ? text("recommended.none") : recommended().label(), () -> recommended().apply(options, game, true))
                .enabledWhen(() -> recommended() != null && !recommended().matches(options, game)));
        rows.add(new SettingsRow.Header(text("section.detail")));
        rows.add(game.slider(options.renderDistance(), "options.renderDistance", text("distance.desc"), 2, QualityPreset.maxDistance(), 1, value -> text("chunks", value)));
        rows.add(game.choice(options.graphicsMode(), "options.graphics", text("graphics.desc"), GraphicsStatus.values()));
        rows.add(game.choice(options.cloudStatus(), "options.renderClouds", text("clouds.desc"), CloudStatus.values()));
        rows.add(game.toggle(options.ambientOcclusion(), "options.ao", text("ao.desc")));
        rows.add(game.choice(options.particles(), "options.particles", text("particles.desc"), ParticleStatus.values()));
        rows.add(game.slider(options.biomeBlendRadius(), "options.biomeBlendRadius", text("blend.desc"), 0, 7, 1,
                value -> value == 0 ? OverhaulLang.get("overhaul.ui.off") : (2 * value + 1) + "x" + (2 * value + 1)));
        rows.add(game.toggle(options.entityShadows(), "options.entityShadows", text("shadows.desc")));
        rows.add(game.percent(options.entityDistanceScaling(), "options.entityDistanceScaling", text("entities.desc"), 50, 500, 25));
        rows.add(game.slider(options.mipmapLevels(), "options.mipmapLevels", text("mipmaps.desc"), 0, 4, 1,
                value -> value == 0 ? OverhaulLang.get("overhaul.ui.off") : String.valueOf(value)));
        return rows;
    }

    private QualityPreset staged() {
        return QualityPreset.current(options, game);
    }

    private QualityPreset recommended() {
        BenchmarkResult result = benchmark.get();
        return result == null ? null : result.recommended();
    }

    @Override
    String readout() {
        QualityPreset preset = staged();
        return preset == null ? text("custom") : preset.label();
    }

    /** The preset as an index into the presets, or -1 for a setup no preset describes. */
    private final class Preset implements SettingsDraft.Setting<Integer> {
        @Override
        public Integer get() {
            QualityPreset preset = staged();
            return preset == null ? -1 : preset.ordinal();
        }

        @Override
        public void set(Integer index) {
            QualityPreset.values()[index].apply(options, game, true);
        }

        @Override
        public boolean changed() {
            return staged() != QualityPreset.current(options);
        }
    }
}
