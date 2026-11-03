package dev.overhaul.vulkan;

import dev.overhaul.shader.compat.UnsupportedShaderException;
import dev.overhaul.shader.pack.CompiledShaderPack;
import dev.overhaul.shader.pack.PackRenderTargets;
import dev.overhaul.shader.pack.PackShadowSettings;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.lwjgl.vulkan.VK10.*;

/** One light-camera generation with full/opaque depth and independent shadowcolor ping-pong storage. */
final class PackShadowImages implements AutoCloseable {
    private final PackShadowSettings settings;
    private final List<GpuImage> images=new ArrayList<>();
    private final List<GpuSampler> samplers=new ArrayList<>();
    private final GpuImageView[] depth=new GpuImageView[2];
    private final GpuImageView[][] colors=new GpuImageView[2][2];
    private final GpuSampler[][] depthSamplers=new GpuSampler[2][2];
    private final GpuSampler[] colorSamplers=new GpuSampler[2];
    private final int[] current=new int[2];
    private boolean initialized;
    PackShadowImages(VulkanContext context,GpuAllocator allocator,PackShadowSettings settings){
        this.settings=settings;
        try{
            ImageFormat depthFormat=depthFormat(context,!settings.nearestDepth(0)||!settings.nearestDepth(1));
            for(int index=0;index<2;index++){
                depth[index]=image(allocator,depthFormat,VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT);
                for(int compare=0;compare<2;compare++)depthSamplers[index][compare]=sampler(allocator,settings.nearestDepth(index),compare!=0);
                colorSamplers[index]=sampler(allocator,settings.nearestColor(index),false);
                var target=settings.colors().targets().get(index);if(target==null)continue;
                var format=GraphImageFormats.image(dev.overhaul.shader.graph.RenderGraph.Format.valueOf(target.format().name()));
                for(int side=0;side<2;side++)colors[index][side]=image(allocator,format,VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT);
            }
        }catch(RuntimeException|Error failure){close();throw failure;}
    }
    private GpuImageView image(GpuAllocator allocator,ImageFormat format,int attachmentUsage){
        var image=allocator.createImage(format,settings.resolution(),settings.resolution(),1,1,attachmentUsage|VK_IMAGE_USAGE_SAMPLED_BIT|VK_IMAGE_USAGE_TRANSFER_SRC_BIT|VK_IMAGE_USAGE_TRANSFER_DST_BIT);
        images.add(image);return image.view(0,1,0,1);
    }
    private GpuSampler sampler(GpuAllocator allocator,boolean nearest,boolean comparison){
        var filter=nearest?SamplerSettings.Filter.NEAREST:SamplerSettings.Filter.LINEAR;
        var sampler=allocator.createSampler(new SamplerSettings(filter,filter,SamplerSettings.Mipmap.NEAREST,
                SamplerSettings.Address.CLAMP,SamplerSettings.Address.CLAMP,SamplerSettings.Address.CLAMP,0,0,1,
                comparison?SamplerSettings.Compare.LESS_OR_EQUAL:SamplerSettings.Compare.NONE));samplers.add(sampler);return sampler;
    }
    private static ImageFormat depthFormat(VulkanContext context,boolean linear){
        try(MemoryStack stack=MemoryStack.stackPush()){
            int required=VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT|VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT|(linear?VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT:0);
            var properties=VkFormatProperties.malloc(stack);
            for(var format:List.of(ImageFormat.D32_SFLOAT,ImageFormat.D16_UNORM)){
                vkGetPhysicalDeviceFormatProperties(context.physicalDevice(),format.vkFormat(),properties);
                if((properties.optimalTilingFeatures()&required)==required)return format;
            }
        }
        throw new UnsupportedShaderException("Device has no sampled shadow depth format for the requested filtering");
    }
    GpuImageView depth(int index){if(index<0||index>1)throw new IllegalArgumentException("Invalid shadow depth index");return depth[index];}
    GpuImageView color(int index){if(index<0||index>1||colors[index][current[index]]==null)throw new IllegalArgumentException("Absent shadow color target: "+index);return colors[index][current[index]];}
    long depthSampler(int index,boolean comparison){depth(index);return depthSamplers[index][comparison?1:0].handle();}
    long colorSampler(int index){color(index);return colorSamplers[index].handle();}
    List<GpuImageView> targets(CompiledShaderPack.Program program){
        List<GpuImageView> result=new ArrayList<>();boolean scene=program.stage()==CompiledShaderPack.Stage.SHADOW;
        for(int index:program.code().framebuffer().colorTargets()){
            if(index<0){result.add(null);continue;}color(index);result.add(colors[index][current[index]^(scene?0:1)]);
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
            GpuImageView target=depth[index];String name=plan.write(target);
            plan.pass("clear-shadow-depth-"+index,Map.of(name,VulkanRenderGraph.Usage.TRANSFER_WRITE),pass->{
                try(MemoryStack stack=MemoryStack.stackPush()){
                    var range=VkImageSubresourceRange.calloc(1,stack).aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT).levelCount(1).layerCount(1);
                    vkCmdClearDepthStencilImage(pass.command(),target.image().handle(),VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,VkClearDepthStencilValue.calloc(stack).depth(1).stencil(0),range);
                }
            });
            var color=settings.colors().targets().get(index);
            if(color!=null&&(!initialized||color.clear()))for(var image:colors[index])PackGraphRuntime.clear(plan,image,color.color());
        }
    }
    void initialized(){initialized=true;}
    void snapshotOpaque(PackFrameGraph plan){
        String read=plan.read(depth[0]),write=plan.write(depth[1]);
        plan.pass("retain-opaque-shadow-depth",Map.of(read,VulkanRenderGraph.Usage.TRANSFER_READ,write,VulkanRenderGraph.Usage.TRANSFER_WRITE),pass->{
            try(MemoryStack stack=MemoryStack.stackPush()){
                var region=VkImageCopy.calloc(1,stack);region.srcSubresource().aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT).layerCount(1);
                region.dstSubresource().aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT).layerCount(1);region.extent().set(settings.resolution(),settings.resolution(),1);
                vkCmdCopyImage(pass.command(),depth[0].image().handle(),VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,depth[1].image().handle(),VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,region);
            }
        });
    }
    @Override public void close(){for(int index=samplers.size()-1;index>=0;index--)samplers.get(index).close();samplers.clear();for(int index=images.size()-1;index>=0;index--)images.get(index).close();images.clear();}
}
