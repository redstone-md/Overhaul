package dev.overhaul.vulkan;

import dev.overhaul.shader.compat.CoordinateConvention;
import dev.overhaul.shader.compat.FramebufferBindings;
import dev.overhaul.shader.pack.CompiledShaderPack;
import dev.overhaul.shader.pack.PackComputeProgram;
import dev.overhaul.shader.pack.ShaderPackProgram;
import dev.overhaul.shader.pack.ShaderPackProperties;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import java.nio.LongBuffer;
import java.util.List;
import java.util.Map;
import static org.lwjgl.vulkan.VK10.*;
import static dev.overhaul.vulkan.VulkanException.check;

/** One pack compute program: a dispatch that reads graph images and reads and writes pack images. */
final class PackComputePass implements AutoCloseable {
    private final VulkanContext context;
    private final PackComputeProgram compute;
    /** Stands in for the compute where uniform filling expects a program: it carries the name, stage and bindings. */
    final CompiledShaderPack.Program program;
    private PackPassDescriptors descriptors;
    private long pipelineLayout,pipeline;

    PackComputePass(VulkanContext context,GpuAllocator allocator,PackComputeProgram compute){
        this.context=context;this.compute=compute;
        if(!context.supportsCompute())throw new dev.overhaul.shader.compat.UnsupportedShaderException("Selected queue cannot run pack compute: "+compute.name());
        var code=new ShaderPackProgram(compute.code(),compute.code(),compute.bindings(),new FramebufferBindings(List.of()),CoordinateConvention.NATIVE_VULKAN);
        program=new CompiledShaderPack.Program(compute.name().replace(".csh",""),compute.stage(),compute.ordinal(),code,
                new ShaderPackProperties.ProgramState(true,Map.of(),java.util.Optional.empty(),Map.of()));
        try(MemoryStack stack=VulkanStack.push()){
            descriptors=new PackPassDescriptors(context,allocator,compute.bindings(),VK_SHADER_STAGE_COMPUTE_BIT);
            LongBuffer pointer=stack.mallocLong(1);
            check(vkCreatePipelineLayout(context.device(),VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pSetLayouts(stack.longs(descriptors.layout())),null,pointer),"pack compute layout");
            pipelineLayout=pointer.get(0);
            check(vkCreateShaderModule(context.device(),VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(compute.code().directBuffer()),null,pointer),"pack compute module");
            long module=pointer.get(0);
            try{
                var info=VkComputePipelineCreateInfo.calloc(1,stack).sType$Default().layout(pipelineLayout);
                info.stage().sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
                check(vkCreateComputePipelines(context.device(),VK_NULL_HANDLE,info,null,pointer),"pack compute pipeline");pipeline=pointer.get(0);
            }finally{vkDestroyShaderModule(context.device(),module,null);}
        }catch(RuntimeException|Error failure){close();throw failure;}
    }
    PackComputeProgram compute(){return compute;}
    dev.overhaul.shader.compat.UniformBlock values(){return descriptors.values;}
    int upload(){return descriptors.upload();}

    /** Fenced on both sides: pack images skip graph tracking, and the dispatch may write any of them. */
    void record(VulkanRenderGraph.PassContext pass,Map<String,String> samples,Map<String,Long> samplers,long sampler,int uniformOffset,PackImageSet images,int width,int height){
        long set=descriptors.set(pass,samples,samplers,sampler,images);
        int[] groups=compute.groups(width,height);
        try(MemoryStack stack=VulkanStack.push()){
            PackImageSet.barrier(pass.command());
            vkCmdBindPipeline(pass.command(),VK_PIPELINE_BIND_POINT_COMPUTE,pipeline);
            vkCmdBindDescriptorSets(pass.command(),VK_PIPELINE_BIND_POINT_COMPUTE,pipelineLayout,0,stack.longs(set),descriptors.offsets(stack,uniformOffset));
            vkCmdDispatch(pass.command(),groups[0],groups[1],groups[2]);
            PackImageSet.barrier(pass.command());
        }
    }
    @Override public void close(){
        context.requireOwner();
        if(pipeline!=0)vkDestroyPipeline(context.device(),pipeline,null);if(pipelineLayout!=0)vkDestroyPipelineLayout(context.device(),pipelineLayout,null);
        pipeline=pipelineLayout=0;if(descriptors!=null){descriptors.close();descriptors=null;}
    }
}
