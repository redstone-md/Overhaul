package dev.overhaul.minecraft.blaze.ui;

import dev.overhaul.minecraft.blaze.BlazeFrontend;
import dev.overhaul.minecraft.blaze.BlazeOptimization;
import dev.overhaul.minecraft.blaze.BlazeRenderer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Overhaul's own shading and the ray traced effects, each with the switches of its parts. */
final class LightingPage extends SettingsPage {
    private static final String DISTANCE = "rtDistance";
    private static final List<BlazeOptimization> SHADING = List.of(BlazeOptimization.SHADING_BLOOM, BlazeOptimization.SHADING_EXPOSURE, BlazeOptimization.SHADING_SCREEN_SHADOWS,
            BlazeOptimization.SHADING_SHAFTS, BlazeOptimization.SHADING_OCCLUSION, BlazeOptimization.SHADING_CLOUDS, BlazeOptimization.SHADING_WATER, BlazeOptimization.SHADING_COLORED_LIGHT);
    private static final List<BlazeOptimization> TRACED = List.of(BlazeOptimization.RT_SHADOWS, BlazeOptimization.RT_LIGHTING, BlazeOptimization.RT_AO, BlazeOptimization.RT_GI,
            BlazeOptimization.RT_REFLECTIONS);
    private final OptimizationValues values;

    LightingPage(OptimizationValues values, Runnable refresh) {
        super("lighting", SettingsIcon.LIGHT, refresh);
        this.values = values;
    }

    @Override
    boolean preview() {
        return true;
    }

    @Override
    List<SettingsRow> rows() {
        List<SettingsRow> rows = new ArrayList<>();
        rows.add(new SettingsRow.Header(text("section.shading"), () -> count(BlazeOptimization.OVERHAUL_SHADING, SHADING)));
        rows.add(row(BlazeOptimization.OVERHAUL_SHADING, null));
        for (BlazeOptimization option : SHADING) rows.add(row(option, BlazeOptimization.OVERHAUL_SHADING));
        rows.add(new SettingsRow.Header(text("section.tracing"), () -> count(BlazeOptimization.RAY_TRACING, TRACED)));
        rows.add(row(BlazeOptimization.RAY_TRACING, null).note(this::rayNote));
        for (BlazeOptimization option : TRACED) rows.add(row(option, BlazeOptimization.RAY_TRACING));
        rows.add(new SettingsRow.Slider(text("distance.name"), text("distance.desc"), 16, 256, 16,
                values.text(DISTANCE, "64").as(LightingPage::whole, String::valueOf), value -> text("blocks", value))
                .enabledWhen(() -> on(BlazeOptimization.RAY_TRACING)));
        return rows;
    }

    /** @param parent the switch this one is a part of, or null */
    private SettingsRow row(BlazeOptimization option, BlazeOptimization parent) {
        String key = option.name().toLowerCase(Locale.ROOT);
        SettingsRow row = values.toggle(option, text(key + ".name"), text(key + ".desc"));
        return parent == null ? row : row.enabledWhen(() -> on(parent) && !option.launchOverride());
    }

    private boolean on(BlazeOptimization option) {
        return values.flag(option).get();
    }

    private String count(BlazeOptimization parent, List<BlazeOptimization> parts) {
        if (!on(parent)) return OverhaulLang.get("overhaul.ui.off");
        return parts.stream().filter(this::on).count() + " / " + parts.size();
    }

    private static int whole(String value) {
        try {
            return Math.round(Float.parseFloat(value.trim()));
        } catch (NumberFormatException ignored) {
            return 64;
        }
    }

    private String rayNote() {
        if (BlazeOptimization.RAY_TRACING.launchOverride()) return OverhaulLang.get("overhaul.ui.launch_override", "-Doverhaul.opt." + BlazeOptimization.RAY_TRACING.key());
        if (!on(BlazeOptimization.RAY_TRACING)) return null;
        BlazeRenderer renderer = BlazeFrontend.current();
        if (renderer == null || renderer.context().rayQuery()) return null;
        boolean asked = BlazeOptimization.RAY_TRACING.launched() || BlazeOptimization.GPU_PRESET.launched() || BlazeOptimization.UPSCALE_READY.launched();
        return asked ? text("no_rays") : OverhaulLang.get("overhaul.ui.restart");
    }

    @Override
    String readout() {
        if (on(BlazeOptimization.RAY_TRACING)) return text("mode.traced");
        return on(BlazeOptimization.OVERHAUL_SHADING) ? text("mode.shading") : text("mode.game");
    }
}
