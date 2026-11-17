package dev.overhaul.minecraft.blaze.ui;

import dev.overhaul.minecraft.blaze.BlazeFrontend;
import dev.overhaul.minecraft.blaze.BlazeOptimization;
import dev.overhaul.minecraft.blaze.BlazeRenderer;
import dev.overhaul.minecraft.blaze.BlazeUpscale;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Image quality and upscaling, laid out the way games offer them: anti-aliasing, sharpening and texture filtering,
 * then one upscaler with a quality mode.
 */
final class UpscalingPage extends SettingsPage {
    private static final String FSR_QUALITY = "fsr2Quality", DLSS_QUALITY = "dlssQuality", SHARPNESS = "fsr2Sharpness", DISTANCE = "rtDistance",
            ANTI_ALIASING = "antiAliasing", SHARPENING = "sharpening", ANISOTROPY = "anisotropy";
    private static final List<Integer> ANISOTROPY_LEVELS = List.of(1, 2, 4, 8, 16);

    /** How edges are smoothed: by a pass over the finished frame or by an upscaler drawing at full resolution. */
    private enum AntiAliasing {
        OFF, FXAA, TAA, DLAA
    }

    private enum Upscaler {
        OFF, FSR2, DLSS
    }

    /** A quality mode with the share of the window's width the world draws at. */
    private enum Mode {
        NATIVE("native", 100), DLAA("dlaa", 100), QUALITY("quality", 67), BALANCED("balanced", 58), PERFORMANCE("performance", 50), ULTRA("ultra", 33);

        final String value;
        final int percent;

        Mode(String value, int percent) {
            this.value = value;
            this.percent = percent;
        }

        static Mode of(String value) {
            String name = value.trim().toLowerCase(Locale.ROOT);
            for (Mode mode : values()) if (mode.value.equals(name)) return mode;
            return switch (name) {
                case "ultraperformance", "ultra_performance" -> ULTRA;
                case "nativeaa" -> NATIVE;
                default -> QUALITY;
            };
        }
    }

    private final OptimizationValues values;

    UpscalingPage(OptimizationValues values, Runnable refresh) {
        super("upscaling", SettingsIcon.UPSCALE, refresh);
        this.values = values;
    }

    @Override
    boolean preview() {
        return true;
    }

