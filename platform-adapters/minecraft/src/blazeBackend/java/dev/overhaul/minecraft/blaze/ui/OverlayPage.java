package dev.overhaul.minecraft.blaze.ui;

import dev.overhaul.minecraft.blaze.BlazeOverlayItem;
import dev.overhaul.minecraft.blaze.BlazeOverlaySettings;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** The performance overlay: whether it shows, how it looks, and which figures it carries. */
final class OverlayPage extends SettingsPage {
    private final SettingsDraft draft;
    private final BlazeOverlaySettings settings = BlazeOverlaySettings.get();
    private boolean written;

    OverlayPage(SettingsDraft draft, Runnable refresh) {
        super("overlay", SettingsIcon.OVERLAY, refresh);
        this.draft = draft;
        draft.onApplied(() -> {
            if (written) settings.save();
            written = false;
        });
    }

    @Override
    boolean preview() {
        return true;
    }

    @Override
    List<SettingsRow> rows() {
        List<SettingsRow> rows = new ArrayList<>();
        rows.add(new SettingsRow.Header(text("section.overlay")));
        rows.add(new SettingsRow.Toggle(text("enabled.name"), text("enabled.desc"), this.<Boolean>value("enabled", settings::enabled, settings::enabled)));
        rows.add(new SettingsRow.Choice<>(text("hotkey.name"), text("hotkey.desc"), BlazeOverlaySettings.HOTKEYS,
                this.<Integer>value("hotkey", settings::hotkey, settings::hotkey), key -> key == 0 ? text("none") : BlazeOverlaySettings.keyName(key)));
        rows.add(new SettingsRow.Toggle(text("hide.name"), text("hide.desc"), this.<Boolean>value("hideWithHud", settings::hideWithHud, settings::hideWithHud)));
        rows.add(new SettingsRow.Header(text("section.look")));
        rows.add(new SettingsRow.Choice<>(text("corner.name"), text("corner.desc"), List.of(BlazeOverlaySettings.Corner.values()),
                this.<BlazeOverlaySettings.Corner>value("corner", settings::corner, settings::corner), value -> named("corner", value)));
        rows.add(new SettingsRow.Choice<>(text("layout.name"), text("layout.desc"), List.of(BlazeOverlaySettings.Layout.values()),
                this.<BlazeOverlaySettings.Layout>value("layout", settings::layout, settings::layout), value -> named("layout", value)));
        rows.add(new SettingsRow.Choice<>(text("tint.name"), text("tint.desc"), List.of(BlazeOverlaySettings.Tint.values()),
                this.<BlazeOverlaySettings.Tint>value("tint", settings::tint, settings::tint), value -> named("tint", value)));
        rows.add(new SettingsRow.Toggle(text("speed.name"), text("speed.desc"), this.<Boolean>value("speedColours", settings::speedColours, settings::speedColours)));
        rows.add(new SettingsRow.Slider(text("scale.name"), text("scale.desc"), 50, 200, 10, this.<Integer>value("scale", settings::scale, settings::scale), value -> value + "%"));
        rows.add(new SettingsRow.Slider(text("opacity.name"), text("opacity.desc"), 0, 100, 5, this.<Integer>value("opacity", settings::opacity, settings::opacity),
                value -> value == 0 ? text("none") : value + "%"));
        rows.add(new SettingsRow.Slider(text("refresh.name"), text("refresh.desc"), 50, 2000, 50,
                this.<Integer>value("refreshMillis", settings::refreshMillis, settings::refreshMillis), value -> text("millis", value)));
        rows.add(new SettingsRow.Header(text("section.shows"), () -> shown() + " / " + BlazeOverlayItem.values().length));
        for (BlazeOverlayItem item : BlazeOverlayItem.values()) {
            String key = "overhaul.overlay.item." + item.key;
            rows.add(new SettingsRow.Toggle(OverhaulLang.or(key + ".name", item.label), OverhaulLang.or(key + ".desc", item.description), shows(item)));
        }
        return rows;
    }

    private <T> SettingsDraft.Value<T> value(String key, Supplier<T> live, Consumer<T> commit) {
        return draft.value("overlay." + key, live, value -> {
            commit.accept(value);
            written = true;
        });
    }

    private SettingsDraft.Value<Boolean> shows(BlazeOverlayItem item) {
        return value("shows." + item.key, () -> settings.shows(item), value -> settings.shows(item, value));
    }

    private String named(String group, Enum<?> value) {
        return text(group + "." + value.name().toLowerCase(Locale.ROOT));
    }

    private long shown() {
        long count = 0;
        for (BlazeOverlayItem item : BlazeOverlayItem.values()) if (shows(item).get()) count++;
        return count;
    }

    @Override
    String readout() {
        boolean enabled = this.<Boolean>value("enabled", settings::enabled, settings::enabled).get();
        return OverhaulLang.get(enabled ? "overhaul.ui.on" : "overhaul.ui.off");
    }
}
