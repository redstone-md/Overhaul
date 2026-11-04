package dev.overhaul.minecraft.backend;

import dev.overhaul.minecraft.shader.OwnedTerrainShaders;
import dev.overhaul.shader.compile.ShadercCompiler;
import dev.overhaul.vulkan.NativeGraphicsPipeline;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.lwjgl.vulkan.VK10.*;

/** One game terrain pipeline with the receiver shaders: same state, game set 0, receiver set 1. */
final class CascadeTerrainPipeline implements AutoCloseable {
    private static final String CHUNK_INPUT="ChunkPosition",MULTIDRAW="MULTIDRAW_TERRAIN";
    private record Formats(List<Integer> colors,int depth){Formats{colors=List.copyOf(colors);}}
    private final OverhaulDeviceBackend owner;
    private final OverhaulPipeline original;
    private final long receiverLayout;
    private final List<NativeGraphicsPipeline.Shader> shaders;
    private final Map<Formats,NativeGraphicsPipeline> variants=new HashMap<>();

    CascadeTerrainPipeline(OverhaulDeviceBackend owner,OverhaulPipeline original,OwnedTerrainShaders sources,long receiverLayout){
        this.owner=owner;this.original=original;this.receiverLayout=receiverLayout;
        var metadata=original.compileMetadata();Map<String,String> defines=new HashMap<>(metadata.defines());
        if(metadata.inputs().stream().anyMatch(input->input.fields().containsKey(CHUNK_INPUT)))defines.putIfAbsent(MULTIDRAW,"1");
        var names=original.uniforms().stream().map(uniform->uniform.name()).toList();
        try(ShadercCompiler compiler=new ShadercCompiler()){
            var compiled=sources.compile(defines,metadata.flags(),names,compiler);
            shaders=List.of(new NativeGraphicsPipeline.Shader(VK_SHADER_STAGE_VERTEX_BIT,"main",compiled.vertex().directBuffer()),
                    new NativeGraphicsPipeline.Shader(VK_SHADER_STAGE_FRAGMENT_BIT,"main",compiled.fragment().directBuffer()));
        }
    }
    /** Excludes the pipelines whose shaders are not the pinned terrain text this receiver rewrites. */
    static boolean accepts(OverhaulPipeline pipeline){
        var metadata=pipeline.compileMetadata();
        return metadata!=null&&metadata.family()==PipelineCompileMetadata.Family.TERRAIN&&!metadata.modelInstances()
                &&!metadata.defined("OIT")&&!metadata.defined("OIT_ACCUMULATE")&&!metadata.defined("OIT_ALPHA_ONLY");
    }
    void bind(VkCommandBuffer command,PassBindings bindings,PassAttachments attachments,PassDescriptors descriptors,long receiverSet){
        var variant=variant(attachments.renderPass,attachments.colorFormats(),attachments.depthFormat());
        vkCmdBindPipeline(command,VK_PIPELINE_BIND_POINT_GRAPHICS,variant.handle());
        try(MemoryStack stack=MemoryStack.stackPush()){
            vkCmdBindDescriptorSets(command,VK_PIPELINE_BIND_POINT_GRAPHICS,variant.layout(),0,stack.longs(descriptors.set(original,bindings.uniforms()),receiverSet),null);
            if(bindings.constants()!=null&&original.pushConstantsSize()>0)vkCmdPushConstants(command,variant.layout(),VK_SHADER_STAGE_VERTEX_BIT|VK_SHADER_STAGE_FRAGMENT_BIT,0,bindings.constants());
        }
        bindings.geometry(command,owner,attachments);
    }
    private NativeGraphicsPipeline variant(long renderPass,List<Integer> colors,int depth){
        return variants.computeIfAbsent(new Formats(colors,depth),key->{
            var base=OverhaulPipelineState.describe(original.info(),shaders,original.descriptorLayout(),colors,depth);
            return new NativeGraphicsPipeline(owner.context(),renderPass,new NativeGraphicsPipeline.Description(base.shaders(),base.bindings(),base.attributes(),
                    new long[]{original.descriptorLayout(),receiverLayout},base.pushConstantsSize(),base.topology(),base.polygonMode(),base.cullMode(),base.frontFace(),
                    base.depth(),base.colors(),base.dynamicViewport(),base.width(),base.height(),base.colorLogic()));
        });
    }
    @Override public void close(){for(var variant:variants.values())variant.close();variants.clear();}
}
