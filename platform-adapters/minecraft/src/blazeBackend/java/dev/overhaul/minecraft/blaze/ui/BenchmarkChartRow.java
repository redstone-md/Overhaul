package dev.overhaul.minecraft.blaze.ui;

import java.text.DateFormat;
import java.util.Date;
import java.util.function.Supplier;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * The last benchmark as one bar per preset: the bar is the average frame rate, the tick on it the slowest one percent
 * of frames. The recommended preset is the only bar in the accent; a hairline marks the average a preset must hold.
 */
final class BenchmarkChartRow extends SettingsRow {
    private static final int BAR = 14, LABEL = 56, FIGURES = 64;
    private final Supplier<BenchmarkResult> result;

    BenchmarkChartRow(Supplier<BenchmarkResult> result) {
        super(OverhaulLang.get("overhaul.benchmark.chart.name"),
                OverhaulLang.get("overhaul.benchmark.chart.desc", (int) BenchmarkResult.TARGET_AVERAGE, (int) BenchmarkResult.TARGET_LOW));
        this.result = result;
    }

    @Override
    int height() {
        BenchmarkResult last = result.get();
        return last == null ? 0 : 20 + last.stages().size() * BAR + 8;
    }

    @Override
    boolean interactive() {
        return false;
    }

    @Override
    void render(GuiGraphics graphics, Font font, int x, int y, int width, boolean hovered, int mouseX, int mouseY) {
        BenchmarkResult last = result.get();
        if (last == null) return;
        String when = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(last.time()));
        graphics.drawString(font, when, x + SettingsTheme.PAD, y + 6, SettingsTheme.FAINT, false);
        double top = 0;
        for (BenchmarkResult.Stage stage : last.stages()) top = Math.max(top, stage.average());
        top = Math.max(top, BenchmarkResult.TARGET_AVERAGE) * 1.1;
        int left = x + SettingsTheme.PAD + LABEL, right = x + width - SettingsTheme.PAD - FIGURES, span = Math.max(1, right - left);
        int row = y + 20;
        for (int i = last.stages().size() - 1; i >= 0; i--) {
            BenchmarkResult.Stage stage = last.stages().get(i);
            boolean picked = stage.preset() == last.recommended();
            SettingsTheme.text(graphics, font, SettingsTheme.clip(font, stage.preset().label(), LABEL - 4), x + SettingsTheme.PAD, row, BAR,
                    picked ? SettingsTheme.TEXT : SettingsTheme.MUTED);
            int end = left + (int) Math.round(span * Math.min(1, stage.average() / top));
            graphics.fill(left, row + 4, end, row + BAR - 4, picked ? SettingsTheme.ACCENT : stage.passes() ? SettingsTheme.MUTED : SettingsTheme.FIELD_HOVER);
            int tick = left + (int) Math.round(span * Math.min(1, stage.low() / top));
            graphics.fill(tick, row + 2, tick + 1, row + BAR - 2, SettingsTheme.TEXT);
            SettingsTheme.textRight(graphics, font, Math.round(stage.average()) + " / " + Math.round(stage.low()), x + width - SettingsTheme.PAD, row, BAR,
                    picked ? SettingsTheme.TEXT : SettingsTheme.FAINT);
            row += BAR;
        }
        int target = left + (int) Math.round(span * BenchmarkResult.TARGET_AVERAGE / top);
        graphics.fill(target, y + 18, target + 1, row, SettingsTheme.HAIRLINE);
    }

    @Override
    void control(GuiGraphics graphics, Font font, int x, int y, int width, int height, boolean enabled, int mouseX) {
    }
}
