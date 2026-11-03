package dev.overhaul.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.function.Consumer;

import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;
import static dev.overhaul.vulkan.VulkanException.check;

/** FIFO presentation with frame-owned acquire semaphores and image-owned presentation semaphores. */
public final class SwapchainPresenter implements AutoCloseable {
    private final VulkanContext context;
    private final FrameRing frames;
    private final long[] acquired = new long[3];
    private long[] finished = new long[0];
    private long swapchain;
    private long[] images;
    private int width;
    private int height;
    private boolean destinationBlitSupported;
    private boolean suspended;
    private boolean closed;
    private boolean needsResize;
    private boolean dirtyAcquire;

    public SwapchainPresenter(VulkanContext context, int width, int height) {
        this.context = context;
        if (context.surface() == 0) throw new IllegalArgumentException("presentation requires a surface");
        frames = new FrameRing(context, acquired.length);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            createSemaphores(acquired, stack);
            resize(width, height);
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    public void resize(int requestedWidth, int requestedHeight) {
        requireOpen();
        if (requestedWidth < 1 || requestedHeight < 1) { suspended = true; needsResize = true; return; }
        context.waitIdle();
        frames.drain();
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
            if (nextWidth == 0 || nextHeight == 0) { suspended = true; needsResize = true; return; }
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
            destinationBlitSupported = supportsFormat(format, VK_FORMAT_FEATURE_BLIT_DST_BIT, stack);
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
            destroySemaphores(finished);
            check(vkGetSwapchainImagesKHR(context.device(), swapchain, count, null), "swapchain image count");
            LongBuffer handles = stack.mallocLong(count.get(0));
            check(vkGetSwapchainImagesKHR(context.device(), swapchain, count, handles), "swapchain images");
            images = new long[count.get(0)];
            handles.get(images);
            finished = new long[images.length];
            createSemaphores(finished, stack);
            if (dirtyAcquire) {
                destroySemaphores(acquired);
                createSemaphores(acquired, stack);
                dirtyAcquire = false;
            }
            width = nextWidth;
            height = nextHeight;
            suspended = false;
            needsResize = false;
        } catch (RuntimeException | Error failure) {
            // Creating a replacement may retire the old swapchain even if replacement creation fails.
            needsResize = true;
            suspended = true;
            throw failure;
        }
    }

    /** Queues a clear with the same semaphore scheduling used by rendered image presentation. */
    public boolean presentClear(float red, float green, float blue) {
        return present((command, image) -> {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkClearColorValue color = VkClearColorValue.calloc(stack)
                    .float32(0, red).float32(1, green).float32(2, blue).float32(3, 1);
                VkImageSubresourceRange.Buffer range = VkImageSubresourceRange.calloc(1, stack)
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
                vkCmdClearColorImage(command, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, color, range);
            }
        }, completion -> { });
    }

    boolean presentImage(VulkanContext sourceContext, ColorTarget source, Consumer<FrameRing.Submission> onSubmitted) {
        requireOpen();
        if (sourceContext != context) throw new IllegalArgumentException("rendered image belongs to another Vulkan context");
        if (swapchain == 0 || suspended) return false;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (!destinationBlitSupported || !supportsFormat(VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_FEATURE_BLIT_SRC_BIT, stack))
                throw new IllegalStateException("source/destination formats do not support GPU blitting");
        }
        return present((command, image) -> source.blitTo(command, image, width, height), onSubmitted);
    }

    private boolean supportsFormat(int format, int feature, MemoryStack stack) {
        VkFormatProperties properties = VkFormatProperties.malloc(stack);
        vkGetPhysicalDeviceFormatProperties(context.physicalDevice(), format, properties);
        return (properties.optimalTilingFeatures() & feature) != 0;
    }

    private boolean present(ImageWriter writeImage, Consumer<FrameRing.Submission> onSubmitted) {
        requireOpen();
        if (swapchain == 0 || suspended) return false;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer index = stack.mallocInt(1);
            FrameRing.Submission submission = frames.submit(frame -> {
                int acquire = vkAcquireNextImageKHR(context.device(), swapchain, 1_000_000_000L,
                    acquired[frame.slot()], VK_NULL_HANDLE, index);
                if (acquire == VK_ERROR_OUT_OF_DATE_KHR || acquire == VK_TIMEOUT || acquire == VK_NOT_READY) {
                    if (acquire == VK_ERROR_OUT_OF_DATE_KHR) { needsResize = true; suspended = true; }
                    frame.cancel();
                    return;
                }
                if (acquire != VK_SUBOPTIMAL_KHR) check(acquire, "vkAcquireNextImageKHR");
                else needsResize = true;
                frame.waitFor(acquired[frame.slot()], VK_PIPELINE_STAGE_TRANSFER_BIT);
                frame.signal(finished[index.get(0)]);
                long image = images[index.get(0)];
                try (MemoryStack recording = MemoryStack.stackPush()) {
                    transition(frame.command(), image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        0, VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, recording);
                    writeImage.write(frame.command(), image);
                    transition(frame.command(), image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                        VK_ACCESS_TRANSFER_WRITE_BIT, 0, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, recording);
                }
            });
            if (submission == null) return false;
            // Source-image lifetime must be pinned even when the subsequent present reports an error.
            onSubmitted.accept(submission);
            VkPresentInfoKHR present = VkPresentInfoKHR.calloc(stack).sType$Default()
                .pWaitSemaphores(stack.longs(finished[index.get(0)]))
                .swapchainCount(1).pSwapchains(stack.longs(swapchain)).pImageIndices(index);
            int result = vkQueuePresentKHR(context.queue(), present);
            if (result != VK_SUBOPTIMAL_KHR && result != VK_ERROR_OUT_OF_DATE_KHR) check(result, "vkQueuePresentKHR");
            if (result == VK_SUBOPTIMAL_KHR || result == VK_ERROR_OUT_OF_DATE_KHR) needsResize = true;
            if (result == VK_ERROR_OUT_OF_DATE_KHR) suspended = true;
            return !needsResize;
        } catch (RuntimeException | Error failure) {
            // An acquired semaphore may be signaled without a consuming submission after recording fails.
            dirtyAcquire = true;
            needsResize = true;
            suspended = true;
            throw failure;
        }
    }

    private void createSemaphores(long[] storage, MemoryStack stack) {
        LongBuffer pointer = stack.mallocLong(1);
        VkSemaphoreCreateInfo info = VkSemaphoreCreateInfo.calloc(stack).sType$Default();
        for (int i = 0; i < storage.length; i++) {
            check(vkCreateSemaphore(context.device(), info, null, pointer), "presentation semaphore");
            storage[i] = pointer.get(0);
        }
    }

    private void destroySemaphores(long[] storage) {
        for (int i = 0; i < storage.length; i++) {
            if (storage[i] != 0) vkDestroySemaphore(context.device(), storage[i], null);
            storage[i] = 0;
        }
    }

    @FunctionalInterface private interface ImageWriter {
        void write(VkCommandBuffer command, long image);
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
    public boolean needsResize() { requireOpen(); return needsResize; }

    @Override public void close() {
        if (closed) return;
        context.requireOwner();
        context.waitIdle();
        frames.close();
        if (swapchain != 0) vkDestroySwapchainKHR(context.device(), swapchain, null);
        destroySemaphores(finished);
        destroySemaphores(acquired);
        closed = true;
    }
}
