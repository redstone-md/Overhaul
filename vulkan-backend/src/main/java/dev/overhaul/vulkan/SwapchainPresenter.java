package dev.overhaul.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;
import static dev.overhaul.vulkan.VulkanException.check;

/** Surface lifecycle and FIFO presentation smoke lane; resize recreates the swapchain. */
public final class SwapchainPresenter implements AutoCloseable {
    private final VulkanContext context;
    private final ImmediateCommands commands;
    private long swapchain;
    private long acquireFence;
    private long[] images;
    private int width;
    private int height;
    private boolean closed;

    public SwapchainPresenter(VulkanContext context, int width, int height) {
        this.context = context;
        if (context.surface() == 0) throw new IllegalArgumentException("presentation requires a surface");
        commands = new ImmediateCommands(context);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer pointer = stack.mallocLong(1);
            check(vkCreateFence(context.device(), VkFenceCreateInfo.calloc(stack).sType$Default(), null, pointer), "acquire fence");
            acquireFence = pointer.get(0);
            resize(width, height);
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    public void resize(int requestedWidth, int requestedHeight) {
        requireOpen();
        if (requestedWidth < 1 || requestedHeight < 1) return;
        context.waitIdle();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSurfaceCapabilitiesKHR capabilities = VkSurfaceCapabilitiesKHR.malloc(stack);
            check(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(context.physicalDevice(), context.surface(), capabilities), "surface capabilities");
            if ((capabilities.supportedUsageFlags() & VK_IMAGE_USAGE_TRANSFER_DST_BIT) == 0)
                throw new IllegalStateException("surface does not support transfer destination images");
            int nextWidth = capabilities.currentExtent().width();
            int nextHeight = capabilities.currentExtent().height();
            if (nextWidth == -1) {
                nextWidth = Math.max(capabilities.minImageExtent().width(), Math.min(requestedWidth, capabilities.maxImageExtent().width()));
                nextHeight = Math.max(capabilities.minImageExtent().height(), Math.min(requestedHeight, capabilities.maxImageExtent().height()));
            }
            if (nextWidth == 0 || nextHeight == 0) return;
            IntBuffer count = stack.ints(0);
            check(vkGetPhysicalDeviceSurfaceFormatsKHR(context.physicalDevice(), context.surface(), count, null), "surface formats");
            if (count.get(0) == 0) throw new IllegalStateException("surface has no formats");
            VkSurfaceFormatKHR.Buffer formats = VkSurfaceFormatKHR.malloc(count.get(0), stack);
            check(vkGetPhysicalDeviceSurfaceFormatsKHR(context.physicalDevice(), context.surface(), count, formats), "surface formats");
            int format = formats.get(0).format();
            int colorSpace = formats.get(0).colorSpace();
            if (format == VK_FORMAT_UNDEFINED) format = VK_FORMAT_B8G8R8A8_UNORM;
            for (VkSurfaceFormatKHR available : formats) {
                if (available.format() == VK_FORMAT_B8G8R8A8_UNORM && available.colorSpace() == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
                    format = available.format(); colorSpace = available.colorSpace(); break;
                }
            }
            int imageCount = capabilities.minImageCount() + 1;
            if (capabilities.maxImageCount() > 0) imageCount = Math.min(imageCount, capabilities.maxImageCount());
            int alpha = Integer.lowestOneBit(capabilities.supportedCompositeAlpha());
            VkSwapchainCreateInfoKHR info = VkSwapchainCreateInfoKHR.calloc(stack).sType$Default()
                .surface(context.surface()).minImageCount(imageCount).imageFormat(format).imageColorSpace(colorSpace)
                .imageArrayLayers(1).imageUsage(VK_IMAGE_USAGE_TRANSFER_DST_BIT).imageSharingMode(VK_SHARING_MODE_EXCLUSIVE)
                .preTransform(capabilities.currentTransform()).compositeAlpha(alpha).presentMode(VK_PRESENT_MODE_FIFO_KHR)
                .clipped(true).oldSwapchain(swapchain);
            info.imageExtent().set(nextWidth, nextHeight);
            LongBuffer pointer = stack.mallocLong(1);
            check(vkCreateSwapchainKHR(context.device(), info, null, pointer), "vkCreateSwapchainKHR");
            long previous = swapchain;
            swapchain = pointer.get(0);
            if (previous != 0) vkDestroySwapchainKHR(context.device(), previous, null);
            check(vkGetSwapchainImagesKHR(context.device(), swapchain, count, null), "swapchain image count");
            LongBuffer handles = stack.mallocLong(count.get(0));
            check(vkGetSwapchainImagesKHR(context.device(), swapchain, count, handles), "swapchain images");
            images = new long[count.get(0)];
            handles.get(images);
            width = nextWidth;
            height = nextHeight;
        }
    }

