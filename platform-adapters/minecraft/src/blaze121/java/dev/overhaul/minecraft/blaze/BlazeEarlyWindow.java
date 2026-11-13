package dev.overhaul.minecraft.blaze;

import org.lwjgl.glfw.Callbacks;
import org.lwjgl.glfw.GLFW;

/**
 * FML's early loading screen hands the game an OpenGL window, and a Vulkan surface cannot be made on one.
 * Its window is swapped for one without a client API, so {@code earlyWindowControl} needs no setting in a player's config.
 */
public final class BlazeEarlyWindow {
    private static boolean replaced;

    private BlazeEarlyWindow() {}

    /** Thirty frames a second is plenty for a plain colour and leaves the loading threads the CPU. */
    private static final long FRAME_NANOS = 33_000_000L;
    private static long lastFrame;
    private static boolean paintFailed;

    /**
     * The early screen's tick while mods load: pump events and keep the window filled with the loading screen's
     * background, which an unpainted window would otherwise show as white.
     */
    public static void tick() {
        GLFW.glfwPollEvents();
        long now = System.nanoTime();
        if (now - lastFrame < FRAME_NANOS) return;
        lastFrame = now;
        var renderer = BlazeFrontend.current();
        if (renderer == null || paintFailed) return;
        try {
            int color = background();
            renderer.presentSolid((color >> 16 & 0xFF) / 255f, (color >> 8 & 0xFF) / 255f, (color & 0xFF) / 255f);
        } catch (RuntimeException failure) {
            // A plain colour is cosmetic: a window left unpainted beats a failed start.
            paintFailed = true;
            org.slf4j.LoggerFactory.getLogger(BlazeEarlyWindow.class).warn("Overhaul stopped painting the window while mods load", failure);
        }
    }

    /** The colour LoadingOverlay draws next: Mojang red, or black with the dark background option. */
    private static int background() {
        var client = net.minecraft.client.Minecraft.getInstance();
        var options = client == null ? null : client.options;
        return options != null && options.darkMojangStudiosBackground().get() ? 0x000000 : 0xEF323D;
    }

    public static boolean replaced() {
        return replaced;
    }

    /** With the early screen off FML has already created the window from the game's hints, and it is kept. */
    public static long replace(long handed, int width, int height, String title, long monitor) {
        if (GLFW.glfwGetWindowAttrib(handed, GLFW.GLFW_CLIENT_API) == GLFW.GLFW_NO_API) return handed;
        replaced = true;
        GLFW.glfwMakeContextCurrent(0L);
        Callbacks.glfwFreeCallbacks(handed);
        GLFW.glfwDestroyWindow(handed);
        // The early screen's start thread shares GLFW's hints and may have set its own after the game's.
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_NO_API);
        long window = GLFW.glfwCreateWindow(width, height, title, monitor, 0L);
        if (window == 0L) throw new IllegalStateException("GLFW could not create a window for Vulkan");
        return window;
    }
}
