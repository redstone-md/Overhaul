package dev.overhaul.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkInstance;
import java.nio.LongBuffer;
import java.util.List;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.glfw.GLFWVulkan.*;
import static org.lwjgl.system.MemoryUtil.memAddress;
import static dev.overhaul.vulkan.VulkanException.check;

/** Borrows an initialized GLFW no-API window without changing or destroying it. */
public final class GlfwSurfaceProvider implements SurfaceProvider {
    private final long window;

    public GlfwSurfaceProvider(long window) {
        if (window == 0) throw new IllegalArgumentException("window is null");
        this.window = window;
    }

    @Override public List<String> requiredInstanceExtensions() {
        if (glfwGetWindowAttrib(window, GLFW_CLIENT_API) != GLFW_NO_API)
            throw new IllegalArgumentException("GLFW surface requires a no-API window");
        return SurfaceExtensions.copy(glfwGetRequiredInstanceExtensions());
    }

    @Override public long createSurface(VkInstance instance) {
        try (MemoryStack stack = VulkanStack.push()) {
            LongBuffer handle = stack.mallocLong(1);
            // GLFW may live in the loader's parent layer, which cannot resolve VkInstance.
            check(nglfwCreateWindowSurface(instance.address(), window, 0L, memAddress(handle)),
                    "glfwCreateWindowSurface");
            return handle.get(0);
        }
    }
}
