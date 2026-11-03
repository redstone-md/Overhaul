package dev.overhaul.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import java.nio.LongBuffer;
import static org.lwjgl.vulkan.VK10.*;
import static dev.overhaul.vulkan.VulkanException.check;

/** Allocator-owned native sampler, independent of image/view lifetimes. */
public final class GpuSampler implements AutoCloseable {
    private final GpuAllocator allocator;private final SamplerSettings settings;private long handle;
    GpuSampler(GpuAllocator allocator,SamplerSettings settings){
        this.allocator=allocator;this.settings=java.util.Objects.requireNonNull(settings);
        DeviceCapabilities capabilities=allocator.context().capabilities();
        if(settings.anisotropy()>capabilities.maxSamplerAnisotropy()||settings.anisotropy()>1&&!capabilities.samplerAnisotropy())
            throw new IllegalArgumentException("requested anisotropy is not enabled/supported");
        try(MemoryStack stack=MemoryStack.stackPush()){
            VkSamplerCreateInfo info=VkSamplerCreateInfo.calloc(stack).sType$Default().minFilter(settings.minFilter().vk).magFilter(settings.magFilter().vk)
                .mipmapMode(settings.mipmap().vk).addressModeU(settings.u().vk).addressModeV(settings.v().vk).addressModeW(settings.w().vk)
                .minLod(settings.minLod()).maxLod(settings.maxLod()).anisotropyEnable(settings.anisotropy()>1).maxAnisotropy(settings.anisotropy())
                .compareEnable(settings.comparison()!=SamplerSettings.Compare.NONE).compareOp(settings.comparison().vk).borderColor(VK_BORDER_COLOR_FLOAT_TRANSPARENT_BLACK);
            LongBuffer pointer=stack.mallocLong(1);check(vkCreateSampler(allocator.context().device(),info,null,pointer),"vkCreateSampler");handle=pointer.get(0);
        }
    }
    public VulkanContext context(){requireOpen();return allocator.context();}public SamplerSettings settings(){return settings;}
    public long handle(){requireOpen();return handle;}
    private void requireOpen(){allocator.requireOpen();if(handle==0)throw new IllegalStateException("GPU sampler closed");}
    @Override public void close(){if(handle==0)return;requireOpen();vkDestroySampler(allocator.context().device(),handle,null);handle=0;allocator.removeSampler(this);}
}
