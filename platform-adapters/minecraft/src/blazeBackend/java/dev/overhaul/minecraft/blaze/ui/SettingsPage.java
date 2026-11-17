package dev.overhaul.minecraft.blaze.ui;

import java.nio.file.Path;
import java.util.List;

/** A page of the settings screen. Pages rebuild their rows through {@code refresh} when what they list changes. */
abstract class SettingsPage {
    /** A group of the page's rows, picked from the rail at the left. */
    record Category(SettingsIcon icon, String name, String count) {
    }

    private final String key;
    private final SettingsIcon icon;
    protected final Runnable refresh;

    /** @param key the page's name in the language files, as in {@code overhaul.page.<key>} */
    SettingsPage(String key, SettingsIcon icon, Runnable refresh) {
        this.key = key;
        this.icon = icon;
        this.refresh = refresh;
    }

    final String title() {
        return text("title");
    }

    final SettingsIcon icon() {
        return icon;
    }

    /** The page's own text: {@code overhaul.page.<key>.<name>}. */
    protected final String text(String name, Object... arguments) {
        return OverhaulLang.get("overhaul.page." + key + "." + name, arguments);
    }

    abstract List<SettingsRow> rows();

    /** What the figure at the right of the header is. */
    String readoutLabel() {
        return text("readout");
    }

    /** The one figure that sums the page up, shown at the right of the header. */
    abstract String readout();

    /** Groups for a rail at the left of the rows; empty for a page that lists everything at once. */
    List<Category> categories() {
        return List.of();
    }

    int category() {
        return 0;
    }

    void category(int index) {
    }

    /** True when the page's picture of the world is large, since the page changes what the world looks like. */
    boolean preview() {
        return false;
    }

    /** A line for the bottom of the side panel, such as a restart that is pending, or the pack's state. */
    String status() {
        return "";
    }

    boolean statusIsError() {
        return false;
    }

    /** Status lines in the accent, such as a restart that is pending. */
    boolean statusIsPending() {
        return false;
    }

    void filesDropped(List<Path> files) {
    }
}
