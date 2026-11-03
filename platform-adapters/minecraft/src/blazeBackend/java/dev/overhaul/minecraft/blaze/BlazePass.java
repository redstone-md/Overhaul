package dev.overhaul.minecraft.blaze;

import dev.overhaul.vulkan.GpuBuffer;
import dev.overhaul.vulkan.NativeGraphicsPipeline;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import static dev.overhaul.vulkan.VulkanException.check;
import static org.lwjgl.vulkan.VK10.*;

/** A synchronous native draw transaction; descriptor storage survives the actual submission fence. */
final class BlazePass implements AutoCloseable {
    private final BlazeRenderer owner;
    private final BlazeTarget target;
    private final BlazeMesh mesh;
    private final BlazeDrawState.Snapshot state;
    private GpuBuffer uniforms;
    private long descriptorLayout, pool, set;
    private NativeGraphicsPipeline pipeline;

    BlazePass(BlazeRenderer owner,BlazeTarget target,BlazeMesh mesh,BlazeShaderSnapshot shader,BlazeDrawState.Snapshot state){
        this.owner=owner;this.target=target;this.mesh=mesh;this.state=state;
        try {
            descriptors(shader);
            pipeline=owner.pipeline(shader.program(),mesh.state(),state,target,descriptorLayout);
        }catch(RuntimeException|Error failure){close();throw failure;}
    }

    private void descriptors(BlazeShaderSnapshot shader){
        var bindings=shader.program().bindings();
        List<BlazeTextures.Sampled> samples=new ArrayList<>();
        List<Integer> numbers=new ArrayList<>();
        for(var sampler:bindings.samplers().entrySet()){
            BlazeTextures.Sampled sample=owner.textures().resolve(shader.samplers().get(sampler.getKey()));
            if(sample.view().image()==target.color()||sample.view().image()==target.depth())throw new UnsupportedOperationException("Framebuffer sampling feedback requires an explicit pass dependency");
            samples.add(sample);numbers.add(sampler.getValue());
        }
        try(MemoryStack stack=MemoryStack.stackPush()){
            var nativeBindings=VkDescriptorSetLayoutBinding.calloc(samples.size()+1,stack);
            nativeBindings.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_VERTEX_BIT|VK_SHADER_STAGE_FRAGMENT_BIT);
            for(int index=0;index<samples.size();index++)nativeBindings.get(index+1).binding(numbers.get(index)).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_VERTEX_BIT|VK_SHADER_STAGE_FRAGMENT_BIT);
            var pointer=stack.mallocLong(1);
            check(vkCreateDescriptorSetLayout(owner.context().device(),VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(nativeBindings),null,pointer),"Blaze descriptor layout");descriptorLayout=pointer.get(0);
            var sizes=VkDescriptorPoolSize.calloc(samples.isEmpty()?1:2,stack);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1);
            if(!samples.isEmpty())sizes.get(1).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(samples.size());
            check(vkCreateDescriptorPool(owner.context().device(),VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(sizes),null,pointer),"Blaze descriptor pool");pool=pointer.get(0);
            check(vkAllocateDescriptorSets(owner.context().device(),VkDescriptorSetAllocateInfo.calloc(stack).sType$Default().descriptorPool(pool).pSetLayouts(stack.longs(descriptorLayout)),pointer),"Blaze descriptor set");set=pointer.get(0);
            var bytes=shader.uniforms();
            uniforms=owner.allocator().hostBuffer(Math.max(16,bytes.remaining()),VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
            if(bytes.hasRemaining())uniforms.write(bytes);
            else uniforms.write(stack.calloc(16));
            var writes=VkWriteDescriptorSet.calloc(samples.size()+1,stack);
            writes.get(0).sType$Default().dstSet(set).dstBinding(0).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1)
                    .pBufferInfo(VkDescriptorBufferInfo.calloc(1,stack).buffer(uniforms.handle()).offset(0).range(uniforms.size()));
            for(int index=0;index<samples.size();index++){
                var sample=samples.get(index);
                writes.get(index+1).sType$Default().dstSet(set).dstBinding(numbers.get(index)).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1)
                        .pImageInfo(VkDescriptorImageInfo.calloc(1,stack).imageView(sample.view().handle()).sampler(sample.sampler().handle()).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL));
            }
            vkUpdateDescriptorSets(owner.context().device(),writes,null);
        }
    }

    void record(VkCommandBuffer command){
        int x=state.scissor()?Math.max(0,state.sx()):0,y=state.scissor()?Math.max(0,state.sy()):0;
        long right=state.scissor()?Math.min(target.width(),(long)state.sx()+state.sw()):target.width();
        long top=state.scissor()?Math.min(target.height(),(long)state.sy()+state.sh()):target.height();
        if(state.width()==0||state.height()==0||right<=x||top<=y||mesh.state().indexCount()==0)return;
        try(MemoryStack stack=MemoryStack.stackPush()){
            target.begin(command,stack);
            vkCmdBindPipeline(command,VK_PIPELINE_BIND_POINT_GRAPHICS,pipeline.handle());
            vkCmdBindDescriptorSets(command,VK_PIPELINE_BIND_POINT_GRAPHICS,pipeline.layout(),0,stack.longs(set),null);
            vkCmdSetViewport(command,0,VkViewport.calloc(1,stack).x(state.x()).y(state.y()).width(state.width()).height(state.height()).minDepth(0).maxDepth(1));
            var clip=VkRect2D.calloc(1,stack);clip.offset().set(x,y);clip.extent().set((int)(right-x),(int)(top-y));vkCmdSetScissor(command,0,clip);
            mesh.record(command);vkCmdEndRenderPass(command);
        }
    }

    @Override public void close(){
        if(pool!=0){vkDestroyDescriptorPool(owner.context().device(),pool,null);pool=0;}
        if(descriptorLayout!=0){vkDestroyDescriptorSetLayout(owner.context().device(),descriptorLayout,null);descriptorLayout=0;}
        if(uniforms!=null){uniforms.close();uniforms=null;}
    }
}
