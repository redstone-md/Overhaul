package dev.overhaul.minecraft.backend;

import dev.overhaul.vulkan.FrameRing;
import org.lwjgl.vulkan.VkCommandBuffer;

/** Transfers a sealed pass and its pins into the device's ordered queue. */
final class PassOperation implements OverhaulGpuOperation {
    private final OverhaulRenderPass pass;
    PassOperation(OverhaulRenderPass pass) {
        this.pass=pass;
        try { pass.seal(); }
        catch (RuntimeException|Error failure) {
            try { pass.close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
    @Override public void record(VkCommandBuffer command) { pass.record(command); }
    @Override public void submitted(FrameRing.Submission submission) { pass.complete(); }
    @Override public boolean usesBuffer(OverhaulBuffer buffer,long offset,long length) { return pass.usesBuffer(buffer,offset,length); }
    @Override public boolean usesResource(BackendResource resource) { return pass.usesResource(resource); }
    @Override public void close() { pass.close(); }
}
