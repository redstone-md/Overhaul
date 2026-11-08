package dev.overhaul.minecraft.blaze.ui;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import net.minecraft.client.Minecraft;

/**
 * What the last benchmark measured for each preset and the preset it picked, kept in the config folder so the
 * quality page can show it after a restart.
 */
record BenchmarkResult(List<Stage> stages, QualityPreset recommended, long time) {
    /** A preset passes when it holds this average and its slowest frames stay above the low mark. */
    static final double TARGET_AVERAGE = 75, TARGET_LOW = 40;

    record Stage(QualityPreset preset, double average, double low) {
        boolean passes() {
            return average >= TARGET_AVERAGE && low >= TARGET_LOW;
        }
    }

    /** The highest preset that passed, or the lowest preset when none did. */
    static BenchmarkResult of(List<Stage> stages) {
        QualityPreset best = QualityPreset.ULTRA_LOW;
        for (Stage stage : stages) if (stage.passes() && stage.preset().ordinal() > best.ordinal()) best = stage.preset();
        return new BenchmarkResult(List.copyOf(stages), best, System.currentTimeMillis());
    }

    private static Path file() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("overhaul-benchmark.properties");
    }

    void save() {
        Properties values = new Properties();
        values.setProperty("recommended", recommended.name());
        values.setProperty("time", Long.toString(time));
        for (Stage stage : stages) {
            values.setProperty(stage.preset().name() + ".average", Double.toString(stage.average()));
            values.setProperty(stage.preset().name() + ".low", Double.toString(stage.low()));
        }
        try {
            Files.createDirectories(file().getParent());
            try (Writer writer = Files.newBufferedWriter(file())) {
                values.store(writer, "Overhaul benchmark");
            }
        } catch (IOException ignored) {
            // A result that cannot be kept still shows until the game closes.
        }
    }

    /** The saved result, or null when none was saved or the file no longer reads. */
    static BenchmarkResult load() {
        Path file = file();
        if (!Files.isRegularFile(file)) return null;
        Properties values = new Properties();
        try (Reader reader = Files.newBufferedReader(file)) {
            values.load(reader);
            List<Stage> stages = new ArrayList<>();
            for (QualityPreset preset : QualityPreset.values()) {
                String average = values.getProperty(preset.name() + ".average"), low = values.getProperty(preset.name() + ".low");
                if (average != null && low != null) stages.add(new Stage(preset, Double.parseDouble(average), Double.parseDouble(low)));
            }
            return new BenchmarkResult(stages, QualityPreset.valueOf(values.getProperty("recommended")), Long.parseLong(values.getProperty("time")));
        } catch (IOException | RuntimeException failure) {
            return null;
        }
    }
}
