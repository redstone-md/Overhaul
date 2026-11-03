package dev.overhaul.vulkan;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;
import java.util.function.Consumer;

import static org.lwjgl.vulkan.VK10.*;
import static dev.overhaul.vulkan.VulkanException.check;

/** Fence-backed synchronous transfer/verification lane, separate from frame submission. */
public final class ImmediateCommands implements AutoCloseable {
    private final VulkanContext context;
    private final VkCommandBuffer command;
    private long pool;
    private long fence;

    public ImmediateCommands(VulkanContext context) {
        this.context = context;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer pointer = stack.mallocLong(1);
            VkCommandPoolCreateInfo info = VkCommandPoolCreateInfo.calloc(stack).sType$Default()
                .queueFamilyIndex(context.queueFamily()).flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT);
            check(vkCreateCommandPool(context.device(), info, null, pointer), "vkCreateCommandPool");
            pool = pointer.get(0);
            VkCommandBufferAllocateInfo allocate = VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                .commandPool(pool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1);
            PointerBuffer commands = stack.mallocPointer(1);
            check(vkAllocateCommandBuffers(context.device(), allocate, commands), "vkAllocateCommandBuffers");
            command = new VkCommandBuffer(commands.get(0), context.device());
            check(vkCreateFence(context.device(), VkFenceCreateInfo.calloc(stack).sType$Default(), null, pointer), "vkCreateFence");
            fence = pointer.get(0);
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    public void submit(Consumer<VkCommandBuffer> recording) {
        context.requireOwner();
        if (pool == 0) throw new IllegalStateException("command lane closed");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            check(vkResetCommandPool(context.device(), pool, 0), "vkResetCommandPool");
            check(vkBeginCommandBuffer(command, VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "vkBeginCommandBuffer");
            recording.accept(command);
            check(vkEndCommandBuffer(command), "vkEndCommandBuffer");
            check(vkResetFences(context.device(), fence), "vkResetFences");
            VkSubmitInfo info = VkSubmitInfo.calloc(stack).sType$Default().pCommandBuffers(stack.pointers(command));
            check(vkQueueSubmit(context.queue(), info, fence), "vkQueueSubmit");
            check(vkWaitForFences(context.device(), fence, true, -1L), "vkWaitForFences");
        }
    }

    public void copy(GpuBuffer source, GpuBuffer target, long size) {
        if (size < 1 || size > source.size() || size > target.size()) throw new IllegalArgumentException("copy out of bounds");
        submit(command -> {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkBufferCopy.Buffer copy = VkBufferCopy.calloc(1, stack).size(size);
                vkCmdCopyBuffer(command, source.handle(), target.handle(), copy);
                VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_HOST_READ_BIT);
                vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_ALL_COMMANDS_BIT | VK_PIPELINE_STAGE_HOST_BIT, 0, barrier, null, null);
            }
        });
    }

    @Override public void close() {
        if (pool == 0) return;
        context.requireOwner();
        context.waitIdle();
        if (fence != 0) vkDestroyFence(context.device(), fence, null);
        vkDestroyCommandPool(context.device(), pool, null);
        pool = 0;
        fence = 0;
    }
}
