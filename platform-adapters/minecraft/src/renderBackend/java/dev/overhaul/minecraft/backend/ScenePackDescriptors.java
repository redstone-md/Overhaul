package dev.overhaul.minecraft.backend;

import dev.overhaul.vulkan.FrameRing;
import dev.overhaul.vulkan.GpuBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.lwjgl.vulkan.VK10.*;
import static dev.overhaul.vulkan.VulkanException.check;

/** Per-pass immutable pack sets borrow the same bounded, fence-retired descriptor pool arena. */
final class ScenePackDescriptors implements AutoCloseable {
    private record Key(ScenePackPipeline pipeline,List<Object> uniforms,Object vertices){}
    private final OverhaulDeviceBackend owner;
    private final Map<DescriptorPoolCache.Shape,List<DescriptorPoolCache.Lease>> pools=new HashMap<>();
    private final Map<Key,Long> sets=new HashMap<>();
    ScenePackDescriptors(OverhaulDeviceBackend owner){this.owner=owner;}
    long set(ScenePackPipeline pipeline,PassBindings bindings,GpuBuffer uniforms){
        var vertex=pipeline.code.normals?bindings.vertices().get(pipeline.code.inputs.storage().slot()):null;Key key=new Key(pipeline,Collections.unmodifiableList(new ArrayList<>(Arrays.asList(bindings.uniforms()))),vertex==null?null:vertex.buffer());
        Long existing=sets.get(key);if(existing!=null)return existing;
        try(MemoryStack stack=MemoryStack.stackPush()){
            var pool=pool(pipeline.shape);LongBuffer pointer=stack.mallocLong(1);
            check(vkAllocateDescriptorSets(owner.context().device(),VkDescriptorSetAllocateInfo.calloc(stack).sType$Default().descriptorPool(pool.handle()).pSetLayouts(stack.longs(pipeline.descriptorLayout)),pointer),"scene pack descriptor allocation");pool.allocated();long set=pointer.get(0);
            var writes=VkWriteDescriptorSet.calloc(pipeline.shape.uniformBuffers()+pipeline.shape.combinedSamplers()+pipeline.shape.storageBuffers(),stack);int slot=0;
            if(uniforms!=null)writes.get(slot++).sType$Default().dstSet(set).dstBinding(0).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                    .pBufferInfo(VkDescriptorBufferInfo.calloc(1,stack).buffer(uniforms.handle()).range(uniforms.size()));
            for(var sampler:pipeline.code.bindings.samplers().entrySet()){
                var sample=pipeline.generation.sample(pipeline,sampler.getKey(),bindings);
                writes.get(slot++).sType$Default().dstSet(set).dstBinding(sampler.getValue()).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                        .pImageInfo(VkDescriptorImageInfo.calloc(1,stack).imageView(sample.view().handle()).sampler(sample.sampler()).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL));
            }
            if(pipeline.code.normals){
                if(vertex==null)throw new IllegalStateException("Quad normal reconstruction requires its actual vertex buffer binding");var buffer=owner.buffer(vertex.buffer());
                var properties=VkPhysicalDeviceProperties.malloc(stack);vkGetPhysicalDeviceProperties(owner.context().physicalDevice(),properties);
                if(buffer.size()>Integer.toUnsignedLong(properties.limits().maxStorageBufferRange()))throw new UnsupportedOperationException("Terrain vertex buffer exceeds the device SSBO range");
                if((buffer.usage()&VK_BUFFER_USAGE_STORAGE_BUFFER_BIT)==0)throw new IllegalStateException("Terrain vertex buffer lacks storage usage");
                // Zero is aligned for every device; the actual vertex slice word offset is supplied per draw.
                writes.get(slot).sType$Default().dstSet(set).dstBinding(pipeline.code.storageBinding).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                        .pBufferInfo(VkDescriptorBufferInfo.calloc(1,stack).buffer(buffer.handle()).offset(0).range(buffer.size()));
            }
            vkUpdateDescriptorSets(owner.context().device(),writes,null);sets.put(key,set);return set;
        }
    }
    private DescriptorPoolCache.Lease pool(DescriptorPoolCache.Shape shape){
        var available=pools.computeIfAbsent(shape,ignored->new ArrayList<>());if(!available.isEmpty()&&available.getLast().hasCapacity())return available.getLast();
        var result=owner.descriptorPools().acquire(shape);available.add(result);return result;
    }
    void submitted(FrameRing.Submission completion){for(var leases:pools.values())for(var lease:leases)lease.submitted(completion);}
    @Override public void close(){for(var leases:pools.values())for(var lease:leases)lease.close();pools.clear();sets.clear();}
}
