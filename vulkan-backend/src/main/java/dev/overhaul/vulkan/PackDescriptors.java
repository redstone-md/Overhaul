package dev.overhaul.vulkan;

import dev.overhaul.shader.compat.ProgramBindings;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.Map;
import java.util.TreeMap;
import static org.lwjgl.vulkan.VK10.*;
import static dev.overhaul.vulkan.VulkanException.check;

/** One complete descriptor set matching the translator's generated UBO/sampler ABI. */
final class PackDescriptors implements AutoCloseable {
    private final VulkanContext context;
    private GpuBuffer uniforms;
    long layout;
    long set;
    private long pool;

    PackDescriptors(VulkanContext context,GpuAllocator allocator,ProgramBindings bindings,Map<String,SampledTexture> textures) {
        this.context=context;
        if (!textures.keySet().equals(bindings.samplers().keySet())) throw new IllegalArgumentException("Textures must exactly match the shader samplers");
        if (textures.size()>16) throw new IllegalArgumentException("Diagnostic pack renderer supports at most 16 samplers");
        try (MemoryStack stack=VulkanStack.push()) {
            uniforms=allocator.hostBuffer(Math.max(16,bindings.uniformBytes()),VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
            int stages=VK_SHADER_STAGE_VERTEX_BIT|VK_SHADER_STAGE_FRAGMENT_BIT;
            VkDescriptorSetLayoutBinding.Buffer entries=VkDescriptorSetLayoutBinding.calloc(1+textures.size(),stack);
            entries.get(0).binding(0).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).stageFlags(stages);
            int index=1;
            for (var binding:new TreeMap<>(bindings.samplers()).entrySet()) entries.get(index++).binding(binding.getValue())
                    .descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).stageFlags(stages);
            LongBuffer pointer=stack.mallocLong(1);
            check(vkCreateDescriptorSetLayout(context.device(),VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(entries),null,pointer),"vkCreateDescriptorSetLayout");
            layout=pointer.get(0);
            VkDescriptorPoolSize.Buffer sizes=VkDescriptorPoolSize.calloc(textures.isEmpty()?1:2,stack);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1);
            if (!textures.isEmpty()) sizes.get(1).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(textures.size());
            check(vkCreateDescriptorPool(context.device(),VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(sizes),null,pointer),"vkCreateDescriptorPool");
            pool=pointer.get(0);
            check(vkAllocateDescriptorSets(context.device(),VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(pool).pSetLayouts(stack.longs(layout)),pointer),"vkAllocateDescriptorSets"); set=pointer.get(0);
            VkWriteDescriptorSet.Buffer writes=VkWriteDescriptorSet.calloc(1+textures.size(),stack);
            writes.get(0).sType$Default().dstSet(set).dstBinding(0).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1)
                    .pBufferInfo(VkDescriptorBufferInfo.calloc(1,stack).buffer(uniforms.handle()).offset(0).range(uniforms.size()));
            index=1;
            for (var binding:new TreeMap<>(bindings.samplers()).entrySet()) {
                SampledTexture texture=textures.get(binding.getKey());
                writes.get(index++).sType$Default().dstSet(set).dstBinding(binding.getValue()).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                        .descriptorCount(1).pImageInfo(VkDescriptorImageInfo.calloc(1,stack).sampler(texture.sampler).imageView(texture.view()).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL));
            }
            vkUpdateDescriptorSets(context.device(),writes,null);
        } catch (RuntimeException|Error failure) { close(); throw failure; }
    }
    void write(ByteBuffer data) { uniforms.write(data); }
    @Override public void close() {
        context.requireOwner();
        if (pool!=0) vkDestroyDescriptorPool(context.device(),pool,null);
        if (layout!=0) vkDestroyDescriptorSetLayout(context.device(),layout,null);
        pool=layout=set=0;
        if (uniforms!=null) { uniforms.close(); uniforms=null; }
    }
}
