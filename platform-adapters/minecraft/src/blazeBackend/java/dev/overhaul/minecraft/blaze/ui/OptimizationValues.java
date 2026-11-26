package dev.overhaul.minecraft.blaze.ui;

import dev.overhaul.minecraft.blaze.BlazeOptimization;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The optimisation file seen through the draft. Everything staged on any page is written by one save after Apply, so
 * the file is rewritten once however many switches moved.
 */
final class OptimizationValues {
    /** What the upscaling and lighting pages own; the optimisation page leaves these out. */
    static final Set<BlazeOptimization> GRAPHICS = EnumSet.of(BlazeOptimization.FSR2, BlazeOptimization.DLSS, BlazeOptimization.PACK_UPSCALE, BlazeOptimization.UPSCALE_MIP_BIAS,
            BlazeOptimization.RAY_TRACING, BlazeOptimization.RT_SHADOWS, BlazeOptimization.RT_LIGHTING, BlazeOptimization.RT_AO, BlazeOptimization.RT_GI, BlazeOptimization.RT_REFLECTIONS,
            BlazeOptimization.OVERHAUL_SHADING, BlazeOptimization.SHADING_BLOOM, BlazeOptimization.SHADING_EXPOSURE, BlazeOptimization.SHADING_SCREEN_SHADOWS, BlazeOptimization.SHADING_SHAFTS,
            BlazeOptimization.SHADING_OCCLUSION, BlazeOptimization.SHADING_CLOUDS, BlazeOptimization.SHADING_WATER, BlazeOptimization.SHADING_COLORED_LIGHT, BlazeOptimization.PATH_TRACING, BlazeOptimization.UPSCALE_READY, BlazeOptimization.GPU_PRESET);
    private static final List<BlazeOptimization> PRESET_PICKS = List.of(BlazeOptimization.FSR2, BlazeOptimization.DLSS, BlazeOptimization.RAY_TRACING);
    private static final String REASON = "Set from the settings screen";
    private final SettingsDraft draft;
    private final Map<BlazeOptimization, Boolean> flags = new EnumMap<>(BlazeOptimization.class);
    private final Map<String, String> texts = new LinkedHashMap<>();

    OptimizationValues(SettingsDraft draft) {
        this.draft = draft;
        draft.onApplied(this::flush);
    }

    /** What the file holds for an option the game reads at startup, and what runs now for one it reads every frame. */
    static boolean current(BlazeOptimization option) {
        return option.live() ? option.on() : option.saved();
    }

    SettingsDraft.Value<Boolean> flag(BlazeOptimization option) {
        return draft.value(option, () -> current(option), value -> flags.put(option, value));
    }

    /** A named value of the file, such as an upscaler's quality mode. */
    SettingsDraft.Value<String> text(String key, String fallback) {
        return draft.value("optimization." + key, () -> BlazeOptimization.value(key, fallback), value -> texts.put(key, value));
    }

    /** A switch for one option; a launch argument that sets the option locks the row and says so. */
    SettingsRow toggle(BlazeOptimization option, String label, String description) {
        return new SettingsRow.Toggle(label, description, flag(option))
                .enabledWhen(() -> !option.launchOverride())
                .note(() -> option.launchOverride() ? OverhaulLang.get("overhaul.ui.launch_override", "-Doverhaul.opt." + option.key()) : null);
    }

    private void flush() {
        if (flags.isEmpty() && texts.isEmpty()) return;
        leavePreset();
        flags.keySet().removeIf(BlazeOptimization::launchOverride);
        if (!flags.isEmpty()) BlazeOptimization.save(flags);
        texts.forEach((key, value) -> BlazeOptimization.saveValue(key, value, REASON));
        flags.clear();
        texts.clear();
    }

    /**
     * While the card's preset decides, the first change to what it owns writes out what the preset picked and turns
     * the preset off, so nothing else moves and the pages are custom from then on.
     */
    private void leavePreset() {
        BlazeOptimization preset = BlazeOptimization.GPU_PRESET;
        if (!preset.on() || preset.launchOverride() || flags.containsKey(preset)) return;
        if (texts.isEmpty() && flags.keySet().stream().noneMatch(GRAPHICS::contains)) return;
        for (BlazeOptimization option : PRESET_PICKS) flags.putIfAbsent(option, option.on());
        flags.put(preset, false);
    }
}
