package dev.overhaul.minecraft.blaze;

import static org.lwjgl.vulkan.VK10.*;

import dev.overhaul.vulkan.VulkanContext;
import dev.overhaul.vulkan.VulkanStack;
import java.nio.LongBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;

/**
 * GPU time of whole frames: one timestamp as a frame's commands begin, one after its GUI, in a ring of query pools
 * read a few frames later without waiting. A result that is not ready yet is dropped. Both marks fall between frames,
 * where no render pass is open, so they cost no draw merging. Owner thread only.
 */
final class BlazeFrameGpuTimer implements AutoCloseable {
    private static final int SLOTS = 4;
    private final VulkanContext context;
    private final long[] pools = new long[SLOTS];
    private final boolean[] started = new boolean[SLOTS], ended = new boolean[SLOTS];
    private final double period;
    private int slot;
    private double millis = -1;

    BlazeFrameGpuTimer(VulkanContext context) {
        this.context = context;
        try (MemoryStack stack = VulkanStack.push()) {
            var properties = VkPhysicalDeviceProperties.malloc(stack);
            vkGetPhysicalDeviceProperties(context.physicalDevice(), properties);
            boolean usable = properties.limits().timestampComputeAndGraphics() && properties.limits().timestampPeriod() > 0;
            period = properties.limits().timestampPeriod();
            if (!usable) return;
            var info = VkQueryPoolCreateInfo.calloc(stack).sType$Default().queryType(VK_QUERY_TYPE_TIMESTAMP).queryCount(2);
            LongBuffer pointer = stack.mallocLong(1);
            for (int i = 0; i < SLOTS; i++) {
                if (vkCreateQueryPool(context.device(), info, null, pointer) != VK_SUCCESS) {
                    close();
                    return;
                }
                pools[i] = pointer.get(0);
            }
        }
    }

    /** @return the last frame's GPU time in milliseconds, or -1 when none has been read */
    double millis() {
        return millis;
    }

    /** @return the mark that opens this frame, or null when the device has no timestamps */
    BlazeQueue.Operation start() {
        int at = slot;
        if (pools[at] == 0) return null;
        started[at] = true;
        ended[at] = false;
        return stamp(at, 0);
    }

    /** @return the mark that closes this frame, or null when the frame was not opened */
    BlazeQueue.Operation end() {
        int at = slot;
        if (pools[at] == 0 || !started[at]) return null;
        ended[at] = true;
        return stamp(at, 1);
    }

    /** Moves to the next slot and reads the frame that used it last, if the GPU has finished it. */
    void advance() {
        slot = (slot + 1) % SLOTS;
        if (pools[slot] == 0 || !ended[slot]) return;
        started[slot] = ended[slot] = false;
        try (MemoryStack stack = VulkanStack.push()) {
            LongBuffer values = stack.mallocLong(2);
            if (vkGetQueryPoolResults(context.device(), pools[slot], 0, 2, values, Long.BYTES, VK_QUERY_RESULT_64_BIT) != VK_SUCCESS) return;
            long ticks = values.get(1) - values.get(0);
            if (ticks > 0) millis = ticks * period / 1e6;
        }
    }

    private BlazeQueue.Operation stamp(int pool, int query) {
        long handle = pools[pool];
        return new BlazeQueue.Operation() {
            @Override public void record(VkCommandBuffer command) {
                if (query == 0) vkCmdResetQueryPool(command, handle, 0, 2);
                vkCmdWriteTimestamp(command, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, handle, query);
            }

            @Override public void close() {}
        };
    }

    @Override public void close() {
        for (int i = 0; i < SLOTS; i++) {
            if (pools[i] != 0) vkDestroyQueryPool(context.device(), pools[i], null);
            pools[i] = 0;
        }
    }
}
