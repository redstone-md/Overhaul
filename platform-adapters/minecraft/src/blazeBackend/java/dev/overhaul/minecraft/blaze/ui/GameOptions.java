package dev.overhaul.minecraft.blaze.ui;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.IntFunction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.network.chat.Component;
import net.minecraft.util.OptionEnum;

/**
 * The game's own options seen through the draft. Apply writes the staged ones, saves the options file, and does what
 * the game's video screen does on leaving: reloads textures for a new mipmap level, lays the screen out for a new scale.
 */
final class GameOptions implements QualityPreset.Sink {
    final Minecraft minecraft;
    final Options options;
    private final SettingsDraft draft;
    private boolean written, textures, scale;

    GameOptions(Minecraft minecraft, SettingsDraft draft) {
        this.minecraft = minecraft;
        this.options = minecraft.options;
        this.draft = draft;
        draft.onApplied(this::applied);
    }

    <T> SettingsDraft.Value<T> value(OptionInstance<T> option) {
        return draft.value(option, option::get, value -> write(option, value));
    }

    @Override
    public <T> T get(OptionInstance<T> option) {
        return value(option).get();
    }

    @Override
    public <T> void set(OptionInstance<T> option, T value) {
        value(option).set(value);
    }

    /** The game's own name for an option, in the language the game runs in. */
    static String name(String key) {
        return Component.translatable(key).getString();
    }

    SettingsRow slider(OptionInstance<Integer> option, String key, String description, int minimum, int maximum, int step, IntFunction<String> format) {
        return new SettingsRow.Slider(name(key), description, minimum, maximum, step, value(option), format);
    }

    /** A slider over an option the game stores as a fraction, shown in percent. */
    SettingsRow percent(OptionInstance<Double> option, String key, String description, int minimum, int maximum, int step) {
        return new SettingsRow.Slider(name(key), description, minimum, maximum, step,
                value(option).as(value -> (int) Math.round(value * 100), value -> value / 100.0), value -> value + "%");
    }

    SettingsRow toggle(OptionInstance<Boolean> option, String key, String description) {
        return new SettingsRow.Toggle(name(key), description, value(option));
    }

    <T extends OptionEnum> SettingsRow choice(OptionInstance<T> option, String key, String description, T[] values) {
        return new SettingsRow.Choice<>(name(key), description, Arrays.asList(values), value(option), value -> value.getCaption().getString());
    }

    /** Some options reload the world when set, so one that already holds the value is left alone. */
    private <T> void write(OptionInstance<T> option, T value) {
        if (Objects.equals(option.get(), value)) return;
        option.set(value);
        written = true;
        if (option == options.mipmapLevels()) textures = true;
        if (option == options.guiScale()) scale = true;
    }

    private void applied() {
        if (!written) return;
        options.save();
        if (textures) {
            minecraft.updateMaxMipLevel(options.mipmapLevels().get());
            minecraft.delayTextureReload();
        }
        if (scale) minecraft.resizeDisplay();
        written = textures = scale = false;
    }
}
