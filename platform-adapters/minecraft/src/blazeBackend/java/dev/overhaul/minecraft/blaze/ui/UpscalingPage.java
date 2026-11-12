package dev.overhaul.minecraft.blaze.ui;

import dev.overhaul.minecraft.blaze.BlazeFrontend;
import dev.overhaul.minecraft.blaze.BlazeOptimization;
import dev.overhaul.minecraft.blaze.BlazeRenderer;
import dev.overhaul.minecraft.blaze.BlazeUpscale;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.network.chat.Component;

/**
 * Image quality, upscaling and ray tracing, laid out the way games offer them: anti-aliasing, sharpening and texture
 * filtering, one upscaler with a quality mode, and the ray traced effects. Every change applies to the next frame. While the card's preset decides, the first change here
 * writes out what the preset picked, so nothing else moves, and the page is custom from then on.
 */
final class UpscalingPage extends SettingsPage {
    /** The options this page owns, which the optimisation page leaves out. */
    static final Set<BlazeOptimization> OPTIONS = EnumSet.of(BlazeOptimization.FSR2, BlazeOptimization.DLSS, BlazeOptimization.PACK_UPSCALE, BlazeOptimization.UPSCALE_MIP_BIAS,
            BlazeOptimization.RAY_TRACING, BlazeOptimization.RT_SHADOWS, BlazeOptimization.RT_LIGHTING, BlazeOptimization.RT_AO, BlazeOptimization.RT_REFLECTIONS,
            BlazeOptimization.UPSCALE_READY, BlazeOptimization.GPU_PRESET);
    private static final String FSR_QUALITY = "fsr2Quality", DLSS_QUALITY = "dlssQuality", SHARPNESS = "fsr2Sharpness", DISTANCE = "rtDistance",
            ANTI_ALIASING = "antiAliasing", SHARPENING = "sharpening", ANISOTROPY = "anisotropy";
    private static final List<Integer> ANISOTROPY_LEVELS = List.of(1, 2, 4, 8, 16);

    /** How edges are smoothed: by a pass over the finished frame or by an upscaler drawing at full resolution. */
    private enum AntiAliasing {
        OFF("Off"), FXAA("FXAA"), TAA("TAA  FSR 2"), DLAA("DLAA");

        final String label;

        AntiAliasing(String label) {
            this.label = label;
        }
    }

    private enum Upscaler {
        OFF("Off"), FSR2("FSR 2"), DLSS("DLSS");

        final String label;

        Upscaler(String label) {
            this.label = label;
        }
    }

    /** A quality mode with the share of the window's width the world draws at. */
    private enum Mode {
        NATIVE("Native AA", "native", 100), DLAA("DLAA", "dlaa", 100), QUALITY("Quality", "quality", 67), BALANCED("Balanced", "balanced", 58),
        PERFORMANCE("Performance", "performance", 50), ULTRA("Ultra performance", "ultra", 33);

        final String label, value;
        final int percent;

        Mode(String label, String value, int percent) {
            this.label = label;
            this.value = value;
            this.percent = percent;
        }

