package dev.overhaul.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import static org.lwjgl.vulkan.VK10.*;
import static dev.overhaul.vulkan.VulkanException.check;

/** Uploads once through a fence-completed staging allocation; owns sampler and image. */
final class SampledTexture implements AutoCloseable {
    private final VulkanContext context;
    private AttachmentImage image;
    long sampler;

    SampledTexture(VulkanContext context,GpuAllocator allocator,ImmediateCommands commands,PackTextureData data) {
        this.context=context;
        try (MemoryStack stack=MemoryStack.stackPush()) {
            VkFormatProperties properties=VkFormatProperties.malloc(stack);
            vkGetPhysicalDeviceFormatProperties(context.physicalDevice(),VK_FORMAT_R8G8B8A8_UNORM,properties);
            int required=VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT;
            if (data.filter()==PackTextureData.Filter.LINEAR) required|=VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT;
            if ((properties.optimalTilingFeatures()&required)!=required) throw new IllegalStateException("RGBA8 texture filtering unsupported");
            image=new AttachmentImage(context,data.width(),data.height(),VK_FORMAT_R8G8B8A8_UNORM,
                    VK_IMAGE_USAGE_SAMPLED_BIT|VK_IMAGE_USAGE_TRANSFER_DST_BIT,VK_IMAGE_ASPECT_COLOR_BIT);
            byte[] pixels=data.rgba();
            try (GpuBuffer staging=allocator.hostBuffer(pixels.length,VK_BUFFER_USAGE_TRANSFER_SRC_BIT)) {
                staging.write(ByteBuffer.wrap(pixels));
                commands.submit(command->{
                    transition(command,VK_IMAGE_LAYOUT_UNDEFINED,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,0,VK_ACCESS_TRANSFER_WRITE_BIT,
                            VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT);
                    try (MemoryStack copyStack=MemoryStack.stackPush()) {
                        VkBufferImageCopy.Buffer copy=VkBufferImageCopy.calloc(1,copyStack);
                        copy.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                        copy.imageExtent().set(data.width(),data.height(),1);
                        vkCmdCopyBufferToImage(command,staging.handle(),image.image,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,copy);
                    }
                    transition(command,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                            VK_ACCESS_TRANSFER_WRITE_BIT,VK_ACCESS_SHADER_READ_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,
                            VK_PIPELINE_STAGE_VERTEX_SHADER_BIT|VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT);
                });
            }
            int filter=data.filter()==PackTextureData.Filter.LINEAR ? VK_FILTER_LINEAR : VK_FILTER_NEAREST;
            int wrap=data.wrap()==PackTextureData.Wrap.REPEAT ? VK_SAMPLER_ADDRESS_MODE_REPEAT : VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
            VkSamplerCreateInfo info=VkSamplerCreateInfo.calloc(stack).sType$Default().magFilter(filter).minFilter(filter)
                    .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST).addressModeU(wrap).addressModeV(wrap).addressModeW(wrap)
                    .minLod(0).maxLod(0).borderColor(VK_BORDER_COLOR_FLOAT_TRANSPARENT_BLACK);
            LongBuffer pointer=stack.mallocLong(1);
            check(vkCreateSampler(context.device(),info,null,pointer),"vkCreateSampler"); sampler=pointer.get(0);
        } catch (RuntimeException|Error failure) { close(); throw failure; }
    }
    long view() { return image.view; }
    private void transition(VkCommandBuffer command,int oldLayout,int newLayout,int sourceAccess,int destinationAccess,int sourceStage,int destinationStage) {
        try (MemoryStack stack=MemoryStack.stackPush()) {
            VkImageMemoryBarrier.Buffer barrier=VkImageMemoryBarrier.calloc(1,stack).sType$Default()
                    .oldLayout(oldLayout).newLayout(newLayout).srcAccessMask(sourceAccess).dstAccessMask(destinationAccess)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).image(image.image);
            barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            vkCmdPipelineBarrier(command,sourceStage,destinationStage,0,null,null,barrier);
        }
    }
    @Override public void close() {
        context.requireOwner();
        if (sampler!=0) vkDestroySampler(context.device(),sampler,null);
        sampler=0;
        if (image!=null) { image.close(); image=null; }
    }
}
