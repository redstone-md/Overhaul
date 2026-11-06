package dev.overhaul.minecraft.blaze.ui;

import java.nio.file.Path;
import java.util.List;

/** A page of the settings screen. Pages rebuild their rows through {@code refresh} when what they list changes. */
abstract class SettingsPage {
    protected final Runnable refresh;

    SettingsPage(Runnable refresh) {
        this.refresh = refresh;
    }

    abstract String title();

    abstract List<SettingsRow> rows();

    /** True when the right side stays clear so the world, and the shader pack on it, shows through. */
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

    void closed() {
    }
}