    /** A synchronized clear/present check. Full scene frame scheduling is a separate renderer. */
    public boolean presentClear(float red, float green, float blue) {
        requireOpen();
        if (swapchain == 0) return false;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            check(vkResetFences(context.device(), acquireFence), "reset acquire fence");
            IntBuffer index = stack.mallocInt(1);
            int acquire = vkAcquireNextImageKHR(context.device(), swapchain, -1L, VK_NULL_HANDLE, acquireFence, index);
            if (acquire == VK_ERROR_OUT_OF_DATE_KHR) return false;
            if (acquire != VK_SUBOPTIMAL_KHR) check(acquire, "vkAcquireNextImageKHR");
            check(vkWaitForFences(context.device(), acquireFence, true, -1L), "wait acquired image");
            long image = images[index.get(0)];
            commands.submit(command -> {
                try (MemoryStack recording = MemoryStack.stackPush()) {
                    transition(command, image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        0, VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, recording);
                    VkClearColorValue color = VkClearColorValue.calloc(recording)
                        .float32(0, red).float32(1, green).float32(2, blue).float32(3, 1);
                    VkImageSubresourceRange.Buffer range = VkImageSubresourceRange.calloc(1, recording)
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
                    vkCmdClearColorImage(command, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, color, range);
                    transition(command, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                        VK_ACCESS_TRANSFER_WRITE_BIT, 0, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, recording);
                }
            });
            VkPresentInfoKHR present = VkPresentInfoKHR.calloc(stack).sType$Default()
                .swapchainCount(1).pSwapchains(stack.longs(swapchain)).pImageIndices(index);
            int result = vkQueuePresentKHR(context.queue(), present);
            if (result != VK_SUBOPTIMAL_KHR && result != VK_ERROR_OUT_OF_DATE_KHR) check(result, "vkQueuePresentKHR");
            check(vkQueueWaitIdle(context.queue()), "presentation idle");
            return acquire != VK_SUBOPTIMAL_KHR && result == VK_SUCCESS;
        }
    }

    private static void transition(VkCommandBuffer command, long image, int before, int after,
                                   int sourceAccess, int destinationAccess, int sourceStage, int destinationStage, MemoryStack stack) {
        VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack).sType$Default()
            .oldLayout(before).newLayout(after).srcAccessMask(sourceAccess).dstAccessMask(destinationAccess)
            .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).image(image);
        barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
        vkCmdPipelineBarrier(command, sourceStage, destinationStage, 0, null, null, barrier);
    }

    private void requireOpen() {
        context.requireOwner();
        if (closed) throw new IllegalStateException("presenter closed");
    }

    public int width() { requireOpen(); return width; }
    public int height() { requireOpen(); return height; }

    @Override public void close() {
        if (closed) return;
        context.requireOwner();
        context.waitIdle();
        if (swapchain != 0) vkDestroySwapchainKHR(context.device(), swapchain, null);
        if (acquireFence != 0) vkDestroyFence(context.device(), acquireFence, null);
        commands.close();
        closed = true;
    }
}
