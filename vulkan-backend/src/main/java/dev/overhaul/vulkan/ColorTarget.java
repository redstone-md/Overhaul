package dev.overhaul.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;
import static dev.overhaul.vulkan.VulkanException.check;

/** Offscreen color attachment whose render pass ends ready for a transfer readback. */
final class ColorTarget implements AutoCloseable {
    private final VulkanContext context;
    final int width;
    final int height;
    private AttachmentImage color;
    private AttachmentImage depth;
    long renderPass;
    long framebuffer;

    ColorTarget(VulkanContext context, int width, int height) {
        if (width < 1 || height < 1 || (long) width * height * 4 > Integer.MAX_VALUE)
            throw new IllegalArgumentException("invalid target dimensions");
        this.context = context;
        this.width = width;
        this.height = height;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer pointer = stack.mallocLong(1);
            color = new AttachmentImage(context, width, height, VK_FORMAT_R8G8B8A8_UNORM,
                VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT, VK_IMAGE_ASPECT_COLOR_BIT);
            VkFormatProperties support = VkFormatProperties.malloc(stack);
            vkGetPhysicalDeviceFormatProperties(context.physicalDevice(), VK_FORMAT_D32_SFLOAT, support);
            if ((support.optimalTilingFeatures() & VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT) == 0)
                throw new IllegalStateException("D32 depth attachments are unsupported");
            depth = new AttachmentImage(context, width, height, VK_FORMAT_D32_SFLOAT,
                VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT, VK_IMAGE_ASPECT_DEPTH_BIT);
            createRenderPass(stack, pointer);
            VkFramebufferCreateInfo framebufferInfo = VkFramebufferCreateInfo.calloc(stack).sType$Default()
                .renderPass(renderPass).pAttachments(stack.longs(color.view, depth.view)).width(width).height(height).layers(1);
            check(vkCreateFramebuffer(context.device(), framebufferInfo, null, pointer), "vkCreateFramebuffer");
            framebuffer = pointer.get(0);
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    private void createRenderPass(MemoryStack stack, LongBuffer pointer) {
        VkAttachmentDescription.Buffer attachments = VkAttachmentDescription.calloc(2, stack);
        attachments.get(0)
            .format(VK_FORMAT_R8G8B8A8_UNORM).samples(VK_SAMPLE_COUNT_1_BIT)
            .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_STORE)
            .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
            .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED).finalLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
        attachments.get(1).format(VK_FORMAT_D32_SFLOAT).samples(VK_SAMPLE_COUNT_1_BIT)
            .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
            .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
            .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED).finalLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
        VkAttachmentReference.Buffer color = VkAttachmentReference.calloc(1, stack)
            .attachment(0).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        VkSubpassDescription.Buffer subpasses = VkSubpassDescription.calloc(1, stack)
            .pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS).colorAttachmentCount(1).pColorAttachments(color);
        subpasses.pDepthStencilAttachment(VkAttachmentReference.calloc(stack).attachment(1)
            .layout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL));
        VkSubpassDependency.Buffer dependencies = VkSubpassDependency.calloc(2, stack);
        dependencies.get(0).srcSubpass(VK_SUBPASS_EXTERNAL).dstSubpass(0)
            .srcStageMask(VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT)
            .dstStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT)
            .dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);
        dependencies.get(1).srcSubpass(0).dstSubpass(VK_SUBPASS_EXTERNAL)
            .srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT).dstStageMask(VK_PIPELINE_STAGE_TRANSFER_BIT)
            .srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT).dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT);
        VkRenderPassCreateInfo info = VkRenderPassCreateInfo.calloc(stack).sType$Default()
            .pAttachments(attachments).pSubpasses(subpasses).pDependencies(dependencies);
        check(vkCreateRenderPass(context.device(), info, null, pointer), "vkCreateRenderPass");
        renderPass = pointer.get(0);
    }

    void copyTo(VkCommandBuffer command, GpuBuffer readback) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
            region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            region.imageExtent().set(width, height, 1);
            vkCmdCopyImageToBuffer(command, color.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, readback.handle(), region);
            VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack).sType$Default()
                .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT);
            vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT,
                0, barrier, null, null);
        }
    }

    void blitTo(VkCommandBuffer command, long destination, int destinationWidth, int destinationHeight) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack).sType$Default()
                .image(color.image).oldLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).newLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
                .srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT).dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
            barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                0, null, null, barrier);
            VkImageBlit.Buffer region = VkImageBlit.calloc(1, stack);
            region.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            region.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            region.srcOffsets(1).set(width, height, 1);
            region.dstOffsets(1).set(destinationWidth, destinationHeight, 1);
            vkCmdBlitImage(command, color.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                destination, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region, VK_FILTER_NEAREST);
        }
    }

    @Override public void close() {
        context.requireOwner();
        if (framebuffer != 0) vkDestroyFramebuffer(context.device(), framebuffer, null);
        if (renderPass != 0) vkDestroyRenderPass(context.device(), renderPass, null);
        if (depth != null) depth.close();
        if (color != null) color.close();
        framebuffer = renderPass = 0;
    }
}
