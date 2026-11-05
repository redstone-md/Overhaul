package dev.overhaul.vulkan;

import dev.overhaul.core.model.ModelMesh;
import dev.overhaul.core.model.RenderLayer;
import dev.overhaul.shader.compat.UniformBlock;
import org.lwjgl.system.MemoryStack;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import static org.lwjgl.vulkan.VK10.*;

/** Baked-model asset path with atlas/lightmap, cutout discard and camera-sorted translucent layers. */
public final class ModelRenderer implements AutoCloseable {
    private final VulkanContext context;
    private final Set<GpuModelMesh> meshes=Collections.newSetFromMap(new IdentityHashMap<>());
    private final UniformBlock camera=new UniformBlock(ModelPipelines.DESCRIPTORS);
    private GpuAllocator allocator;
    private ImmediateCommands commands;
    private ColorTarget target;
    private SampledTexture atlas,lightmap;
    private PackDescriptors descriptors;
    private ModelPipelines pipelines;
    private GpuBuffer readback;
    private boolean closed;

    public ModelRenderer(VulkanContext context,int width,int height,PackTextureData atlas,PackTextureData lightmap) {
        this.context=context;context.requireOwner();
        try{
            allocator=new GpuAllocator(context);commands=new ImmediateCommands(context);target=new ColorTarget(context,width,height);
            this.atlas=new SampledTexture(context,allocator,commands,atlas);this.lightmap=new SampledTexture(context,allocator,commands,lightmap);
            descriptors=new PackDescriptors(context,allocator,ModelPipelines.DESCRIPTORS,Map.of("atlas",this.atlas,"lightmap",this.lightmap));
            pipelines=new ModelPipelines(context,target,descriptors.layout);
            readback=allocator.hostBuffer((long)width*height*4,VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        }catch(RuntimeException|Error failure){close();throw failure;}
    }
    /** Upload completion makes the caller's pooled ModelMesh immediately reusable. */
    public GpuModelMesh upload(ModelMesh source){
        requireOpen();GpuModelMesh mesh=new GpuModelMesh(context,allocator,commands,source,meshes::remove);meshes.add(mesh);return mesh;
    }
    public ByteBuffer render(GpuModelMesh mesh,ModelCamera view){
        requireOpen();if(!meshes.contains(mesh))throw new IllegalArgumentException("Model belongs to another renderer");
        mesh.sort(view);camera.setMatrix("transform",4,view.transform(mesh.originX(),mesh.originY(),mesh.originZ()));descriptors.write(camera.snapshot());
        commands.submit(command->{
            try(MemoryStack stack=VulkanStack.push()){
                GraphicsPipeline first=pipelines.get(RenderLayer.OPAQUE);
                RenderPassCommands.begin(command,target,first.pipeline,first.layout,descriptors.set);
                vkCmdBindVertexBuffers(command,0,stack.longs(mesh.vertices.handle()),stack.longs(0));
                vkCmdBindIndexBuffer(command,mesh.indices.handle(),0,VK_INDEX_TYPE_UINT32);
                for(RenderLayer layer:RenderLayer.values())if(mesh.indexCount(layer)>0){
                    GraphicsPipeline pipeline=pipelines.get(layer);vkCmdBindPipeline(command,VK_PIPELINE_BIND_POINT_GRAPHICS,pipeline.pipeline);
                    vkCmdBindDescriptorSets(command,VK_PIPELINE_BIND_POINT_GRAPHICS,pipeline.layout,0,stack.longs(descriptors.set),null);
                    vkCmdDrawIndexedIndirect(command,mesh.indirect.handle(),layer.ordinal()*20L,1,20);
                }
                vkCmdEndRenderPass(command);target.copyTo(command,readback);
            }
        });
        return readback.read();
    }
    private void requireOpen(){context.requireOwner();if(closed)throw new IllegalStateException("Model renderer closed");}
    @Override public void close(){
        if(closed)return;context.requireOwner();context.waitIdle();
        for(GpuModelMesh mesh:meshes.toArray(new GpuModelMesh[0]))mesh.close();meshes.clear();
        if(readback!=null)readback.close();if(pipelines!=null)pipelines.close();if(descriptors!=null)descriptors.close();
        if(lightmap!=null)lightmap.close();if(atlas!=null)atlas.close();if(target!=null)target.close();
        if(commands!=null)commands.close();if(allocator!=null)allocator.close();closed=true;
    }
}