    @Override
    List<SettingsRow> rows() {
        List<SettingsRow> rows = new ArrayList<>();
        rows.add(new SettingsRow.Header(text("section.preset")));
        rows.add(new SettingsRow.Action(text("recommended.name"), text("recommended.desc"), SettingsIcon.CHECK,
                () -> on(BlazeOptimization.GPU_PRESET) ? text("recommended.used") : text("recommended.set"), this::recommend));
        rows.add(new SettingsRow.Header(text("section.image")));
        rows.add(new SettingsRow.Choice<>(text("aa.name"), text("aa.desc"), List.of(AntiAliasing.values()), this.<AntiAliasing>picked(this::antiAliasing, this::antiAliasing),
                value -> text("aa." + value.name().toLowerCase(Locale.ROOT)))
                .enabledWhen(() -> !scaledDown() && !locked()).note(() -> scaledDown() ? text("aa.upscaled") : null));
        rows.add(new SettingsRow.Slider(text("sharpening.name"), text("sharpening.desc"), 0, 100, 5,
                values.text(SHARPENING, "0").as(value -> Math.round(number(value, 0)), String::valueOf),
                value -> value == 0 ? OverhaulLang.get("overhaul.ui.off") : value + "%")
                .enabledWhen(() -> upscaler() == Upscaler.OFF).note(() -> upscaler() == Upscaler.OFF ? null : text("sharpening.upscaled")));
        rows.add(new SettingsRow.Choice<>(text("anisotropy.name"), text("anisotropy.desc"), ANISOTROPY_LEVELS,
                values.text(ANISOTROPY, "1").as(UpscalingPage::anisotropy, String::valueOf), value -> value == 1 ? OverhaulLang.get("overhaul.ui.off") : value + "x"));
        rows.add(new SettingsRow.Header(text("section.upscaling")));
        Upscaler upscaler = upscaler();
        rows.add(new SettingsRow.Choice<>(text("upscaler.name"), text("upscaler.desc"), List.of(Upscaler.values()), this.<Upscaler>picked(this::upscaler, this::upscaler),
                value -> value == Upscaler.OFF ? OverhaulLang.get("overhaul.ui.off") : value == Upscaler.FSR2 ? "FSR 2" : "DLSS")
                .enabledWhen(() -> !locked()).note(this::upscalerNote));
        if (upscaler != Upscaler.OFF) {
            rows.add(new SettingsRow.Choice<>(text("mode.name"), text("mode.desc"), modes(upscaler),
                    values.text(qualityKey(upscaler), "quality").as(Mode::of, mode -> mode.value),
                    mode -> text("mode." + mode.value) + "  " + mode.percent + "%"));
            if (upscaler == Upscaler.FSR2) {
                rows.add(new SettingsRow.Slider(text("sharpness.name"), text("sharpness.desc"), 0, 100, 5,
                        values.text(SHARPNESS, "0.3").as(value -> Math.round(number(value, 0.3f) * 100), value -> String.valueOf(value / 100f)), value -> value + "%"));
            }
            rows.add(values.toggle(BlazeOptimization.UPSCALE_MIP_BIAS, text("mip_bias.name"), text("mip_bias.desc")));
            rows.add(values.toggle(BlazeOptimization.PACK_UPSCALE, text("packs.name"), text("packs.desc")));
        }
        rows.add(new SettingsRow.Info(text("drawing.name"), text("drawing.desc"), this::readout));
        rows.add(new SettingsRow.Header(text("section.startup")));
        rows.add(values.toggle(BlazeOptimization.UPSCALE_READY, text("ready.name"), text("ready.desc")));
        return rows;
    }

    /** A choice that moves several values of the draft at once, and with them which rows the page lists. */
    private <T> SettingsDraft.Setting<T> picked(Supplier<T> get, Consumer<T> set) {
        return new SettingsDraft.Setting<>() {
            @Override
            public T get() {
                return get.get();
            }

            @Override
            public void set(T value) {
                set.accept(value);
                refresh.run();
            }

            @Override
            public boolean changed() {
                return values.flag(BlazeOptimization.FSR2).changed() || values.flag(BlazeOptimization.DLSS).changed() || values.text(ANTI_ALIASING, "off").changed();
            }
        };
    }

    private boolean on(BlazeOptimization option) {
        return values.flag(option).get();
    }

    private static boolean locked() {
        return BlazeOptimization.FSR2.launchOverride() || BlazeOptimization.DLSS.launchOverride();
    }

    private Upscaler upscaler() {
        if (on(BlazeOptimization.DLSS)) return Upscaler.DLSS;
        return on(BlazeOptimization.FSR2) ? Upscaler.FSR2 : Upscaler.OFF;
    }

    /** DLSS keeps FSR 2 on beneath it, so a card without DLSS still upscales. */
    private void upscaler(Upscaler upscaler) {
        values.flag(BlazeOptimization.FSR2).set(upscaler != Upscaler.OFF);
        values.flag(BlazeOptimization.DLSS).set(upscaler == Upscaler.DLSS);
    }

    /** An upscaler at full resolution is the anti-aliasing; at any lower one it has no separate choice. */
    private AntiAliasing antiAliasing() {
        Upscaler upscaler = upscaler();
        if (upscaler == Upscaler.DLSS) return AntiAliasing.DLAA;
        if (upscaler == Upscaler.FSR2) return AntiAliasing.TAA;
        return values.text(ANTI_ALIASING, "off").get().equalsIgnoreCase("fxaa") ? AntiAliasing.FXAA : AntiAliasing.OFF;
    }

