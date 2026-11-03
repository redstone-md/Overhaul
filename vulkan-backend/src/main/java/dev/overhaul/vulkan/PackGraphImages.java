package dev.overhaul.vulkan;

import dev.overhaul.shader.graph.PackTargetSwaps;
import dev.overhaul.shader.pack.PackRenderTargets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.lwjgl.vulkan.VK10.*;

/** Owns bounded color pairs; native frame imports remain borrowed by the graph. */
final class PackGraphImages implements AutoCloseable {
    final Map<PackTargetSwaps.Target,GpuImageView> colors=new LinkedHashMap<>();
    final Map<PackTargetSwaps.Target,List<GpuImageView>> levels=new LinkedHashMap<>();
    final Map<PackTargetSwaps.Target,GpuImageView> sampling=new LinkedHashMap<>();
    private final List<GpuImage> owned=new ArrayList<>();
    final GpuImageView output,depth;
    PackGraphImages(GpuAllocator allocator,PackRenderTargets targets,int width,int height){
        try{
            for(var target:targets.targets().values())for(int side=0;side<2;side++){
                int w=target.width(width),h=target.height(height),count=target.mipmapped()?32-Integer.numberOfLeadingZeros(Math.max(w,h)):1;
                var key=new PackTargetSwaps.Target(target.index(),side);GpuImageView base=create(allocator,format(target.format()),w,h,count);
                colors.put(key,base);var chain=new ArrayList<GpuImageView>();chain.add(base);
                for(int mip=1;mip<count;mip++)chain.add(base.image().view(mip,1,0,1));levels.put(key,List.copyOf(chain));
                sampling.put(key,count==1?base:base.image().view(0,count,0,1));
            }
            output=create(allocator,ImageFormat.RGBA8_UNORM,width,height);
            depth=create(allocator,ImageFormat.R32_SFLOAT,width,height);
        }catch(RuntimeException|Error failure){close();throw failure;}
    }
    private GpuImageView create(GpuAllocator allocator,ImageFormat format,int width,int height){
        return create(allocator,format,width,height,1);
    }
    private GpuImageView create(GpuAllocator allocator,ImageFormat format,int width,int height,int mips){
        GpuImage image=allocator.createImage(format,width,height,mips,1,VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT|VK_IMAGE_USAGE_SAMPLED_BIT
                |VK_IMAGE_USAGE_TRANSFER_SRC_BIT|VK_IMAGE_USAGE_TRANSFER_DST_BIT);
        owned.add(image);return image.view(0,1,0,1);
    }
    private static ImageFormat format(PackRenderTargets.Format format){return GraphImageFormats.image(dev.overhaul.shader.graph.RenderGraph.Format.valueOf(format.name()));}
    @Override public void close(){for(int index=owned.size()-1;index>=0;index--)owned.get(index).close();owned.clear();colors.clear();levels.clear();sampling.clear();}
}
