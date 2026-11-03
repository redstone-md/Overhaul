package dev.overhaul.minecraft.backend;

import dev.overhaul.vulkan.GpuImage;
import dev.overhaul.vulkan.GpuImageView;
import dev.overhaul.vulkan.GpuSampler;
import dev.overhaul.vulkan.ImageFormat;
import dev.overhaul.vulkan.ImmediateCommands;
import dev.overhaul.vulkan.SamplerSettings;
import static org.lwjgl.vulkan.VK10.*;

/** An absent lightmap on a genuinely emissive source is the multiplicative identity, not invented scene lighting. */
final class SceneFallbackTexture implements AutoCloseable {
    private GpuImage image;
    private GpuImageView view;
    private GpuSampler sampler;
    private boolean initialized;
    SceneFallbackTexture(OverhaulDeviceBackend owner){
        try{image=owner.allocator().createImage(ImageFormat.RGBA8_UNORM,1,1,1,1,VK_IMAGE_USAGE_SAMPLED_BIT|VK_IMAGE_USAGE_TRANSFER_DST_BIT);
            view=image.view(0,1,0,1);sampler=owner.allocator().createSampler(SamplerSettings.nearest());}
        catch(RuntimeException|Error failure){close();throw failure;}
    }
    void initialize(ImmediateCommands lane){if(!initialized){lane.clearColor(image,0,0,1,1,1,1);lane.transition(image,0,0,VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);initialized=true;}}
    ShaderPackSceneGeneration.Sample sample(){if(!initialized)throw new IllegalStateException("Scene identity texture is not initialized");return new ShaderPackSceneGeneration.Sample(view,sampler.handle());}
    @Override public void close(){if(sampler!=null){sampler.close();sampler=null;}if(view!=null){view.close();view=null;}if(image!=null){image.close();image=null;}}
}