    private void antiAliasing(AntiAliasing choice) {
        switch (choice) {
            case TAA -> values.text(FSR_QUALITY, "quality").set("native");
            case DLAA -> values.text(DLSS_QUALITY, "quality").set("dlaa");
            default -> values.text(ANTI_ALIASING, "off").set(choice == AntiAliasing.FXAA ? "fxaa" : "off");
        }
        upscaler(choice == AntiAliasing.DLAA ? Upscaler.DLSS : choice == AntiAliasing.TAA ? Upscaler.FSR2 : Upscaler.OFF);
    }

    /** Whether an upscaler draws below full resolution, which leaves anti-aliasing to it. */
    private boolean scaledDown() {
        Upscaler upscaler = upscaler();
        return upscaler != Upscaler.OFF && Mode.of(values.text(qualityKey(upscaler), "quality").get()).percent < 100;
    }

    private static int anisotropy(String value) {
        int wanted = Math.round(number(value, 1)), chosen = 1;
        for (int level : ANISOTROPY_LEVELS) if (level <= wanted) chosen = level;
        return chosen;
    }

    private static String qualityKey(Upscaler upscaler) {
        return upscaler == Upscaler.DLSS ? DLSS_QUALITY : FSR_QUALITY;
    }

    private static List<Mode> modes(Upscaler upscaler) {
        return List.of(upscaler == Upscaler.DLSS ? Mode.DLAA : Mode.NATIVE, Mode.QUALITY, Mode.BALANCED, Mode.PERFORMANCE, Mode.ULTRA);
    }

    private String upscalerNote() {
        if (locked()) return OverhaulLang.get("overhaul.ui.launch_override", "-Doverhaul.opt." + BlazeOptimization.FSR2.key());
        if (upscaler() == Upscaler.OFF) return null;
        if (!hookLoaded()) return OverhaulLang.get("overhaul.ui.restart");
        if (upscaler() != Upscaler.DLSS) return null;
        BlazeRenderer renderer = BlazeFrontend.current();
        if (renderer == null || !renderer.streamlineLoaded()) {
            return BlazeOptimization.DLSS.launched() || BlazeOptimization.UPSCALE_READY.launched() ? text("dlss.unavailable") : text("dlss.restart");
        }
        return Boolean.FALSE.equals(BlazeUpscale.get().dlssAvailable()) ? text("dlss.unsupported") : null;
    }

    /** The world pass hook loads at startup while any of these is on. */
    private static boolean hookLoaded() {
        return BlazeOptimization.UPSCALE_READY.launched() || BlazeOptimization.FSR2.launched() || BlazeOptimization.DLSS.launched()
                || BlazeOptimization.GPU_PRESET.launched();
    }

    /** Stages what the card's class picks and leaves the preset off, so later changes stay as made. */
    private void recommend() {
        stage(BlazeOptimization.FSR2, BlazeOptimization.FSR2.recommended());
        stage(BlazeOptimization.DLSS, false);
        stage(BlazeOptimization.RAY_TRACING, BlazeOptimization.RAY_TRACING.recommended());
        stage(BlazeOptimization.GPU_PRESET, false);
        recommend(FSR_QUALITY, "quality");
        recommend(DLSS_QUALITY, "quality");
        recommend(DISTANCE, "64");
        recommend(ANISOTROPY, "16");
        recommend(ANTI_ALIASING, "fxaa");
        refresh.run();
    }

    private void stage(BlazeOptimization option, boolean value) {
        if (!option.launchOverride()) values.flag(option).set(value);
    }

    private void recommend(String key, String fallback) {
        values.text(key, fallback).set(BlazeOptimization.recommendedValue(key, fallback));
    }

    private static float number(String value, float fallback) {
        try {
            return Float.parseFloat(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    @Override
    String readout() {
        String shown = BlazeUpscale.get().describe();
        return shown == null ? text("full") : shown;
    }

    @Override
    String status() {
        return BlazeOptimization.UPSCALE_READY.saved() != BlazeOptimization.UPSCALE_READY.launched() ? OverhaulLang.get("overhaul.ui.restart") : "";
    }

    @Override
    boolean statusIsPending() {
        return true;
    }
}
