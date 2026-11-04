package dev.overhaul.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import java.util.Map;
import static org.lwjgl.vulkan.VK10.*;

/** Shared per-view graph operations for legacy depth roles and owned cascade layers. */
final class ShadowDepthPasses {
    private ShadowDepthPasses(){}
    static void clear(PackFrameGraph graph,GpuImageView view){
        String name=graph.write(view);graph.pass("clear-shadow-"+name,Map.of(name,VulkanRenderGraph.Usage.TRANSFER_WRITE),pass->{
            try(MemoryStack stack=MemoryStack.stackPush()){
                var range=VkImageSubresourceRange.calloc(1,stack).aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT).baseMipLevel(view.baseMip()).levelCount(1).baseArrayLayer(view.baseLayer()).layerCount(1);
                vkCmdClearDepthStencilImage(pass.command(),view.image().handle(),VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,VkClearDepthStencilValue.calloc(stack).depth(1).stencil(0),range);
            }
        });
    }
    static void copy(PackFrameGraph graph,GpuImageView source,GpuImageView target){
        String read=graph.read(source),write=graph.write(target);
        graph.pass("snapshot-shadow-"+write,Map.of(read,VulkanRenderGraph.Usage.TRANSFER_READ,write,VulkanRenderGraph.Usage.TRANSFER_WRITE),pass->{
            try(MemoryStack stack=MemoryStack.stackPush()){
                var region=VkImageCopy.calloc(1,stack);region.srcSubresource().aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT).mipLevel(source.baseMip()).baseArrayLayer(source.baseLayer()).layerCount(1);
                region.dstSubresource().aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT).mipLevel(target.baseMip()).baseArrayLayer(target.baseLayer()).layerCount(1);
                region.extent().set(source.image().width(source.baseMip()),source.image().height(source.baseMip()),1);
                vkCmdCopyImage(pass.command(),source.image().handle(),VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,target.image().handle(),VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,region);
            }
        });
    }
}
