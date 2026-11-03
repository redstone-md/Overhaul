package dev.overhaul.minecraft.backend;

import org.lwjgl.vulkan.VkCommandBuffer;

/** Query reset and write remain ordered with the draws preceding this API call. */
final class TimestampOperation implements OverhaulGpuOperation {
    private final OverhaulQueryPool pool;
    private final int index;
    private final long generation;
    private final BackendPins pins;
    TimestampOperation(OverhaulDeviceBackend owner,OverhaulQueryPool pool,int index) {
        this.pool=pool; this.index=index;
        pool.prepare(index); generation=pool.generation(index); pins=new BackendPins(owner,pool);
    }
    @Override public void record(VkCommandBuffer command) { pool.reset(command,index); pool.write(command,index); }
    @Override public void submitted(dev.overhaul.vulkan.FrameRing.Submission submission) { pool.markSubmitted(index,generation); }
    @Override public boolean usesResource(BackendResource resource) { return resource==pool; }
    @Override public void close() { pins.close(); }
}
