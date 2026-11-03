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
    private final List<GpuImage> owned=new ArrayList<>();
    final GpuImageView output,depth;
    PackGraphImages(GpuAllocator allocator,PackRenderTargets targets,int width,int height){
        try{
            for(var target:targets.targets().values())for(int side=0;side<2;side++)colors.put(new PackTargetSwaps.Target(target.index(),side),
                    create(allocator,format(target.format()),target.width(width),target.height(height)));
            output=create(allocator,ImageFormat.RGBA8_UNORM,width,height);
            depth=create(allocator,ImageFormat.R32_SFLOAT,width,height);
        }catch(RuntimeException|Error failure){close();throw failure;}
    }
    private GpuImageView create(GpuAllocator allocator,ImageFormat format,int width,int height){
        GpuImage image=allocator.createImage(format,width,height,1,1,VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT|VK_IMAGE_USAGE_SAMPLED_BIT
                |VK_IMAGE_USAGE_TRANSFER_SRC_BIT|VK_IMAGE_USAGE_TRANSFER_DST_BIT);
        owned.add(image);return image.view(0,1,0,1);
    }
    private static ImageFormat format(PackRenderTargets.Format format){return GraphImageFormats.image(dev.overhaul.shader.graph.RenderGraph.Format.valueOf(format.name()));}
    @Override public void close(){for(int index=owned.size()-1;index>=0;index--)owned.get(index).close();owned.clear();colors.clear();}
}
