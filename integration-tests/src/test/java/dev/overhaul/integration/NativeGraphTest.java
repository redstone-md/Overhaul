package dev.overhaul.integration;

import dev.overhaul.shader.graph.RenderGraph;
import dev.overhaul.vulkan.VulkanContext;
import dev.overhaul.vulkan.VulkanRenderGraph;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static dev.overhaul.vulkan.VulkanRenderGraph.Usage.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.vulkan.VK10.*;

@Tag("native")
class NativeGraphTest {
    @Test void orderedPassesTransitionRealImagesAndRecoverAfterRecordingFailure() {
        RenderGraph graph=new RenderGraph.Builder()
                .resource(new RenderGraph.Resource("source",RenderGraph.Format.RGBA8,8,8,false))
                .resource(new RenderGraph.Resource("result",RenderGraph.Format.RGBA8,8,8,false))
                // Deliberately register consumer first; dependency order drives execution.
                .pass(new RenderGraph.Pass("copy",Set.of("source"),Set.of("result"),Set.of()))
                .pass(new RenderGraph.Pass("clear",Set.of(),Set.of("source"),Set.of())).build();
        AtomicBoolean fail=new AtomicBoolean(true);
        Map<String,VulkanRenderGraph.Operation> operations=Map.of(
                "clear",new VulkanRenderGraph.Operation(Map.of("source",TRANSFER_WRITE),pass->{
                    if (fail.getAndSet(false)) throw new IllegalStateException("recording interrupted");
                    try (MemoryStack stack=MemoryStack.stackPush()) {
                        VkClearColorValue color=VkClearColorValue.calloc(stack)
                                .float32(0,1).float32(1,0).float32(2,0).float32(3,1);
                        VkImageSubresourceRange.Buffer range=VkImageSubresourceRange.calloc(1,stack)
                                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
                        vkCmdClearColorImage(pass.command(),pass.image("source").handle(),TRANSFER_WRITE.layout(),color,range);
                    }
                }),
                "copy",new VulkanRenderGraph.Operation(Map.of("source",TRANSFER_READ,"result",TRANSFER_WRITE),pass->{
                    try (MemoryStack stack=MemoryStack.stackPush()) {
                        VkImageCopy.Buffer copy=VkImageCopy.calloc(1,stack);
                        copy.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                        copy.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                        copy.extent().set(8,8,1);
                        vkCmdCopyImage(pass.command(),pass.image("source").handle(),TRANSFER_READ.layout(),
                                pass.image("result").handle(),TRANSFER_WRITE.layout(),copy);
                    }
                }));
        try (VulkanContext context=VulkanContext.openHeadless();
             VulkanRenderGraph execution=new VulkanRenderGraph(context,graph,operations)) {
            assertThrows(IllegalStateException.class,execution::execute);
            assertThrows(IllegalArgumentException.class,()->execution.readRgba8("result"));
            execution.execute();
            byte[] image=execution.readRgba8("result");
            assertEquals(8*8*4,image.length);
            for (int i=0;i<image.length;i+=4) {
                assertEquals(255,image[i]&255); assertEquals(0,image[i+1]&255);
                assertEquals(0,image[i+2]&255); assertEquals(255,image[i+3]&255);
            }
            execution.execute();
            assertArrayEquals(image,execution.readRgba8("result"));
        }
    }
}
