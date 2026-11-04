package dev.overhaul.minecraft.backend;

import dev.overhaul.vulkan.GpuImage;
import dev.overhaul.vulkan.GpuImageView;
import dev.overhaul.vulkan.GpuSampler;
import dev.overhaul.vulkan.ImageFormat;
import dev.overhaul.vulkan.ImmediateCommands;
import dev.overhaul.vulkan.SamplerSettings;
import static org.lwjgl.vulkan.VK10.*;

/** A one-texel stand-in for an absent input: the emissive lightmap identity, and the neutral PBR maps Iris binds for a resource pack without them. */
final class SceneFallbackTexture implements AutoCloseable {
    private final float red,green,blue,alpha;
    private GpuImage image;
    private GpuImageView view;
    private GpuSampler sampler;
    private boolean initialized;
    static SceneFallbackTexture identity(OverhaulDeviceBackend owner){return new SceneFallbackTexture(owner,1,1,1,1);}
    static SceneFallbackTexture flatNormal(OverhaulDeviceBackend owner){return new SceneFallbackTexture(owner,127f/255,127f/255,1,1);}
    static SceneFallbackTexture noSpecular(OverhaulDeviceBackend owner){return new SceneFallbackTexture(owner,0,0,0,0);}
    private SceneFallbackTexture(OverhaulDeviceBackend owner,float red,float green,float blue,float alpha){
        this.red=red;this.green=green;this.blue=blue;this.alpha=alpha;
        try{image=owner.allocator().createImage(ImageFormat.RGBA8_UNORM,1,1,1,1,VK_IMAGE_USAGE_SAMPLED_BIT|VK_IMAGE_USAGE_TRANSFER_DST_BIT);
            view=image.view(0,1,0,1);sampler=owner.allocator().createSampler(SamplerSettings.nearest());}
        catch(RuntimeException|Error failure){close();throw failure;}
    }
    void initialize(ImmediateCommands lane){if(!initialized){lane.clearColor(image,0,0,red,green,blue,alpha);lane.transition(image,0,0,VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);initialized=true;}}
    ShaderPackSceneGeneration.Sample sample(){if(!initialized)throw new IllegalStateException("Scene fallback texture is not initialized");return new ShaderPackSceneGeneration.Sample(view,sampler.handle());}
    @Override public void close(){if(sampler!=null){sampler.close();sampler=null;}if(view!=null){view.close();view=null;}if(image!=null){image.close();image=null;}}
}