        static Mode of(String value) {
            for (Mode mode : values()) if (mode.value.equalsIgnoreCase(value.trim())) return mode;
            return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "ultraperformance", "ultra_performance" -> ULTRA;
                case "nativeaa" -> NATIVE;
                default -> QUALITY;
            };
        }
    }

    UpscalingPage(Runnable refresh) {
        super(refresh);
    }

    @Override
    String title() {
        return "Graphics";
    }

    @Override
    boolean preview() {
        return true;
    }

    @Override
    List<SettingsRow> rows() {
        List<SettingsRow> rows = new ArrayList<>();
        rows.add(new SettingsRow.Header("Preset"));
        rows.add(new SettingsRow.Action(Component.literal("Recommended for this card"),
                "Sets this page to what the card affords",
                this::recommend, () -> BlazeOptimization.GPU_PRESET.on() ? "In use" : "Apply"));
        rows.add(new SettingsRow.Header("Image quality"));
        rows.add(new SettingsRow.Choice<>(Component.literal("Anti-aliasing"),
                "FXAA smooths edges for almost nothing; TAA and DLAA smooth more, shimmer less and cost more",
                List.of(AntiAliasing.values()), this::antiAliasing, this::antiAliasing, value -> value.label)
                .enabledWhen(() -> !scaledDown() && !locked(BlazeOptimization.FSR2) && !locked(BlazeOptimization.DLSS))
                .note(() -> scaledDown() ? "The upscaler smooths the image" : null));
        rows.add(new SettingsRow.Slider(Component.literal("Sharpening"), "Contrast adaptive sharpening of the finished frame", 0, 100, 5,
                () -> (int) number(BlazeOptimization.value(SHARPENING, "0"), 0), value -> saveValue(SHARPENING, String.valueOf(value)),
                value -> value == 0 ? "Off" : value + "%").enabledWhen(() -> upscaler() == Upscaler.OFF)
                .note(() -> upscaler() == Upscaler.OFF ? null : "The upscaler sharpens the image"));
        rows.add(new SettingsRow.Choice<>(Component.literal("Anisotropic filtering"), "Keeps textures sharp on floors and walls seen at an angle",
                ANISOTROPY_LEVELS, () -> anisotropy(), value -> saveValue(ANISOTROPY, String.valueOf(value)), value -> value == 1 ? "Off" : value + "x"));
        rows.add(new SettingsRow.Header("Upscaling"));
        Upscaler upscaler = upscaler();
        rows.add(new SettingsRow.Choice<>(Component.literal("Upscaler"),
                "FSR 2 runs on any card; DLSS needs NVIDIA RTX and falls back to FSR 2",
                List.of(Upscaler.values()), this::upscaler, this::upscaler, value -> value.label)
                .enabledWhen(() -> !locked(BlazeOptimization.FSR2) && !locked(BlazeOptimization.DLSS))
                .note(this::upscalerNote));
        if (upscaler != Upscaler.OFF) {
            String key = qualityKey(upscaler);
            rows.add(new SettingsRow.Choice<>(Component.literal("Quality mode"),
                    (upscaler == Upscaler.DLSS ? "DLAA" : "Native AA") + " smooths at full resolution; lower modes give more frames",
                    modes(upscaler), () -> Mode.of(BlazeOptimization.value(key, "quality")), mode -> saveValue(key, mode.value),
                    mode -> mode.label + "  " + mode.percent + "%"));
            if (upscaler == Upscaler.FSR2) {
                rows.add(new SettingsRow.Slider(Component.literal("Sharpness"), "How much FSR 2 sharpens the upscaled frame", 0, 100, 5,
                        () -> Math.round(number(BlazeOptimization.value(SHARPNESS, "0.3"), 0.3f) * 100),
                        value -> saveValue(SHARPNESS, String.valueOf(value / 100f)), value -> value + "%"));
            }
            rows.add(toggle(BlazeOptimization.UPSCALE_MIP_BIAS, "Sharp textures",
                    "Textures keep full resolution detail while the world draws smaller"));
            rows.add(toggle(BlazeOptimization.PACK_UPSCALE, "Under shader packs",
                    "The pack draws at the lower resolution too; turn off its own TAA"));
            rows.add(new SettingsRow.Action(Component.literal("Now drawing"), "Render resolution and window resolution this frame",
                    () -> {}, () -> {
                        String shown = BlazeUpscale.get().describe();
                        return shown == null ? "Full resolution" : shown;
                    }));
        }
        rows.add(new SettingsRow.Header("Ray tracing"));
        rows.add(toggle(BlazeOptimization.RAY_TRACING, "Ray traced lighting",
                "Traced shadows, occlusion and reflections; replaces a shader pack and waits while one is on")
                .note(this::rayNote));
        rows.add(toggle(BlazeOptimization.RT_SHADOWS, "Shadows", "Sun and moon shadows traced against the terrain").enabledWhen(this::tracing));
        rows.add(toggle(BlazeOptimization.RT_LIGHTING, "Lighting", "Coloured sun, sky and block light in place of the flat game lighting").enabledWhen(this::tracing));
        rows.add(toggle(BlazeOptimization.RT_AO, "Ambient occlusion", "Soft darkening in corners and under overhangs").enabledWhen(this::tracing));
        rows.add(toggle(BlazeOptimization.RT_REFLECTIONS, "Reflections", "Reflections on water and other upward facing translucent surfaces").enabledWhen(this::tracing));
        rows.add(new SettingsRow.Slider(Component.literal("Ray distance"), "How far rays reach; farther costs more", 16, 256, 16,
                () -> (int) number(BlazeOptimization.value(DISTANCE, "64"), 64), value -> saveValue(DISTANCE, String.valueOf(value)), value -> value + " blocks")
                .enabledWhen(this::tracing));
        rows.add(new SettingsRow.Header("Startup"));
        rows.add(toggle(BlazeOptimization.UPSCALE_READY, "Switch without restart",
                "Loads upscaling and ray tracing at startup so this page applies at once")
                .note(() -> BlazeOptimization.UPSCALE_READY.saved() != BlazeOptimization.UPSCALE_READY.launched() ? "Applies after a restart" : null));
        return rows;
    }

    private SettingsRow toggle(BlazeOptimization option, String label, String description) {
        return new SettingsRow.Toggle(Component.literal(label), description, option.live() ? option::on : option::saved, value -> set(Map.of(option, value)))
                .enabledWhen(() -> !locked(option))
                .note(() -> locked(option) ? "Set by -Doverhaul.opt." + option.key() + ", the screen cannot change it" : null);
    }

    private static boolean locked(BlazeOptimization option) {
        return option.launchOverride();
    }

    private boolean tracing() {
        return BlazeOptimization.RAY_TRACING.on();
    }

    private Upscaler upscaler() {
        if (BlazeOptimization.DLSS.on()) return Upscaler.DLSS;
        return BlazeOptimization.FSR2.on() ? Upscaler.FSR2 : Upscaler.OFF;
    }

    /** DLSS keeps FSR 2 on beneath it, so a card without DLSS still upscales. */
    private void upscaler(Upscaler upscaler) {
        Map<BlazeOptimization, Boolean> values = new EnumMap<>(BlazeOptimization.class);
        values.put(BlazeOptimization.FSR2, upscaler != Upscaler.OFF);
        values.put(BlazeOptimization.DLSS, upscaler == Upscaler.DLSS);
        set(values);
        refresh.run();
    }

    /** An upscaler at full resolution is the anti-aliasing; at any lower one it has no separate choice. */
    private AntiAliasing antiAliasing() {
        Upscaler upscaler = upscaler();
        if (upscaler == Upscaler.DLSS) return AntiAliasing.DLAA;
        if (upscaler == Upscaler.FSR2) return AntiAliasing.TAA;
        return BlazeOptimization.value(ANTI_ALIASING, "off").equalsIgnoreCase("fxaa") ? AntiAliasing.FXAA : AntiAliasing.OFF;
    }

    private void antiAliasing(AntiAliasing choice) {
        switch (choice) {
            case TAA -> saveValue(FSR_QUALITY, "native");
            case DLAA -> saveValue(DLSS_QUALITY, "dlaa");
            default -> saveValue(ANTI_ALIASING, choice == AntiAliasing.FXAA ? "fxaa" : "off");
        }
        upscaler(choice == AntiAliasing.DLAA ? Upscaler.DLSS : choice == AntiAliasing.TAA ? Upscaler.FSR2 : Upscaler.OFF);
    }

    /** Whether an upscaler draws below full resolution, which leaves anti-aliasing to it. */
    private boolean scaledDown() {
        Upscaler upscaler = upscaler();
        return upscaler != Upscaler.OFF && Mode.of(BlazeOptimization.value(qualityKey(upscaler), "quality")).percent < 100;
    }

    private static int anisotropy() {
        int wanted = (int) number(BlazeOptimization.value(ANISOTROPY, "1"), 1);
        int chosen = 1;
        for (int level : ANISOTROPY_LEVELS) if (level <= wanted) chosen = level;
        return chosen;
    }

    private static String qualityKey(Upscaler upscaler) {
        return upscaler == Upscaler.DLSS ? DLSS_QUALITY : FSR_QUALITY;
    }

    private static List<Mode> modes(Upscaler upscaler) {
        Mode full = upscaler == Upscaler.DLSS ? Mode.DLAA : Mode.NATIVE;
        return List.of(full, Mode.QUALITY, Mode.BALANCED, Mode.PERFORMANCE, Mode.ULTRA);
    }

    private String upscalerNote() {
        BlazeRenderer renderer = BlazeFrontend.current();
        if (upscaler() == Upscaler.OFF) return null;
        if (!hookLoaded()) return "Restart the game to apply";
        if (upscaler() != Upscaler.DLSS) return null;
        if (renderer == null || !renderer.streamlineLoaded()) {
            return BlazeOptimization.DLSS.launched() || BlazeOptimization.UPSCALE_READY.launched()
                    ? "DLSS is unavailable here, FSR 2 upscales instead" : "Restart the game to load DLSS; FSR 2 upscales until then";
        }
        return Boolean.FALSE.equals(BlazeUpscale.get().dlssAvailable()) ? "This card has no DLSS, FSR 2 upscales instead" : null;
    }

    private String rayNote() {
        if (!BlazeOptimization.RAY_TRACING.on()) return null;
        BlazeRenderer renderer = BlazeFrontend.current();
        if (renderer == null || renderer.context().rayQuery()) return null;
        boolean asked = BlazeOptimization.RAY_TRACING.launched() || BlazeOptimization.GPU_PRESET.launched() || BlazeOptimization.UPSCALE_READY.launched();
        return asked ? "This card or driver has no hardware ray queries" : "Restart the game to apply";
    }

    /** The world pass hook loads at startup while any of these is on. */
    private static boolean hookLoaded() {
        return BlazeOptimization.UPSCALE_READY.launched() || BlazeOptimization.FSR2.launched() || BlazeOptimization.DLSS.launched()
                || BlazeOptimization.GPU_PRESET.launched();
    }

    /** Writes out what the card's class picks and leaves the preset off, so later changes stay as made. */
    private void recommend() {
        Map<BlazeOptimization, Boolean> values = new EnumMap<>(BlazeOptimization.class);
        values.put(BlazeOptimization.FSR2, BlazeOptimization.FSR2.recommended());
        values.put(BlazeOptimization.DLSS, false);
        values.put(BlazeOptimization.RAY_TRACING, BlazeOptimization.RAY_TRACING.recommended());
        values.put(BlazeOptimization.GPU_PRESET, false);
        values.keySet().removeIf(BlazeOptimization::launchOverride);
        BlazeOptimization.save(values);
        for (String key : List.of(FSR_QUALITY, DLSS_QUALITY, DISTANCE)) {
            BlazeOptimization.saveValue(key, BlazeOptimization.recommendedValue(key, key.equals(DISTANCE) ? "64" : "quality"), "Set from the upscaling page");
        }
        BlazeOptimization.saveValue(ANISOTROPY, BlazeOptimization.recommendedValue(ANISOTROPY, "16"), "Set from the upscaling page");
        BlazeOptimization.saveValue(ANTI_ALIASING, BlazeOptimization.recommendedValue(ANTI_ALIASING, "fxaa"), "Set from the upscaling page");
        refresh.run();
    }

    private void set(Map<BlazeOptimization, Boolean> values) {
        leavePreset();
        BlazeOptimization.save(values);
    }

    private void saveValue(String key, String value) {
        leavePreset();
        BlazeOptimization.saveValue(key, value, "Set from the upscaling page");
    }

    /** The preset's picks become the file's, so turning it off changes nothing on screen. */
    private static void leavePreset() {
        if (!BlazeOptimization.GPU_PRESET.on() || BlazeOptimization.GPU_PRESET.launchOverride()) return;
        Map<BlazeOptimization, Boolean> values = new EnumMap<>(BlazeOptimization.class);
        for (BlazeOptimization option : List.of(BlazeOptimization.FSR2, BlazeOptimization.DLSS, BlazeOptimization.RAY_TRACING)) {
            if (!option.launchOverride()) values.put(option, option.on());
        }
        values.put(BlazeOptimization.GPU_PRESET, false);
        BlazeOptimization.save(values);
    }

    private static float number(String value, float fallback) {
        try {
            return Float.parseFloat(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    @Override
    String status() {
        String shown = BlazeUpscale.get().describe();
        return shown == null ? "Changes apply at once" : "Drawing " + shown;
    }
}
