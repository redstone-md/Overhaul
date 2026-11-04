package dev.overhaul.vulkan;

import dev.overhaul.shader.pack.PackTextures;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkFormatProperties;
import java.nio.ByteBuffer;
import static org.lwjgl.vulkan.VK10.*;

/** Authoritative image upload/layout tracking shared by pack assets and material textures. */
final class SampledTexture implements AutoCloseable {
    private final VulkanContext context;
    private GpuImage image;
    private GpuImageView view;
    private GpuSampler sampling;
    long sampler;

    SampledTexture(VulkanContext context,GpuAllocator allocator,ImmediateCommands commands,PackTextureData data){
        this(context,allocator,commands,data.width(),data.height(),ByteBuffer.wrap(data.rgba()),
                data.filter()==PackTextureData.Filter.LINEAR,data.wrap()==PackTextureData.Wrap.CLAMP);
    }
    SampledTexture(VulkanContext context,GpuAllocator allocator,ImmediateCommands commands,PackTextures.Image data){
        this(context,allocator,commands,data.width(),data.height(),data.pixels(),data.blur(),data.clamp());
    }
    private SampledTexture(VulkanContext context,GpuAllocator allocator,ImmediateCommands commands,int width,int height,ByteBuffer pixels,boolean linear,boolean clamp){
        this.context=context;
        try(MemoryStack stack=MemoryStack.stackPush()){
            VkFormatProperties properties=VkFormatProperties.malloc(stack);
            vkGetPhysicalDeviceFormatProperties(context.physicalDevice(),VK_FORMAT_R8G8B8A8_UNORM,properties);
            int required=VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT|(linear?VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT:0);
            if((properties.optimalTilingFeatures()&required)!=required)throw new IllegalStateException("RGBA8 texture filtering unsupported");
            image=allocator.createImage(ImageFormat.RGBA8_UNORM,width,height,1,1,VK_IMAGE_USAGE_SAMPLED_BIT|VK_IMAGE_USAGE_TRANSFER_DST_BIT);
            view=image.view(0,1,0,1);commands.upload(image,ImageRegion.whole(image,0,0),pixels,0);
            var filter=linear?SamplerSettings.Filter.LINEAR:SamplerSettings.Filter.NEAREST;
            var address=clamp?SamplerSettings.Address.CLAMP:SamplerSettings.Address.REPEAT;
            sampling=allocator.createSampler(new SamplerSettings(filter,filter,SamplerSettings.Mipmap.NEAREST,address,address,address,0,0,1,SamplerSettings.Compare.NONE));
            sampler=sampling.handle();
        }catch(RuntimeException|Error failure){close();throw failure;}
    }
    long view(){return nativeView().handle();}
    GpuImageView nativeView(){if(view==null)throw new IllegalStateException("Sampled texture closed");return view;}
    @Override public void close(){
        context.requireOwner();if(sampling!=null){sampling.close();sampling=null;}sampler=0;
        if(image!=null){image.close();image=null;}view=null;
    }
}
