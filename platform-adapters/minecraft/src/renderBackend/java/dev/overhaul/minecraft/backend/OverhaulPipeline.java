package dev.overhaul.minecraft.backend;

import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import dev.overhaul.vulkan.NativeGraphicsPipeline;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.lwjgl.vulkan.VK10.*;
import static dev.overhaul.vulkan.VulkanException.check;

/** Owned native pipelines; worker preparation copies SPIR-V before frontend modules can close. */
public final class OverhaulPipeline extends BackendResource implements BackendRenderPipeline {
    private record Prepared(CreateInfo info,List<NativeGraphicsPipeline.Shader> shaders,BitSet activeUniforms,boolean usesDrawIndex) {}
    private record Formats(List<Integer> colors,int depth) {Formats{colors=List.copyOf(colors);}}
    private final Prepared prepared;
    private final Map<Formats,NativeGraphicsPipeline> variants=new HashMap<>();
    private long descriptorLayout;

    private OverhaulPipeline(OverhaulDeviceBackend owner,Prepared prepared) {
        super(owner);this.prepared=prepared;
        try(MemoryStack stack=MemoryStack.stackPush()) {
            VkPhysicalDeviceProperties properties=VkPhysicalDeviceProperties.malloc(stack);vkGetPhysicalDeviceProperties(owner.context().physicalDevice(),properties);
            if(prepared.info().pushConstantsSize()>properties.limits().maxPushConstantsSize())throw new UnsupportedOperationException("Push constants exceed device limit");
            VkDescriptorSetLayoutBinding.Buffer bindings=VkDescriptorSetLayoutBinding.calloc(uniforms().size(),stack);
            for(int slot=0;slot<uniforms().size();slot++)bindings.get(slot).binding(slot).descriptorCount(1)
                    .descriptorType(OverhaulPipelineState.descriptor(uniforms().get(slot).type())).stageFlags(VK_SHADER_STAGE_VERTEX_BIT|VK_SHADER_STAGE_FRAGMENT_BIT);
            LongBuffer pointer=stack.mallocLong(1);
            check(vkCreateDescriptorSetLayout(owner.context().device(),VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings),null,pointer),"overhaul descriptor layout");descriptorLayout=pointer.get(0);
        }catch(RuntimeException|Error failure){close();throw failure;}
    }
    public static Pending compile(OverhaulDeviceBackend owner,CreateInfo source) {
        if(source.pushConstantsSize()<0||source.pushConstantsSize()%4!=0)throw new IllegalArgumentException("Invalid push-constant range");
        List<NativeGraphicsPipeline.Shader> shaders=new ArrayList<>();BitSet active=new BitSet();boolean reflectionComplete=true;
        for(var shader:source.shaders()) {
            shaders.add(new NativeGraphicsPipeline.Shader(shader.module().type()==com.mojang.renderpearl.api.pipeline.ShaderType.VERTEX?VK_SHADER_STAGE_VERTEX_BIT:VK_SHADER_STAGE_FRAGMENT_BIT,shader.entryPoint(),shader.module().spv()));
            var reflection=shader.module().getReflectionInfoIfAvailable();
            if(reflection==null){reflectionComplete=false;continue;}
            for(var descriptor:reflection.descriptors()) {
                int slot=descriptor.binding();
                if(descriptor.descriptorSetIndex()!=0||slot<0||slot>=source.uniforms().size())throw new IllegalArgumentException("SPIR-V descriptor was not flattened to the declared uniform layout");
                active.set(slot);
            }
            for(var constants:reflection.pushConstants())if(constants.size()>source.pushConstantsSize())throw new IllegalArgumentException("SPIR-V push constants exceed pipeline declaration");
        }
        if(shaders.stream().noneMatch(shader->shader.stage()==VK_SHADER_STAGE_VERTEX_BIT)||shaders.stream().map(NativeGraphicsPipeline.Shader::stage).distinct().count()!=shaders.size())throw new IllegalArgumentException("Pipeline requires one vertex stage and no duplicate stages");
        if(!reflectionComplete)active.set(0,source.uniforms().size());
        CreateInfo copy=new CreateInfo(source.name(),List.of(),List.copyOf(source.vertexBuffers()),List.copyOf(source.attribBindings()),List.copyOf(source.uniforms()),source.pushConstantsSize(),
                source.depthStencilState(),source.polygonMode(),source.cull(),java.util.Collections.unmodifiableList(new ArrayList<>(source.colorTargetStates())),source.primitiveTopology());
        Prepared prepared=new Prepared(copy,List.copyOf(shaders),(BitSet)active.clone(),shaders.stream().anyMatch(shader->hasDrawIndex(shader.code())));
        return new Pending(){private OverhaulPipeline result;@Override public synchronized BackendRenderPipeline finishCompile(){if(result==null)result=new OverhaulPipeline(owner,prepared);return result;}};
    }
    NativeGraphicsPipeline variant(long renderPass,List<Integer> colors,int depth) {
        requireNativeAlive();Formats key=new Formats(colors,depth);
        return variants.computeIfAbsent(key,ignored->new NativeGraphicsPipeline(owner().context(),renderPass,
                OverhaulPipelineState.describe(prepared.info(),prepared.shaders(),descriptorLayout,colors,depth)));
    }
    List<BindGroupLayout.UniformDescription> uniforms(){return prepared.info().uniforms();}
    BitSet activeUniforms(){return(BitSet)prepared.activeUniforms().clone();}
    int pushConstantsSize(){return prepared.info().pushConstantsSize();}
    long descriptorLayout(){requireNativeAlive();return descriptorLayout;}
    BackendRenderPipeline.CreateInfo info(){return prepared.info();}
    boolean usesDrawIndex(){return prepared.usesDrawIndex();}
    private static boolean hasDrawIndex(java.nio.ByteBuffer code){
        for(int word=5;word<code.remaining()/4;){int instruction=code.getInt(word*4),count=instruction>>>16,opcode=instruction&65535;
            if(count<1||word+count>code.remaining()/4)throw new IllegalArgumentException("Malformed SPIR-V instruction stream");
            if(opcode==71&&count>=4&&code.getInt((word+2)*4)==11&&code.getInt((word+3)*4)==4426)return true;word+=count;
        }return false;
    }
    @Override protected void destroyNative(){for(var pipeline:variants.values())pipeline.close();variants.clear();if(descriptorLayout!=0)vkDestroyDescriptorSetLayout(owner().context().device(),descriptorLayout,null);descriptorLayout=0;}
}
