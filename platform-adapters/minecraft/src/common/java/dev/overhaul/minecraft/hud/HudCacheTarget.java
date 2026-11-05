package dev.overhaul.minecraft.hud;

/**
 * Tells the Vulkan frontend which framebuffer holds the cached HUD while that pass runs.
 * The class is shared because the frontend also compiles on tiers without the HUD cache.
 *
 * <p>When the frontend is present, the cached target keeps in its alpha channel how much of the
 * world stays visible behind the HUD. Each draw multiplies that value by its destination color
 * factor, which is what the same draw would do to the world, so the composite reproduces direct
 * drawing for any blend state, including draws with blending turned off.
 */
public final class HudCacheTarget {
    private static final int NONE = -1;

    private static boolean tracksWorldVisibility;
    private static int framebuffer = NONE;

    private HudCacheTarget() {
    }

    public static void enableWorldVisibility() {
        tracksWorldVisibility = true;
    }

    public static boolean tracksWorldVisibility() {
        return tracksWorldVisibility;
    }

    public static void begin(int id) {
        framebuffer = id;
    }

    public static void end() {
        framebuffer = NONE;
    }

    public static boolean isActive(int id) {
        return framebuffer == id;
    }
}
