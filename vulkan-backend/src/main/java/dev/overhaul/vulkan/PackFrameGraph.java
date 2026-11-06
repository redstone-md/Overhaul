package dev.overhaul.vulkan;

import dev.overhaul.shader.graph.RenderGraph;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Gives every physical overwrite a logical graph version while preserving one authoritative image owner. */
final class PackFrameGraph {
    /**
     * Graph shapes a runtime has already validated. A frame repeats the images and passes of an earlier one, so its
     * graph is built and checked once; only the pass callbacks are new.
     */
    static final class Shapes {
        private static final int LIMIT=32;
        private final Map<List<Object>,VulkanRenderGraph.Shape> known=new LinkedHashMap<>(16,.75f,true){
            @Override protected boolean removeEldestEntry(Map.Entry<List<Object>,VulkanRenderGraph.Shape> eldest){return size()>LIMIT;}
        };
        void clear(){known.clear();}
    }

    final Map<String,VulkanRenderGraph.Operation> operations=new LinkedHashMap<>();
    private final List<GpuImageView> versions=new ArrayList<>();
    /** The views in version order and each pass's name and uses; equal keys produce equal graphs. */
    private final List<Object> key=new ArrayList<>();
    private final IdentityHashMap<GpuImageView,String> latest=new IdentityHashMap<>();

    String read(GpuImageView image){String name=latest.get(image);return name==null?write(image):name;}
    String write(GpuImageView image){
        String name="image-"+versions.size();versions.add(image);latest.put(image,name);key.add(image);return name;
    }
    void pass(String name,Map<String,VulkanRenderGraph.Usage> uses,Consumer<VulkanRenderGraph.PassContext> record){
        if(operations.containsKey(name))throw new IllegalArgumentException("Duplicate pass: "+name);
        var operation=new VulkanRenderGraph.Operation(uses,record);operations.put(name,operation);key.add(name);key.add(operation.uses());
    }
    VulkanRenderGraph create(VulkanContext context,ImmediateCommands lane){return create(context,lane,null);}
    VulkanRenderGraph create(VulkanContext context,ImmediateCommands lane,Shapes shapes){
        VulkanRenderGraph.Shape known=shapes==null?null:shapes.known.get(key);
        if(known!=null)return new VulkanRenderGraph(context,known,operations,lane);
        Map<String,GpuImageView> imports=new LinkedHashMap<>();
        RenderGraph.Builder graph=new RenderGraph.Builder();
        for(int version=0;version<versions.size();version++){
            GpuImageView image=versions.get(version);String name="image-"+version;imports.put(name,image);
            graph.resource(new RenderGraph.Resource(name,format(image.image().format()),image.image().width(image.baseMip()),image.image().height(image.baseMip()),true));
        }
        String previous=null;
        for(var pass:operations.entrySet()){
            Set<String> reads=new LinkedHashSet<>(),writes=new LinkedHashSet<>();
            for(var entry:pass.getValue().uses().entrySet()){
                switch(entry.getValue()){
                    case COLOR_WRITE,DEPTH_WRITE,TRANSFER_WRITE->writes.add(entry.getKey());
                    default->reads.add(entry.getKey());
                }
            }
            graph.pass(new RenderGraph.Pass(pass.getKey(),reads,writes,previous==null?Set.of():Set.of(previous)));previous=pass.getKey();
        }
        VulkanRenderGraph built=new VulkanRenderGraph(context,graph.build(),operations,imports,lane);
        if(shapes!=null)shapes.known.put(List.copyOf(key),built.shape());
        return built;
    }
    private static RenderGraph.Format format(ImageFormat format){return GraphImageFormats.graph(format);}
}
