package dev.overhaul.minecraft.blaze;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import org.lwjgl.glfw.GLFW;
import org.slf4j.LoggerFactory;

/**
 * What the performance overlay shows and where, kept in {@code config/overhaul-overlay.properties}. Changes apply on
 * the next frame; {@link #save()} writes them. Render thread only.
 */
public final class BlazeOverlaySettings {
    private static final Path PATH = Path.of("config", "overhaul-overlay.properties");
    /** Keys the game itself leaves free; 0 means no key. */
    public static final List<Integer> HOTKEYS = List.of(0, GLFW.GLFW_KEY_F6, GLFW.GLFW_KEY_F7, GLFW.GLFW_KEY_F8,
            GLFW.GLFW_KEY_F9, GLFW.GLFW_KEY_F10, GLFW.GLFW_KEY_F12);
    private static final BlazeOverlaySettings INSTANCE = load();

    public enum Corner {
        TOP_LEFT("Top left"), TOP_RIGHT("Top right"), BOTTOM_LEFT("Bottom left"), BOTTOM_RIGHT("Bottom right");

        public final String label;

        Corner(String label) {
            this.label = label;
        }

        boolean right() {
            return this == TOP_RIGHT || this == BOTTOM_RIGHT;
        }

        boolean bottom() {
            return this == BOTTOM_LEFT || this == BOTTOM_RIGHT;
        }
    }

    public enum Layout {
        COLUMN("Column"), ROW("Single line");

        public final String label;

        Layout(String label) {
            this.label = label;
        }
    }

    public enum Tint {
        WHITE("White", 0xFFFFFF), GREEN("Green", 0x7CE38B), YELLOW("Yellow", 0xF2D46B), CYAN("Cyan", 0x6FD3E8);

        public final String label;
        final int rgb;

        Tint(String label, int rgb) {
            this.label = label;
            this.rgb = rgb;
        }
    }

    private boolean enabled, hideWithHud = true, speedColours = true;
    private Corner corner = Corner.TOP_LEFT;
    private Layout layout = Layout.COLUMN;
    private Tint tint = Tint.WHITE;
    private int scale = 100, refreshMillis = 250, opacity = 45, hotkey = GLFW.GLFW_KEY_F10;
    private final Set<BlazeOverlayItem> items = EnumSet.of(BlazeOverlayItem.FPS, BlazeOverlayItem.FRAME_TIME,
            BlazeOverlayItem.LOWS, BlazeOverlayItem.GRAPH, BlazeOverlayItem.GPU_TIME, BlazeOverlayItem.RENDER);
    private int revision;

    private BlazeOverlaySettings() {}

    public static BlazeOverlaySettings get() {
        return INSTANCE;
    }

    public boolean enabled() { return enabled; }
    public boolean hideWithHud() { return hideWithHud; }
    public boolean speedColours() { return speedColours; }
    public Corner corner() { return corner; }
    public Layout layout() { return layout; }
    public Tint tint() { return tint; }
    /** Percent of the GUI scale. */
    public int scale() { return scale; }
    public int refreshMillis() { return refreshMillis; }
    /** Background opacity in percent. */
    public int opacity() { return opacity; }
    /** A GLFW key code, or 0. */
    public int hotkey() { return hotkey; }
    public boolean shows(BlazeOverlayItem item) { return items.contains(item); }
    /** Rises on every change, so the overlay knows to lay its text out again. */
    int revision() { return revision; }

    public void enabled(boolean value) { enabled = value; revision++; }
    public void hideWithHud(boolean value) { hideWithHud = value; revision++; }
    public void speedColours(boolean value) { speedColours = value; revision++; }
    public void corner(Corner value) { corner = value; revision++; }
    public void layout(Layout value) { layout = value; revision++; }
    public void tint(Tint value) { tint = value; revision++; }
    public void scale(int value) { scale = clamp(value, 50, 200); revision++; }
    public void refreshMillis(int value) { refreshMillis = clamp(value, 50, 2000); revision++; }
    public void opacity(int value) { opacity = clamp(value, 0, 100); revision++; }
    public void hotkey(int value) { hotkey = HOTKEYS.contains(value) ? value : 0; revision++; }

