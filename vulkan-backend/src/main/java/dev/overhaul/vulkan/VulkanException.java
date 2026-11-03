package dev.overhaul.vulkan;

/** Preserves the native VkResult rather than hiding device failures. */
public final class VulkanException extends RuntimeException {
    private final int result;

    public VulkanException(String operation, int result) {
        super(operation + " failed with VkResult " + result);
        this.result = result;
    }

    public int result() { return result; }

    public static void check(int result, String operation) {
        if (result != 0) throw new VulkanException(operation, result);
    }
}
