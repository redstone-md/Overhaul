package dev.overhaul.vulkan;

import org.lwjgl.vulkan.VkInstance;
import java.util.List;

/**
 * Borrows a caller-owned native window/runtime. The context owns only the created Vulkan surface.
 * The window and its runtime must remain alive until the context closes, on the window's owner thread.
 */
public interface SurfaceProvider {
    List<String> requiredInstanceExtensions();
    long createSurface(VkInstance instance);

    default void destroySurface(VkInstance instance, long surface) {
        org.lwjgl.vulkan.KHRSurface.vkDestroySurfaceKHR(instance, surface, null);
    }
}
