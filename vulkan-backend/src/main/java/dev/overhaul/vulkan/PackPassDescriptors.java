package dev.overhaul.vulkan;

import dev.overhaul.shader.compat.ProgramBindings;
import dev.overhaul.shader.compat.UniformBlock;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.lwjgl.vulkan.VK10.*;
import static dev.overhaul.vulkan.VulkanException.check;

/**
 * The descriptor ABI the translator gives a pack program: a dynamic uniform block at binding 0, then one binding per
 * sampler, storage image or storage buffer. Sets are cached per bound image combination, and uniforms rotate through copies.
 */
final class PackPassDescriptors implements AutoCloseable {
    private record Sample(Object identity,long view,long sampler,int type,int layout){}
    private static final int MAX_SETS=32;
    /**
     * A pass runs once a frame and may be queued behind frames still on the GPU, so each run writes its values into
     * the next of these copies. The frame ring keeps three submissions in flight and every frame submits at least once.
     */
    private static final int UNIFORM_COPIES=8;
    private final VulkanContext context;
    private final ProgramBindings bindings;
    private final Map<Map<String,Sample>,Long> sets=new LinkedHashMap<>();
    private final ByteBuffer upload;
    private GpuBuffer uniforms;
    private long layout,pool;
    private int stride,copy;
    final UniformBlock values;

