package dev.overhaul.minecraft.backend;

import dev.overhaul.minecraft.shader.OwnedReceiverShaders;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import java.nio.LongBuffer;
import java.util.IdentityHashMap;
import java.util.Map;
import static dev.overhaul.vulkan.VulkanException.check;
import static org.lwjgl.vulkan.VK10.*;

/** The generation's receiver descriptor layout and its adapted pipeline for each receiving game pipeline. */
final class CascadeReceiverPipelines implements AutoCloseable {
    private final OverhaulDeviceBackend owner;
    private final OwnedReceiverShaders sources;
    private final Map<OverhaulPipeline,CascadeReceiverPipeline> pipelines=new IdentityHashMap<>();
    private long receiverLayout;

    CascadeReceiverPipelines(OverhaulDeviceBackend owner,OwnedReceiverShaders sources){
        this.owner=owner;this.sources=sources;
        try(MemoryStack stack=MemoryStack.stackPush()){
            VkDescriptorSetLayoutBinding.Buffer bindings=VkDescriptorSetLayoutBinding.calloc(2,stack);
            bindings.get(0).binding(0).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
            bindings.get(1).binding(1).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
            LongBuffer pointer=stack.mallocLong(1);
            check(vkCreateDescriptorSetLayout(owner.context().device(),VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings),null,pointer),"cascade receiver descriptor layout");
            receiverLayout=pointer.get(0);
        }
    }
    long receiverLayout(){return receiverLayout;}
    CascadeReceiverPipeline pipeline(OverhaulPipeline original){
        return pipelines.computeIfAbsent(original,key->new CascadeReceiverPipeline(owner,key,sources,receiverLayout));
    }
    @Override public void close(){
        for(var pipeline:pipelines.values())pipeline.close();pipelines.clear();
        if(receiverLayout!=0){vkDestroyDescriptorSetLayout(owner.context().device(),receiverLayout,null);receiverLayout=0;}
    }
}
