package dev.overhaul.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import static org.lwjgl.vulkan.VK10.*;

/** Frame-slot staging storage: reused only after the slot fence, grown without touching geometry. */
final class IndirectUpdates implements AutoCloseable {
    private static final int REGIONS_PER_COPY = 256;
    private final GpuAllocator allocator;
    private final GpuBuffer[] staging;

    IndirectUpdates(GpuAllocator allocator, int frames) {
        this.allocator = allocator;
        staging = new GpuBuffer[frames];
    }

    void record(FrameRing.Frame frame, GpuBatch batch) {
        IndirectCommandState state = batch.commands;
        int bytes = Math.multiplyExact(state.changedCount(), 4);
        int slot = frame.slot();
        if (staging[slot] == null || staging[slot].size() < bytes) {
            if (staging[slot] != null) staging[slot].close();
            staging[slot] = allocator.hostBuffer(bytes, VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
        }
        GpuBuffer source = staging[slot];
        source.write(state.bytes());
        try (MemoryStack stack = VulkanStack.push()) {
            barrier(frame.command(), batch.indirect, VK_ACCESS_INDIRECT_COMMAND_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT, stack);
            for (int start = 0; start < state.changedCount(); start += REGIONS_PER_COPY) {
                int count = Math.min(REGIONS_PER_COPY, state.changedCount() - start);
                try (MemoryStack regionsStack = VulkanStack.push()) {
                    VkBufferCopy.Buffer regions = VkBufferCopy.calloc(count, regionsStack);
                    for (int i = 0; i < count; i++) regions.get(i).srcOffset((long) (start + i) * 4)
                        .dstOffset((long) state.changedDraw(start + i) * 20).size(4);
                    vkCmdCopyBuffer(frame.command(), source.handle(), batch.indirect.handle(), regions);
                }
            }
            barrier(frame.command(), batch.indirect, VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_INDIRECT_COMMAND_READ_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT, stack);
        }
    }

    private static void barrier(VkCommandBuffer command, GpuBuffer indirect, int sourceAccess, int destinationAccess,
                                int sourceStage, int destinationStage, MemoryStack stack) {
        VkBufferMemoryBarrier.Buffer barrier = VkBufferMemoryBarrier.calloc(1, stack).sType$Default()
            .buffer(indirect.handle()).offset(0).size(indirect.size()).srcAccessMask(sourceAccess).dstAccessMask(destinationAccess)
            .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
        vkCmdPipelineBarrier(command, sourceStage, destinationStage, 0, null, barrier, null);
    }

    @Override public void close() { for (GpuBuffer buffer : staging) if (buffer != null) buffer.close(); }
}
