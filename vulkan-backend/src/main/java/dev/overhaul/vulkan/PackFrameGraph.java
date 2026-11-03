package dev.overhaul.vulkan;

import dev.overhaul.shader.graph.RenderGraph;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Gives every physical overwrite a logical graph version while preserving one authoritative image owner. */
final class PackFrameGraph {
    final RenderGraph.Builder graph=new RenderGraph.Builder();
    final Map<String,GpuImageView> imports=new LinkedHashMap<>();
    final Map<String,VulkanRenderGraph.Operation> operations=new LinkedHashMap<>();
    private final IdentityHashMap<GpuImageView,String> latest=new IdentityHashMap<>();
    private int version;
    private String previous;
    String read(GpuImageView image){String name=latest.get(image);return name==null?write(image):name;}
    String write(GpuImageView image){
        String name="image-"+version++;latest.put(image,name);imports.put(name,image);
        graph.resource(new RenderGraph.Resource(name,format(image.image().format()),image.image().width(image.baseMip()),image.image().height(image.baseMip()),true));return name;
    }
    void pass(String name,Map<String,VulkanRenderGraph.Usage> uses,Consumer<VulkanRenderGraph.PassContext> record){
        Set<String> reads=new LinkedHashSet<>(),writes=new LinkedHashSet<>();
        for(var entry:uses.entrySet()){
            switch(entry.getValue()){
                case COLOR_WRITE,DEPTH_WRITE,TRANSFER_WRITE->writes.add(entry.getKey());
                default->reads.add(entry.getKey());
            }
        }
        graph.pass(new RenderGraph.Pass(name,reads,writes,previous==null?Set.of():Set.of(previous)));
        operations.put(name,new VulkanRenderGraph.Operation(uses,record));previous=name;
    }
    VulkanRenderGraph create(VulkanContext context,ImmediateCommands lane){return new VulkanRenderGraph(context,graph.build(),operations,imports,lane);}
    private static RenderGraph.Format format(ImageFormat format){return switch(format){
        case RGBA8_UNORM->RenderGraph.Format.RGBA8;case RGBA8_SRGB->RenderGraph.Format.RGBA8_SRGB;
        case BGRA8_UNORM->RenderGraph.Format.BGRA8;case BGRA8_SRGB->RenderGraph.Format.BGRA8_SRGB;
        case RGBA16_SFLOAT->RenderGraph.Format.RGBA16F;case RGBA32_SFLOAT->RenderGraph.Format.RGBA32F;
        case R32_SFLOAT->RenderGraph.Format.R32F;case D16_UNORM->RenderGraph.Format.DEPTH16;
        case D24_UNORM_S8_UINT->RenderGraph.Format.DEPTH24_STENCIL8;case D32_SFLOAT->RenderGraph.Format.DEPTH32F;
        case D32_SFLOAT_S8_UINT->RenderGraph.Format.DEPTH32F_STENCIL8;
        default->throw new IllegalArgumentException("Unsupported imported pack frame format: "+format);
    };}
}
