package dev.overhaul.minecraft.blaze.ui;

import dev.overhaul.minecraft.blaze.BlazeOverlayItem;
import dev.overhaul.minecraft.blaze.BlazeOverlaySettings;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.network.chat.Component;

/** The performance overlay: whether it shows, where, how it looks, and which figures it lists. */
final class OverlayPage extends SettingsPage {
    private final BlazeOverlaySettings settings = BlazeOverlaySettings.get();

    OverlayPage(Runnable refresh) {
        super(refresh);
    }

    @Override
    String title() {
        return "Overlay";
    }

    @Override
    boolean preview() {
        return true;
    }

    @Override
    List<SettingsRow> rows() {
        List<SettingsRow> rows = new ArrayList<>();
        rows.add(new SettingsRow.Header("Overlay"));
        rows.add(toggle("Show overlay", "Frame rate and other figures in a corner of the screen. Built a few times a second and drawn from cache, so it costs next to nothing",
                settings::enabled, settings::enabled));
        rows.add(new SettingsRow.Choice<>(Component.literal("Hotkey"), "Shows or hides the overlay in game",
                BlazeOverlaySettings.HOTKEYS, settings::hotkey, settings::hotkey, BlazeOverlaySettings::keyName));
        rows.add(toggle("Hide with HUD", "Hides the overlay with the rest of the HUD when F1 is pressed", settings::hideWithHud, settings::hideWithHud));
        rows.add(new SettingsRow.Header("Look"));
        rows.add(new SettingsRow.Choice<>(Component.literal("Corner"), "Where on the screen the overlay sits",
                Arrays.asList(BlazeOverlaySettings.Corner.values()), settings::corner, settings::corner, corner -> corner.label));
        rows.add(new SettingsRow.Choice<>(Component.literal("Layout"), "A column of figures, or all of them on one line",
                Arrays.asList(BlazeOverlaySettings.Layout.values()), settings::layout, settings::layout, layout -> layout.label));
        rows.add(new SettingsRow.Choice<>(Component.literal("Text colour"), "Colour of the figures",
                Arrays.asList(BlazeOverlaySettings.Tint.values()), settings::tint, settings::tint, tint -> tint.label));
        rows.add(toggle("Colour by speed", "Frame rate turns green at 60 and above, yellow from 30, red below; slow frames turn red in the graph",
                settings::speedColours, settings::speedColours));
        rows.add(new SettingsRow.Slider(Component.literal("Size"), "Size relative to the HUD", 50, 200, 10,
                settings::scale, settings::scale, value -> value + "%"));
        rows.add(new SettingsRow.Slider(Component.literal("Background"), "Opacity of the panel behind the text. Text gets a shadow below 30%", 0, 100, 5,
                settings::opacity, settings::opacity, value -> value == 0 ? "None" : value + "%"));
        rows.add(new SettingsRow.Slider(Component.literal("Refresh"), "How often the figures update. The graph moves every frame", 50, 2000, 50,
                settings::refreshMillis, settings::refreshMillis, value -> value + " ms"));
        rows.add(new SettingsRow.Header("Shows"));
        for (BlazeOverlayItem item : BlazeOverlayItem.values()) {
            rows.add(toggle(item.label, item.description, () -> settings.shows(item), shown -> settings.shows(item, shown)));
        }
        return rows;
    }

    private static SettingsRow toggle(String label, String description, java.util.function.BooleanSupplier get, java.util.function.Consumer<Boolean> set) {
        return new SettingsRow.Toggle(Component.literal(label), description, get, set);
    }

    @Override
    void closed() {
        settings.save();
    }
}