    public void shows(BlazeOverlayItem item, boolean shown) {
        if (shown) items.add(item);
        else items.remove(item);
        revision++;
    }

    /** A short name for a key from {@link #HOTKEYS}. */
    public static String keyName(int key) {
        return key == 0 ? "None" : "F" + (key - GLFW.GLFW_KEY_F1 + 1);
    }

    public void save() {
        Properties file = new Properties();
        file.setProperty("enabled", String.valueOf(enabled));
        file.setProperty("hideWithHud", String.valueOf(hideWithHud));
        file.setProperty("speedColours", String.valueOf(speedColours));
        file.setProperty("corner", corner.name().toLowerCase(Locale.ROOT));
        file.setProperty("layout", layout.name().toLowerCase(Locale.ROOT));
        file.setProperty("tint", tint.name().toLowerCase(Locale.ROOT));
        file.setProperty("scale", String.valueOf(scale));
        file.setProperty("refreshMillis", String.valueOf(refreshMillis));
        file.setProperty("opacity", String.valueOf(opacity));
        file.setProperty("hotkey", keyName(hotkey));
        for (BlazeOverlayItem item : BlazeOverlayItem.values()) file.setProperty("show." + item.key, String.valueOf(items.contains(item)));
        try {
            Files.createDirectories(PATH.getParent());
            try (Writer out = Files.newBufferedWriter(PATH)) {
                file.store(out, "Overhaul performance overlay. Edit in game: Overhaul settings, Overlay");
            }
        } catch (IOException failure) {
            LoggerFactory.getLogger(BlazeOverlaySettings.class).warn("Cannot write {}", PATH, failure);
        }
    }

    private static BlazeOverlaySettings load() {
        BlazeOverlaySettings settings = new BlazeOverlaySettings();
        if (!Files.isRegularFile(PATH)) return settings;
        Properties file = new Properties();
        try (Reader in = Files.newBufferedReader(PATH)) {
            file.load(in);
        } catch (IOException | IllegalArgumentException failure) {
            LoggerFactory.getLogger(BlazeOverlaySettings.class).warn("Cannot read {}", PATH, failure);
            return settings;
        }
        settings.enabled = flag(file, "enabled", settings.enabled);
        settings.hideWithHud = flag(file, "hideWithHud", settings.hideWithHud);
        settings.speedColours = flag(file, "speedColours", settings.speedColours);
        settings.corner = named(Corner.class, file.getProperty("corner"), settings.corner);
        settings.layout = named(Layout.class, file.getProperty("layout"), settings.layout);
        settings.tint = named(Tint.class, file.getProperty("tint"), settings.tint);
        settings.scale(number(file, "scale", settings.scale));
        settings.refreshMillis(number(file, "refreshMillis", settings.refreshMillis));
        settings.opacity(number(file, "opacity", settings.opacity));
        settings.hotkey(key(file.getProperty("hotkey"), settings.hotkey));
        for (BlazeOverlayItem item : BlazeOverlayItem.values()) settings.shows(item, flag(file, "show." + item.key, settings.items.contains(item)));
        return settings;
    }

    private static boolean flag(Properties file, String key, boolean fallback) {
        String value = file.getProperty(key);
        return value == null ? fallback : Boolean.parseBoolean(value.trim());
    }

    private static int number(Properties file, String key, int fallback) {
        try {
            String value = file.getProperty(key);
            return value == null ? fallback : Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static <E extends Enum<E>> E named(Class<E> type, String value, E fallback) {
        if (value == null) return fallback;
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    private static int key(String value, int fallback) {
        if (value == null) return fallback;
        for (int key : HOTKEYS) if (keyName(key).equalsIgnoreCase(value.trim())) return key;
        return fallback;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
