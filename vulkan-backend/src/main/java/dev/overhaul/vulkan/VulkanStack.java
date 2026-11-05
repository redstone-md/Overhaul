package dev.overhaul.vulkan;

import org.lwjgl.system.MemoryStack;

/**
 * The backend's own per-thread stack. LWJGL sizes its shared stack once, from a JVM flag, usually before any mod runs,
 * and its 64 KiB default is too small for device enumeration on an ordinary game launch.
 */
public final class VulkanStack {
    private static final int BYTES = 1024 * 1024;
    private static final ThreadLocal<MemoryStack> STACK = ThreadLocal.withInitial(() -> MemoryStack.create(BYTES));

    private VulkanStack() {}

    public static MemoryStack push() {
        return STACK.get().push();
    }
}