    PackPassDescriptors(VulkanContext context,GpuAllocator allocator,ProgramBindings bindings,int stages){
        this.context=context;this.bindings=bindings;values=new UniformBlock(bindings);
        upload=ByteBuffer.allocateDirect(bindings.uniformBytes()).order(ByteOrder.nativeOrder());
        int storage=(int)bindings.samplers().keySet().stream().filter(bindings::storageImage).count();
        int buffers=(int)bindings.samplers().keySet().stream().filter(bindings::storageBuffer).count(),combined=bindings.samplers().size()-storage-buffers;
        try(MemoryStack stack=VulkanStack.push()){
            var entries=VkDescriptorSetLayoutBinding.calloc(bindings.samplers().size()+(bindings.uniformBytes()>0?1:0),stack);int slot=0;
            if(bindings.uniformBytes()>0){
                var properties=VkPhysicalDeviceProperties.malloc(stack);vkGetPhysicalDeviceProperties(context.physicalDevice(),properties);
                int alignment=(int)Math.max(properties.limits().minUniformBufferOffsetAlignment(),properties.limits().nonCoherentAtomSize());
                stride=(bindings.uniformBytes()+alignment-1)/alignment*alignment;
                uniforms=allocator.hostBuffer((long)stride*UNIFORM_COPIES,VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
                entries.get(slot++).binding(0).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC).stageFlags(stages);
            }
            for(var binding:bindings.samplers().entrySet())entries.get(slot++).binding(binding.getValue()).descriptorCount(1).descriptorType(type(binding.getKey())).stageFlags(stages);
            LongBuffer pointer=stack.mallocLong(1);
            check(vkCreateDescriptorSetLayout(context.device(),VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(entries),null,pointer),"pack pass descriptor layout");layout=pointer.get(0);
            var sizes=VkDescriptorPoolSize.calloc((uniforms==null?0:1)+(combined==0?0:1)+(storage==0?0:1)+(buffers==0?0:1),stack);int index=0;
            if(uniforms!=null)sizes.get(index++).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC).descriptorCount(MAX_SETS);
            if(combined>0)sizes.get(index++).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(combined*MAX_SETS);
            if(storage>0)sizes.get(index++).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(storage*MAX_SETS);
            if(buffers>0)sizes.get(index).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(buffers*MAX_SETS);
            check(vkCreateDescriptorPool(context.device(),VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(MAX_SETS).pPoolSizes(sizes),null,pointer),"pack pass descriptor pool");pool=pointer.get(0);
        }catch(RuntimeException|Error failure){close();throw failure;}
    }
    long layout(){return layout;}
    boolean hasUniforms(){return uniforms!=null;}
    private int type(String name){
        return bindings.storageBuffer(name)?VK_DESCRIPTOR_TYPE_STORAGE_BUFFER:bindings.storageImage(name)?VK_DESCRIPTOR_TYPE_STORAGE_IMAGE:VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    }

    /** Writes the current values into the next copy and returns where it starts, for the matching record. */
    int upload(){
        if(uniforms==null)return 0;
        int offset=copy*stride;copy=(copy+1)%UNIFORM_COPIES;
        upload.clear();values.copyTo(upload);upload.flip();uniforms.write(offset,upload);return offset;
    }

    /**
     * Graph images come from {@code samples}, already in SHADER_READ_ONLY; pack images come from {@code images} in
     * GENERAL. Every binding must resolve from one of the two.
     */
    long set(VulkanRenderGraph.PassContext pass,Map<String,String> samples,Map<String,Long> samplers,long sampler,PackImageSet images){
        Map<String,Sample> proposed=new LinkedHashMap<>();
        for(String name:bindings.samplers().keySet()){
            String graph=samples.get(name);
            if(graph!=null){
                var image=pass.image(graph);
                proposed.put(name,new Sample(image.identity(),image.view(),samplers.getOrDefault(name,sampler),VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL));
                continue;
            }
            if(bindings.storageBuffer(name)){
                GpuBuffer buffer=images==null?null:images.buffer(bindings.bufferIndex(name));
                if(buffer==null)throw new IllegalArgumentException("Pack pass storage buffer is undeclared: "+name);
                proposed.put(name,new Sample(buffer,buffer.handle(),0,VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,0));
                continue;
            }
            boolean storage=bindings.storageImage(name);
            GpuImageView view=images==null?null:storage?images.storage(name):images.sampled(name);
            if(view==null)throw new IllegalArgumentException("Pack pass binding is unresolved: "+name);
            proposed.put(name,new Sample(view,view.handle(),storage?0:images.sampler(name),type(name),VK_IMAGE_LAYOUT_GENERAL));
        }
        Long existing=sets.get(proposed);if(existing!=null)return existing;
        if(sets.size()>=MAX_SETS)throw new IllegalStateException("Pack pass descriptor routing exceeds bounded immutable states");
        try(MemoryStack stack=VulkanStack.push()){
            LongBuffer pointer=stack.mallocLong(1);
            check(vkAllocateDescriptorSets(context.device(),VkDescriptorSetAllocateInfo.calloc(stack).sType$Default().descriptorPool(pool).pSetLayouts(stack.longs(layout)),pointer),"pack pass descriptor allocation");
            long set=pointer.get(0);
            var writes=VkWriteDescriptorSet.calloc(proposed.size()+(uniforms==null?0:1),stack);int slot=0;
            if(uniforms!=null)writes.get(slot++).sType$Default().dstSet(set).dstBinding(0).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC)
                    .pBufferInfo(VkDescriptorBufferInfo.calloc(1,stack).buffer(uniforms.handle()).range(bindings.uniformBytes()));
            for(var sample:proposed.entrySet()){
                Sample value=sample.getValue();
                var write=writes.get(slot++).sType$Default().dstSet(set).dstBinding(bindings.samplers().get(sample.getKey())).descriptorCount(1).descriptorType(value.type());
                if(value.type()==VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)write.pBufferInfo(VkDescriptorBufferInfo.calloc(1,stack).buffer(value.view()).range(VK_WHOLE_SIZE));
                else write.pImageInfo(VkDescriptorImageInfo.calloc(1,stack).sampler(value.sampler()).imageView(value.view()).imageLayout(value.layout()));
            }
            vkUpdateDescriptorSets(context.device(),writes,null);sets.put(Map.copyOf(proposed),set);return set;
        }
    }
    /** The dynamic offsets a bind of a set from {@link #set} takes. */
    java.nio.IntBuffer offsets(MemoryStack stack,int uniformOffset){return uniforms==null?null:stack.ints(uniformOffset);}

    @Override public void close(){
        context.requireOwner();
        if(pool!=0)vkDestroyDescriptorPool(context.device(),pool,null);if(layout!=0)vkDestroyDescriptorSetLayout(context.device(),layout,null);
        pool=layout=0;sets.clear();if(uniforms!=null){uniforms.close();uniforms=null;}
    }
}
