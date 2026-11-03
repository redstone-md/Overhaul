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
    private final long[] manualAcquire=new long[1];
    private int manualIndex=-1;
    private boolean manualBlitted;

    public SwapchainPresenter(VulkanContext context, int width, int height) {
        this.context = context;
        if (context.surface() == 0) throw new IllegalArgumentException("presentation requires a surface");
        frames = new FrameRing(context, acquired.length);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            createSemaphores(acquired, stack);
            createSemaphores(manualAcquire,stack);
            resize(width, height);
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    public void resize(int requestedWidth, int requestedHeight) {
        requireOpen();
        if (requestedWidth < 1 || requestedHeight < 1) { suspended = true; needsResize = true; return; }
        abandonManualAcquire();
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
                destroySemaphores(manualAcquire);createSemaphores(manualAcquire,stack);
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
        if(manualIndex!=-1)throw new IllegalStateException("a manually acquired image has not been presented");
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
                writeAcquired(frame,index.get(0),acquired[frame.slot()],writeImage);
            });
            if (submission == null) return false;
            // Source-image lifetime must be pinned even when the subsequent present reports an error.
            onSubmitted.accept(submission);
            return presentIndex(index.get(0));
        } catch (RuntimeException | Error failure) {
            // An acquired semaphore may be signaled without a consuming submission after recording fails.
            dirtyAcquire = true;
            needsResize = true;
            suspended = true;
            throw failure;
        }
    }

    /** Acquires a real WSI image now; a later encoder fence must include blitAcquiredImage's work. */
    public boolean acquireNextTexture(){
        requireOpen();if(manualIndex!=-1)throw new IllegalStateException("a surface image is already acquired");
        if(swapchain==0||suspended)return false;
        try(MemoryStack stack=MemoryStack.stackPush()){
            IntBuffer index=stack.mallocInt(1);
            int result=vkAcquireNextImageKHR(context.device(),swapchain,1_000_000_000L,manualAcquire[0],VK_NULL_HANDLE,index);
            if(result==VK_ERROR_OUT_OF_DATE_KHR){needsResize=true;suspended=true;return false;}
            if(result==VK_TIMEOUT||result==VK_NOT_READY)return false;
            if(result!=VK_SUBOPTIMAL_KHR)check(result,"manual surface acquire");else needsResize=true;
            manualIndex=index.get(0);manualBlitted=false;return true;
        }
    }

    /** Completes the GPU blit before returning, so an immediately created completed encoder fence is truthful. */
    public void blitAcquiredImage(GpuImageView source){
        blitAcquiredImage(source,false);
    }

    /** Render frontends with a bottom-left image origin request a vertical presentation flip. */
    public void blitAcquiredImage(GpuImageView source,boolean flipY){
        requireOpen();if(manualIndex==-1||manualBlitted)throw new IllegalStateException("surface needs one acquired, unfilled image");
        GpuImage image=source.image();if(image.context()!=context)throw new IllegalArgumentException("source image owner differs");
        if(source.layerCount()!=1||image.format().aspectMask()!=VK_IMAGE_ASPECT_COLOR_BIT)throw new IllegalArgumentException("surface blit needs one color layer");
        if(image.format().name().endsWith("UINT")||image.format().name().endsWith("SINT"))throw new IllegalArgumentException("surface color conversion does not accept integer formats");
        image.requireUsage(VK_IMAGE_USAGE_TRANSFER_SRC_BIT);int mip=source.baseMip(),layer=source.baseLayer();
        int previous=image.layout(mip,layer);if(previous==VK_IMAGE_LAYOUT_UNDEFINED)throw new IllegalStateException("source image has not been initialized");
        try(MemoryStack stack=MemoryStack.stackPush()){
            if(!destinationBlitSupported||!supportsFormat(image.format().vkFormat(),VK_FORMAT_FEATURE_BLIT_SRC_BIT,stack))throw new IllegalArgumentException("surface blit format unsupported");
        }
        java.util.List<GpuImage.LayoutChange> changes=new java.util.ArrayList<>();
        try{
            FrameRing.Submission completion=frames.submit(frame->writeAcquired(frame,manualIndex,manualAcquire[0],(command,destination)->{
                changes.add(image.recordTransition(command,mip,1,layer,1,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL));
                try(MemoryStack stack=MemoryStack.stackPush()){
                    VkImageBlit.Buffer region=VkImageBlit.calloc(1,stack);
                    region.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(mip).baseArrayLayer(layer).layerCount(1);
                    region.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                    region.srcOffsets(1).set(image.width(mip),image.height(mip),1);region.dstOffsets(1).set(width,height,1);
                    if(flipY){region.dstOffsets(0).y(height);region.dstOffsets(1).y(0);}
                    vkCmdBlitImage(command,image.handle(),VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,destination,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,region,VK_FILTER_NEAREST);
                }
                changes.add(image.recordTransition(command,mip,1,layer,1,previous));
            }));
            manualBlitted=true;for(GpuImage.LayoutChange change:changes)change.commit();image.retainUntil(completion);completion.await();
        }catch(RuntimeException|Error failure){dirtyAcquire=true;needsResize=true;suspended=true;throw failure;}
        finally{for(int i=changes.size()-1;i>=0;i--)changes.get(i).close();}
    }

    public boolean presentAcquired(){
        requireOpen();if(manualIndex==-1||!manualBlitted)throw new IllegalStateException("surface image was not acquired and filled");
        try{return presentIndex(manualIndex);}finally{manualIndex=-1;manualBlitted=false;}
    }
    public boolean present(GpuImageView source){if(!acquireNextTexture())return false;blitAcquiredImage(source);return presentAcquired();}

    private void writeAcquired(FrameRing.Frame frame,int index,long acquire,ImageWriter writer){
        frame.waitFor(acquire,VK_PIPELINE_STAGE_TRANSFER_BIT);frame.signal(finished[index]);long image=images[index];
        try(MemoryStack stack=MemoryStack.stackPush()){
            transition(frame.command(),image,VK_IMAGE_LAYOUT_UNDEFINED,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,0,VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,stack);
            writer.write(frame.command(),image);
            transition(frame.command(),image,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,VK_ACCESS_TRANSFER_WRITE_BIT,0,
                VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,stack);
        }
    }
    private boolean presentIndex(int index){
        try(MemoryStack stack=MemoryStack.stackPush()){
            VkPresentInfoKHR info=VkPresentInfoKHR.calloc(stack).sType$Default().pWaitSemaphores(stack.longs(finished[index]))
                .swapchainCount(1).pSwapchains(stack.longs(swapchain)).pImageIndices(stack.ints(index));
            int result=vkQueuePresentKHR(context.queue(),info);
            if(result!=VK_SUBOPTIMAL_KHR&&result!=VK_ERROR_OUT_OF_DATE_KHR)check(result,"vkQueuePresentKHR");
            if(result==VK_SUBOPTIMAL_KHR||result==VK_ERROR_OUT_OF_DATE_KHR)needsResize=true;
            if(result==VK_ERROR_OUT_OF_DATE_KHR)suspended=true;
            return !needsResize;
        }
    }
    private void abandonManualAcquire(){
        if(manualIndex==-1)return;
        if(!manualBlitted)frames.submit(frame->frame.waitFor(manualAcquire[0],VK_PIPELINE_STAGE_TRANSFER_BIT)).await();
        manualIndex=-1;manualBlitted=false;
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
        abandonManualAcquire();
        context.waitIdle();
        frames.close();
        if (swapchain != 0) vkDestroySwapchainKHR(context.device(), swapchain, null);
        destroySemaphores(finished);
        destroySemaphores(acquired);
        destroySemaphores(manualAcquire);
        closed = true;
    }
}
