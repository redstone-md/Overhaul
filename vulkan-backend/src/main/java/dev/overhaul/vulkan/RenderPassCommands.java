package dev.overhaul.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import static org.lwjgl.vulkan.VK10.*;

/** Identical attachment clear and binding semantics for every single-target draw lane. */
final class RenderPassCommands {
    private RenderPassCommands() {}
    static void begin(VkCommandBuffer command,ColorTarget target,long pipeline,long layout,long descriptors) {
        try (MemoryStack stack=MemoryStack.stackPush()) {
            VkClearValue.Buffer clear=VkClearValue.calloc(2,stack);
            clear.get(0).color().float32(0,.02f).float32(1,.03f).float32(2,.04f).float32(3,1);
            clear.get(1).depthStencil().depth(1).stencil(0);
            VkRenderPassBeginInfo begin=VkRenderPassBeginInfo.calloc(stack).sType$Default().renderPass(target.renderPass)
                    .framebuffer(target.framebuffer).pClearValues(clear);
            begin.renderArea().extent().set(target.width,target.height);
            vkCmdBeginRenderPass(command,begin,VK_SUBPASS_CONTENTS_INLINE);
            vkCmdBindPipeline(command,VK_PIPELINE_BIND_POINT_GRAPHICS,pipeline);
            vkCmdBindDescriptorSets(command,VK_PIPELINE_BIND_POINT_GRAPHICS,layout,0,stack.longs(descriptors),null);
        }
    }
}
