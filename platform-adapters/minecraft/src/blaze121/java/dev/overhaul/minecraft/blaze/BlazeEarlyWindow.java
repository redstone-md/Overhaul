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
