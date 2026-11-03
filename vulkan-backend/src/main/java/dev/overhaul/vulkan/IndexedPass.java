package dev.overhaul.vulkan;

import org.lwjgl.vulkan.VkCommandBuffer;

/** Shared render-pass setup for synchronous diagnostics and asynchronous frame submission. */
final class IndexedPass {
    private IndexedPass() { }

    static void begin(VkCommandBuffer command, ColorTarget target, CameraUniform camera, IndexedPipeline pipeline) {
        RenderPassCommands.begin(command,target,pipeline.pipeline,pipeline.layout,camera.set);
    }
}
