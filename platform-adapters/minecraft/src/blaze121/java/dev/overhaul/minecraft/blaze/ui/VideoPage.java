package dev.overhaul.minecraft.blaze.ui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.GraphicsStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.ParticleStatus;
import net.minecraft.client.PrioritizeChunkUpdates;
import net.minecraft.network.chat.Component;
import net.minecraft.util.OptionEnum;

/** The game's own video options, written straight to {@link Options} and saved when the screen closes. */
final class VideoPage extends SettingsPage {
    private final Minecraft minecraft;
    private final Options options;
    private final int mipmaps;

    VideoPage(Minecraft minecraft, Runnable refresh) {
        super(refresh);
        this.minecraft = minecraft;
        this.options = minecraft.options;
        this.mipmaps = options.mipmapLevels().get();
    }

    @Override
    String title() {
        return "Video";
    }

    @Override
    List<SettingsRow> rows() {
        int distance = Runtime.getRuntime().maxMemory() >= 1_000_000_000L ? 32 : 16;
        List<SettingsRow> rows = new ArrayList<>();
        rows.add(new SettingsRow.Header("Display"));
        rows.add(slider(options.renderDistance(), "options.renderDistance", "How far terrain is drawn, in chunks. The largest cost on the GPU and on chunk building", 2, distance, 1, v -> v + " chunks"));
        rows.add(slider(options.simulationDistance(), "options.simulationDistance", "How far from you entities move and blocks tick. Costs CPU on the server side", 5, distance, 1, v -> v + " chunks"));
        rows.add(slider(options.framerateLimit(), "options.framerateLimit", "The highest frame rate the game aims for. 260 removes the limit", 10, 260, 10, v -> v >= 260 ? "Unlimited" : v + " fps"));
        rows.add(toggle(options.enableVsync(), "options.vsync", "Waits for the display before showing each frame. Removes tearing, caps the frame rate at the refresh rate"));
        rows.add(toggle(options.fullscreen(), "options.fullscreen", "Fills the whole display"));
        int largest = minecraft.getWindow().calculateScale(0, minecraft.isEnforceUnicode());
        rows.add(new SettingsRow.Choice<>(Component.translatable("options.guiScale"), "Size of menus and the HUD. Auto picks the largest that fits",
                IntStream.rangeClosed(0, largest).boxed().toList(), options.guiScale()::get,
                value -> { options.guiScale().set(value); minecraft.resizeDisplay(); }, value -> value == 0 ? "Auto" : value + "x"));
        rows.add(slider(options.fov(), "options.fov", "Field of view in degrees", 30, 110, 1, v -> v + "°"));
        rows.add(new SettingsRow.Slider(Component.translatable("options.gamma"), "How bright dark places look", 0, 100, 5,
                () -> (int) Math.round(options.gamma().get() * 100), value -> options.gamma().set(value / 100.0), v -> v + "%"));
        rows.add(toggle(options.bobView(), "options.viewBobbing", "The camera sways while walking"));
        rows.add(new SettingsRow.Header("Quality"));
        rows.add(choice(options.graphicsMode(), "options.graphics", "Fast draws leaves and clouds flat; Fancy adds translucency. Fabulous uses the game's post shaders", GraphicsStatus.values()));
        rows.add(choice(options.cloudStatus(), "options.renderClouds", "Cloud style, or none", CloudStatus.values()));
        rows.add(toggle(options.ambientOcclusion(), "options.ao", "Soft shadows in corners and under blocks"));
        rows.add(choice(options.particles(), "options.particles", "How many particles the game spawns", ParticleStatus.values()));
        rows.add(slider(options.biomeBlendRadius(), "options.biomeBlendRadius", "How far grass, foliage and water colours blend between biomes. Larger costs chunk building time", 0, 7, 1, v -> v == 0 ? "Off" : (2 * v + 1) + "x" + (2 * v + 1)));
        rows.add(toggle(options.entityShadows(), "options.entityShadows", "Round shadows under mobs and items"));
        rows.add(new SettingsRow.Slider(Component.translatable("options.entityDistanceScaling"), "How far entities are drawn, relative to the default", 50, 500, 25,
                () -> (int) Math.round(options.entityDistanceScaling().get() * 100), value -> options.entityDistanceScaling().set(value / 100.0), v -> v + "%"));
        rows.add(slider(options.mipmapLevels(), "options.mipmapLevels", "Smoother distant textures. Applied when this screen closes", 0, 4, 1, v -> v == 0 ? "Off" : String.valueOf(v)));
        rows.add(slider(options.menuBackgroundBlurriness(), "options.accessibility.menu_background_blurriness", "Blur behind menus over the world", 0, 10, 1, String::valueOf));
        rows.add(new SettingsRow.Header("Performance"));
        rows.add(choice(options.prioritizeChunkUpdates(), "options.prioritizeChunkUpdates", "Which chunk updates are built on the main thread. Nearby and Player actions trade stutter for faster updates", PrioritizeChunkUpdates.values()));
        return rows;
    }

    private static SettingsRow slider(OptionInstance<Integer> option, String key, String description, int minimum, int maximum, int step, java.util.function.IntFunction<String> format) {
        return new SettingsRow.Slider(Component.translatable(key), description, minimum, maximum, step, option::get, option::set, format);
    }

    private static SettingsRow toggle(OptionInstance<Boolean> option, String key, String description) {
        return new SettingsRow.Toggle(Component.translatable(key), description, option::get, option::set);
    }

    private static <T extends OptionEnum> SettingsRow choice(OptionInstance<T> option, String key, String description, T[] values) {
        return new SettingsRow.Choice<>(Component.translatable(key), description, Arrays.asList(values), option::get, option::set, value -> value.getCaption().getString());
    }

    @Override
    void closed() {
        if (options.mipmapLevels().get() != mipmaps) {
            minecraft.updateMaxMipLevel(options.mipmapLevels().get());
            minecraft.delayTextureReload();
        }
        options.save();
    }
}
