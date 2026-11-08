package dev.overhaul.minecraft.blaze.ui;

import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * Runs the benchmark in its own world. Nothing covers the view but a small card at the foot of the screen with the
 * stage, the live frame rate and the progress; Escape stops the run, puts every option back and leaves the world.
 */
final class BenchmarkScreen extends BlazeScreenBase {
    private static final int CARD_WIDTH = 240, CARD_HEIGHT = 52;
    private final Consumer<BenchmarkResult> finished;
    private final Runnable leave;
    private BenchmarkRun run;
    private boolean left;

    /** @param leave closes the benchmark world; runs once, however the run ends */
    BenchmarkScreen(Consumer<BenchmarkResult> finished, Runnable leave) {
        super(Component.literal("Overhaul benchmark"));
        this.finished = finished;
        this.leave = leave;
    }

    @Override
    protected void init() {
        if (run == null) run = new BenchmarkRun(minecraft);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void backdrop(GuiGraphics graphics, float partial) {
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
        if (left) return;
        if (!run.frame()) {
            BenchmarkResult result = run.result();
            if (result != null) {
                result.save();
                finished.accept(result);
            }
            end();
            return;
        }
        int x = (width - CARD_WIDTH) / 2, y = height - SettingsTheme.GAP - CARD_HEIGHT;
        graphics.fill(x, y, x + CARD_WIDTH, y + CARD_HEIGHT, SettingsTheme.SIDEBAR);
        SettingsTheme.outline(graphics, x, y, CARD_WIDTH, CARD_HEIGHT, SettingsTheme.HAIRLINE);
        int left = x + SettingsTheme.PAD + 4, right = x + CARD_WIDTH - SettingsTheme.PAD - 4;
        graphics.drawString(font, run.preset().label, left, y + 10, SettingsTheme.TEXT, false);
        String stage = (run.stage() + 1) + " of " + run.stageCount();
        graphics.drawString(font, stage, left + font.width(run.preset().label) + 8, y + 10, SettingsTheme.TEXT_3, false);
        SettingsTheme.textRight(graphics, font, minecraft.getFps() + " fps", right, y + 6, 16, SettingsTheme.TEXT);
        graphics.drawString(font, run.measuring() ? "Timing every frame" : "Loading the view", left, y + 24, SettingsTheme.TEXT_2, false);
        SettingsTheme.textRight(graphics, font, "Esc to stop", right, y + 20, 16, SettingsTheme.TEXT_3);
        int bar = y + CARD_HEIGHT - 10;
        graphics.fill(left, bar, right, bar + 2, SettingsTheme.TRACK);
        graphics.fill(left, bar, left + (int) Math.round((right - left) * run.progress()), bar + 2, SettingsTheme.ACCENT);
    }

    @Override
    protected boolean scrolled(double amount) {
        return true;
    }

    @Override
    public void onClose() {
        end();
    }

    /** Any other screen taking over also ends the run, so the benchmark world never stays open behind it. */
    @Override
    public void removed() {
        end();
    }

    private void end() {
        if (run != null) run.restore();
        if (left) return;
        left = true;
        leave.run();
    }
}
