package dev.overhaul.minecraft.backend;

import dev.overhaul.vulkan.FrameRing;
import org.lwjgl.vulkan.VkCommandBuffer;

/** One owned command transaction, retired after its accepted submission's fence. */
interface OverhaulGpuOperation extends AutoCloseable {
    void record(VkCommandBuffer command);
    default void submitted(FrameRing.Submission submission) {}
    default boolean usesBuffer(OverhaulBuffer buffer,long offset,long length) { return false; }
    default boolean usesResource(BackendResource resource) { return false; }
    default long stagingBytes() { return 0; }
    @Override void close();
}
