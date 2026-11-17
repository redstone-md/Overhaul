package dev.overhaul.minecraft.blaze.ui;

import com.mojang.blaze3d.platform.Window;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.PrioritizeChunkUpdates;

/** The window, the frame rate and the camera: the game's own options that no quality preset writes. */
final class DisplayPage extends SettingsPage {
    private static final int UNLIMITED = 260;
    private final GameOptions game;
    private final Runnable vanilla;

    /** @param vanilla opens the game's own video settings */
    DisplayPage(GameOptions game, Runnable refresh, Runnable vanilla) {
        super("display", SettingsIcon.DISPLAY, refresh);
        this.game = game;
        this.vanilla = vanilla;
    }

    @Override
    List<SettingsRow> rows() {
        Options options = game.options;
        Minecraft minecraft = game.minecraft;
        List<SettingsRow> rows = new ArrayList<>();
        rows.add(new SettingsRow.Header(text("section.window")));
        rows.add(game.toggle(options.fullscreen(), "options.fullscreen", text("fullscreen.desc")));
        rows.add(game.toggle(options.enableVsync(), "options.vsync", text("vsync.desc")));
        rows.add(game.slider(options.framerateLimit(), "options.framerateLimit", text("limit.desc"), 10, UNLIMITED, 10,
                value -> value >= UNLIMITED ? text("unlimited") : text("fps", value)));
        int largest = minecraft.getWindow().calculateScale(0, minecraft.isEnforceUnicode());
        rows.add(new SettingsRow.Choice<>(GameOptions.name("options.guiScale"), text("scale.desc"), IntStream.rangeClosed(0, largest).boxed().toList(),
                game.value(options.guiScale()), value -> value == 0 ? text("auto") : value + "x"));
        OptionInstance<Integer> blur = BlazeUiTier.menuBlur(options);
        if (blur != null) rows.add(game.slider(blur, "options.accessibility.menu_background_blurriness", text("blur.desc"), 0, 10, 1, String::valueOf));
        rows.add(new SettingsRow.Header(text("section.camera")));
        rows.add(game.slider(options.fov(), "options.fov", text("fov.desc"), 30, 110, 1, value -> value + "°"));
        rows.add(game.percent(options.gamma(), "options.gamma", text("gamma.desc"), 0, 100, 5));
        rows.add(game.toggle(options.bobView(), "options.viewBobbing", text("bobbing.desc")));
        rows.add(new SettingsRow.Header(text("section.world")));
        rows.add(game.slider(options.simulationDistance(), "options.simulationDistance", text("simulation.desc"), 5, QualityPreset.maxDistance(), 1,
                value -> text("chunks", value)));
        rows.add(game.choice(options.prioritizeChunkUpdates(), "options.prioritizeChunkUpdates", text("updates.desc"), PrioritizeChunkUpdates.values()));
        rows.add(new SettingsRow.Header(text("section.game")));
        rows.add(new SettingsRow.Action(text("vanilla.name"), text("vanilla.desc"), SettingsIcon.WINDOW, () -> text("vanilla.open"), vanilla));
        return rows;
    }

    @Override
    String readout() {
        Window window = game.minecraft.getWindow();
        return window.getWidth() + " x " + window.getHeight();
    }
}
