package dev.overhaul.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import static org.lwjgl.vulkan.VK10.*;

/** Shared render-pass setup for synchronous diagnostics and asynchronous frame submission. */
final class IndexedPass {
    private IndexedPass() { }

    static void begin(VkCommandBuffer command, ColorTarget target, CameraUniform camera, IndexedPipeline pipeline) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkClearValue.Buffer clear = VkClearValue.calloc(2, stack);
            clear.get(0).color().float32(0, 0.02f).float32(1, 0.03f).float32(2, 0.04f).float32(3, 1f);
            clear.get(1).depthStencil().depth(1f).stencil(0);
            VkRenderPassBeginInfo begin = VkRenderPassBeginInfo.calloc(stack).sType$Default()
                .renderPass(target.renderPass).framebuffer(target.framebuffer).pClearValues(clear);
            begin.renderArea().extent().set(target.width, target.height);
            vkCmdBeginRenderPass(command, begin, VK_SUBPASS_CONTENTS_INLINE);
            vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.pipeline);
            vkCmdBindDescriptorSets(command, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.layout,
                0, stack.longs(camera.set), null);
        }
    }
}
