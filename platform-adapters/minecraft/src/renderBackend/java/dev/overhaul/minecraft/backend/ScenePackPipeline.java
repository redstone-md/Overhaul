package dev.overhaul.minecraft.backend;

import dev.overhaul.shader.compat.UniformBlock;
import dev.overhaul.shader.compat.UnsupportedShaderException;
import dev.overhaul.shader.pack.CompiledShaderPack;
import dev.overhaul.shader.compile.ShadercCompiler;
import dev.overhaul.vulkan.GpuBuffer;
import dev.overhaul.vulkan.NativeGraphicsPipeline;
import dev.overhaul.vulkan.PackGraphRuntime;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.lwjgl.vulkan.VK10.*;
import static dev.overhaul.vulkan.VulkanException.check;

/** One actual game input layout adapted to a pack program; formats select compatible native variants. */
final class ScenePackPipeline implements AutoCloseable {
    private record Formats(List<Integer> colors,int depth){Formats{colors=List.copyOf(colors);}}
    final ShaderPackSceneGeneration generation;
    final OverhaulPipeline original;
    final ScenePackProgram code;
    final CompiledShaderPack.Program program;
    final DescriptorPoolCache.Shape shape;
    private final Map<Formats,NativeGraphicsPipeline> variants=new HashMap<>();
    final UniformBlock values;
    private final ByteBuffer upload;
    GpuBuffer uniforms;
    long descriptorLayout;
    ScenePackPipeline(ShaderPackSceneGeneration generation,OverhaulPipeline original,CompiledShaderPack.Program program){
        this.generation=generation;this.original=original;this.program=program;
        if(original.pushConstantsSize()!=0)throw new UnsupportedShaderException("Scene source push constants need an explicit pack ABI");
        try(ShadercCompiler compiler=new ShadercCompiler()){code=new ScenePackProgram(generation.pack(),program,original,compiler);}
        values=new UniformBlock(code.bindings);upload=ByteBuffer.allocateDirect(code.bindings.uniformBytes()).order(ByteOrder.nativeOrder());
        shape=new DescriptorPoolCache.Shape(upload.capacity()>0?1:0,code.bindings.samplers().size(),0,code.normals?1:0,64);
        var owner=original.owner();
        try(MemoryStack stack=MemoryStack.stackPush()){
            if(code.bindings.samplerTypes().containsValue("sampler2DShadow"))owner.context().portability().requireMutableComparisonSamplers();
            int count=shape.uniformBuffers()+shape.combinedSamplers()+shape.storageBuffers();var bindings=VkDescriptorSetLayoutBinding.calloc(count,stack);int slot=0;
            if(shape.uniformBuffers()>0){uniforms=owner.allocator().hostBuffer(upload.capacity(),VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
                bindings.get(slot++).binding(0).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).stageFlags(VK_SHADER_STAGE_VERTEX_BIT|VK_SHADER_STAGE_FRAGMENT_BIT);}
            for(int binding:code.bindings.samplers().values())bindings.get(slot++).binding(binding).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).stageFlags(VK_SHADER_STAGE_VERTEX_BIT|VK_SHADER_STAGE_FRAGMENT_BIT);
            if(code.normals)bindings.get(slot).binding(code.storageBinding).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).stageFlags(VK_SHADER_STAGE_VERTEX_BIT);
            LongBuffer pointer=stack.mallocLong(1);check(vkCreateDescriptorSetLayout(owner.context().device(),VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings),null,pointer),"scene pack descriptor layout");descriptorLayout=pointer.get(0);
            update();
        }catch(RuntimeException|Error failure){close();throw failure;}
    }
    void update(){generation.fill(program,values);if(uniforms!=null){upload.clear();values.copyTo(upload);upload.flip();uniforms.write(upload);}}
    boolean needsDepthRefresh(){
        return program.stage()!=CompiledShaderPack.Stage.SHADOW&&code.bindings.samplers().keySet().stream().anyMatch(name->{
            if(generation.pack().textures().image(program,name)!=null)return false;
            int index=dev.overhaul.shader.pack.PackRenderTargets.depthIndex(name);return index==0||index==2;
        });
    }
    NativeGraphicsPipeline variant(PassAttachments attachments){
        Formats key=new Formats(attachments.colorFormats(),attachments.depthFormat());NativeGraphicsPipeline result=variants.get(key);if(result!=null)return result;
        var stages=List.of(new NativeGraphicsPipeline.Shader(VK_SHADER_STAGE_VERTEX_BIT,"main",code.vertex.directBuffer()),new NativeGraphicsPipeline.Shader(VK_SHADER_STAGE_FRAGMENT_BIT,"main",code.fragment.directBuffer()));
        int count=Math.max(key.colors().size(),original.info().colorTargetStates().size());
        var baseline=OverhaulPipelineState.describe(original.info(),stages,descriptorLayout,java.util.Collections.nCopies(count,VK_FORMAT_UNDEFINED),key.depth());
        boolean shadow=program.stage()==CompiledShaderPack.Stage.SHADOW;
        List<NativeGraphicsPipeline.Color> colors=new ArrayList<>();
        for(int location=0;location<program.code().framebuffer().colorTargets().size();location++){
            int target=program.code().framebuffer().colorTargets().get(location);
            var inherited=baseline.colors().get(location<original.info().colorTargetStates().size()?location:0);
            boolean explicit=program.state().blends().containsKey("*")||program.state().blends().containsKey((shadow?"shadowcolor":"colortex")+target);
            var selected=explicit||shadow?(shadow?PackGraphRuntime.shadowColorState(program.state(),target):PackGraphRuntime.colorState(program.state(),target)):inherited;
            int mask=shadow?VK_COLOR_COMPONENT_R_BIT|VK_COLOR_COMPONENT_G_BIT|VK_COLOR_COMPONENT_B_BIT|VK_COLOR_COMPONENT_A_BIT:inherited.writeMask();
            colors.add(new NativeGraphicsPipeline.Color(selected.blend(),selected.sourceRgb(),selected.destinationRgb(),selected.operationRgb(),selected.sourceAlpha(),selected.destinationAlpha(),selected.operationAlpha(),target<0?0:mask));
        }
        var depth=shadow?new NativeGraphicsPipeline.Depth(true,true,VK_COMPARE_OP_LESS_OR_EQUAL,0,0):baseline.depth();
        result=new NativeGraphicsPipeline(original.owner().context(),attachments.renderPass,new NativeGraphicsPipeline.Description(stages,code.inputs.bindings(),code.inputs.attributes(),
                new long[]{descriptorLayout,original.descriptorLayout()},code.normals?4:0,baseline.topology(),baseline.polygonMode(),baseline.cullMode(),baseline.frontFace(),depth,colors,true,0,0));
        variants.put(key,result);return result;
    }
    void bind(VkCommandBuffer command,PassBindings bindings,PassAttachments attachments,PassDescriptors gameDescriptors,ScenePackDescriptors packDescriptors){
        var owner=original.owner();var pipeline=variant(attachments);
        try(MemoryStack stack=MemoryStack.stackPush()){
            long packSet=packDescriptors.set(this,bindings),gameSet=gameDescriptors.set(original,bindings.uniforms());
            vkCmdBindPipeline(command,VK_PIPELINE_BIND_POINT_GRAPHICS,pipeline.handle());vkCmdBindDescriptorSets(command,VK_PIPELINE_BIND_POINT_GRAPHICS,pipeline.layout(),0,stack.longs(packSet,gameSet),null);
            if(code.normals){var source=bindings.vertices().get(code.inputs.storage().slot());if(source==null||source.offset()%4!=0||source.offset()/4>Integer.MAX_VALUE)throw new UnsupportedShaderException("Scene vertex address cannot be represented by its SSBO word offset");
                vkCmdPushConstants(command,pipeline.layout(),VK_SHADER_STAGE_VERTEX_BIT|VK_SHADER_STAGE_FRAGMENT_BIT,0,stack.ints((int)(source.offset()/4)));}
            bindings.geometry(command,owner,attachments,code.inputs.bindings());
        }
    }
    @Override public void close(){var owner=original.owner();for(var pipeline:variants.values())pipeline.close();variants.clear();if(descriptorLayout!=0)vkDestroyDescriptorSetLayout(owner.context().device(),descriptorLayout,null);descriptorLayout=0;if(uniforms!=null){uniforms.close();uniforms=null;}}
}
