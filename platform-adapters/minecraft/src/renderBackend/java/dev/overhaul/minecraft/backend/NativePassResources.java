package dev.overhaul.minecraft.backend;

import dev.overhaul.vulkan.GpuBuffer;
import dev.overhaul.vulkan.GpuImageView;
import dev.overhaul.vulkan.GpuSampler;
import dev.overhaul.vulkan.VulkanContext;
import org.lwjgl.vulkan.VkCommandBuffer;

/** Device-owned conversion and lifetime boundary; foreign backend resources must fail. */
public interface NativePassResources {
    VulkanContext context();
    GpuBuffer buffer(com.mojang.renderpearl.api.buffers.GpuBuffer buffer);
    GpuImageView texture(com.mojang.renderpearl.api.textures.GpuTextureView view);
    GpuSampler sampler(com.mojang.renderpearl.api.textures.GpuSampler sampler);
    AutoCloseable retain(Object resource);
    void prepareTimestamp(com.mojang.renderpearl.api.commands.GpuQueryPool pool,int index);
    void timestamp(VkCommandBuffer command,com.mojang.renderpearl.api.commands.GpuQueryPool pool,int index);
}
