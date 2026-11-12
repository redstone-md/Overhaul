package dev.overhaul.minecraft.blaze;

import dev.overhaul.vulkan.GpuAllocator;
import dev.overhaul.vulkan.GpuSampler;
import dev.overhaul.vulkan.SamplerSettings;
import java.util.HashMap;
import java.util.Map;

/**
 * One native sampler per distinct setting. Vanilla sets a texture's filter again for every render state, so a texture
 * that alternates between two modes would otherwise create a sampler for each change, and drivers cap live samplers.
 */
final class BlazeSamplers implements AutoCloseable {
    private final GpuAllocator allocator;
    private final BlazeQueue queue;
    private final Map<SamplerSettings,GpuSampler> samplers=new HashMap<>();

    BlazeSamplers(GpuAllocator allocator,BlazeQueue queue){
        this.allocator=allocator;this.queue=queue;
        var capabilities=allocator.context().capabilities();
        BlazeSamplerState.anisotropyLimit(capabilities.samplerAnisotropy()?capabilities.maxSamplerAnisotropy():1);
    }
    GpuSampler get(SamplerSettings settings){
        GpuSampler sampler=samplers.get(settings);
        if(sampler==null){
            sampler=allocator.createSampler(settings);
            samplers.put(settings,sampler);
            BlazeStats.samplerCreated();
        }
        return sampler;
    }
    @Override public void close(){
        for(GpuSampler sampler:samplers.values()){
            if(queue==null)BlazeQueue.closeResource(sampler);else queue.retire(sampler);
        }
        samplers.clear();
    }
}
