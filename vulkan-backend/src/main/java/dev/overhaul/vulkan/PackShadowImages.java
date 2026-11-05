package dev.overhaul.vulkan;

import dev.overhaul.shader.compat.UnsupportedShaderException;
import dev.overhaul.shader.pack.CompiledShaderPack;
import dev.overhaul.shader.pack.PackRenderTargets;
import dev.overhaul.shader.pack.PackShadowSettings;
import dev.overhaul.shader.compile.ShadercCompiler;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.lwjgl.vulkan.VK10.*;

/** One light-camera generation with full/opaque depth and independent shadowcolor ping-pong storage. */
final class PackShadowImages implements AutoCloseable {
    private record Chain(GpuImageView base,GpuImageView sample,List<GpuImageView> levels){}
    private final PackShadowSettings settings;
    private final List<GpuImage> images=new ArrayList<>();
    private final List<GpuSampler> samplers=new ArrayList<>();
    private final Chain[] depth=new Chain[2];
    private final GpuImageView[] rawDepth=new GpuImageView[2];
    private final Chain[][] colors=new Chain[2][2];
    private final GpuSampler[][] depthSamplers=new GpuSampler[2][2];
    private final GpuSampler[] colorSamplers=new GpuSampler[2];
    private final int[] current=new int[2];
    private PackDepthMipmaps depthMipmaps;
    private boolean initialized;
    PackShadowImages(VulkanContext context,GpuAllocator allocator,PackShadowSettings settings,ShadercCompiler compiler){
        this.settings=settings;
        try{
            ImageFormat depthFormat=depthFormat(context,!settings.nearestDepth(0)||!settings.nearestDepth(1));
            for(int index=0;index<2;index++){
                depth[index]=image(allocator,depthFormat,VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT,settings.mipmappedDepth(index));
                for(int compare=0;compare<2;compare++)depthSamplers[index][compare]=sampler(allocator,settings.nearestDepth(index),compare!=0,depth[index].levels().size());
                colorSamplers[index]=sampler(allocator,settings.nearestColor(index),false,settings.mipmappedColor(index)?levelCount():1);
                var target=settings.colors().targets().get(index);if(target==null)continue;
                var format=GraphImageFormats.image(dev.overhaul.shader.graph.RenderGraph.Format.valueOf(target.format().name()));
                for(int side=0;side<2;side++)colors[index][side]=image(allocator,format,VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT,settings.mipmappedColor(index));
            }
            if(settings.mipmappedDepth(0)||settings.mipmappedDepth(1))depthMipmaps=new PackDepthMipmaps(context,allocator,compiler,depthFormat.vkFormat());
        }catch(RuntimeException|Error failure){close();throw failure;}
    }
    private int levelCount(){return 32-Integer.numberOfLeadingZeros(settings.resolution());}
    private Chain image(GpuAllocator allocator,ImageFormat format,int attachmentUsage,boolean mipmapped){
        int count=mipmapped?levelCount():1;
        var image=allocator.createImage(format,settings.resolution(),settings.resolution(),count,1,attachmentUsage|VK_IMAGE_USAGE_SAMPLED_BIT|VK_IMAGE_USAGE_TRANSFER_SRC_BIT|VK_IMAGE_USAGE_TRANSFER_DST_BIT);
        images.add(image);List<GpuImageView> levels=new ArrayList<>();for(int mip=0;mip<count;mip++)levels.add(image.view(mip,1,0,1));
        return new Chain(levels.get(0),count==1?levels.get(0):image.view(0,count,0,1),List.copyOf(levels));
    }
    private GpuSampler sampler(GpuAllocator allocator,boolean nearest,boolean comparison,int levels){
        var filter=nearest?SamplerSettings.Filter.NEAREST:SamplerSettings.Filter.LINEAR;
        var sampler=allocator.createSampler(new SamplerSettings(filter,filter,nearest?SamplerSettings.Mipmap.NEAREST:SamplerSettings.Mipmap.LINEAR,
                SamplerSettings.Address.CLAMP,SamplerSettings.Address.CLAMP,SamplerSettings.Address.CLAMP,0,levels-1,1,
                comparison?SamplerSettings.Compare.LESS_OR_EQUAL:SamplerSettings.Compare.NONE));samplers.add(sampler);return sampler;
    }
    static ImageFormat depthFormat(VulkanContext context,boolean linear){
        try(MemoryStack stack=VulkanStack.push()){
            int required=VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT|VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT|(linear?VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT:0);
            var properties=VkFormatProperties.malloc(stack);
            for(var format:List.of(ImageFormat.D32_SFLOAT,ImageFormat.D16_UNORM)){
                vkGetPhysicalDeviceFormatProperties(context.physicalDevice(),format.vkFormat(),properties);
                if((properties.optimalTilingFeatures()&required)==required)return format;
            }
        }
        throw new UnsupportedShaderException("Device has no sampled shadow depth format for the requested filtering");
    }
    GpuImageView depth(int index){if(index<0||index>1)throw new IllegalArgumentException("Invalid shadow depth index");return depth[index].base();}
    GpuImageView depthSample(int index,boolean comparison){
        depth(index);if(comparison)return depth[index].sample();
        // Comparison-only packs need no component swizzle. Missing alpha already reads as one.
        if(rawDepth[index]==null)rawDepth[index]=depth[index].base().image().view(0,depth[index].levels().size(),0,1,VK_IMAGE_VIEW_TYPE_2D,VK_IMAGE_ASPECT_DEPTH_BIT,
                new GpuImageView.Components(VK_COMPONENT_SWIZZLE_R,VK_COMPONENT_SWIZZLE_R,VK_COMPONENT_SWIZZLE_R,VK_COMPONENT_SWIZZLE_A));
        return rawDepth[index];
    }
    GpuImageView color(int index){if(index<0||index>1||colors[index][current[index]]==null)throw new IllegalArgumentException("Absent shadow color target: "+index);return colors[index][current[index]].sample();}
    long depthSampler(int index,boolean comparison){depth(index);return depthSamplers[index][comparison?1:0].handle();}
    long colorSampler(int index){color(index);return colorSamplers[index].handle();}
    List<GpuImageView> targets(CompiledShaderPack.Program program){
        List<GpuImageView> result=new ArrayList<>();boolean scene=program.stage()==CompiledShaderPack.Stage.SHADOW;
        for(int index:program.code().framebuffer().colorTargets()){
            if(index<0){result.add(null);continue;}color(index);result.add(colors[index][current[index]^(scene?0:1)].base());
        }
        return result;
    }
    void complete(CompiledShaderPack.Program program){
        if(program.stage()==CompiledShaderPack.Stage.SHADOW)return;
        boolean[] flip=new boolean[2];
        for(int index:program.code().framebuffer().colorTargets())if(index>=0)flip[index]=program.state().flips().getOrDefault("shadowcolor"+index,true);
        for(var entry:program.state().flips().entrySet())if(entry.getValue()){
            int index=PackShadowSettings.colorIndex(entry.getKey());if(index<0)throw new UnsupportedShaderException("Shadow composite flip targets the main color namespace");flip[index]=true;
        }
        for(int index=0;index<2;index++)if(flip[index])current[index]^=1;
    }
    int[] snapshot(){return current.clone();}
    void restore(int[] state){System.arraycopy(state,0,current,0,2);}
    void clear(PackFrameGraph plan){
        for(int index=0;index<2;index++){
            for(GpuImageView target:depth[index].levels()){
                ShadowDepthPasses.clear(plan,target);
            }
            var color=settings.colors().targets().get(index);
            if(color!=null&&(!initialized||color.clear()))for(var image:colors[index])for(var level:image.levels())PackGraphRuntime.clear(plan,level,color.color());
        }
    }
    void initialized(){initialized=true;}
    void snapshotOpaque(PackFrameGraph plan){
        ShadowDepthPasses.copy(plan,depth(0),depth(1));
    }
    void generateDepth(PackFrameGraph plan,int index){if(depth[index].levels().size()>1)depthMipmaps.generate(plan,depth[index].levels());}
    void generateColor(PackFrameGraph plan,int index,PackMipmaps mipmaps){if(colors[index][current[index]]!=null)mipmaps.generate(plan,colors[index][current[index]].levels());}
    void generateColors(PackFrameGraph plan,PackMipmaps mipmaps){for(int index=0;index<2;index++)generateColor(plan,index,mipmaps);}
    @Override public void close(){if(depthMipmaps!=null){depthMipmaps.close();depthMipmaps=null;}for(int index=samplers.size()-1;index>=0;index--)samplers.get(index).close();samplers.clear();for(int index=images.size()-1;index>=0;index--)images.get(index).close();images.clear();}
}
