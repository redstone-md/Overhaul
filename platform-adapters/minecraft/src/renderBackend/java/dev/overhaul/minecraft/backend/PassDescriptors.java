package dev.overhaul.minecraft.backend;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.util.TextureViewAndSampler;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.lwjgl.vulkan.VK10.*;
import static dev.overhaul.vulkan.VulkanException.check;

/** Immutable descriptor sets are cached per draw state and retired after the submission fence. */
final class PassDescriptors implements AutoCloseable {
    private static final int SETS_PER_POOL=64;
    private record Key(OverhaulPipeline pipeline,List<Object> values) {}
    private record BufferView(GpuBufferSlice slice,int format) {}
    private static final class Pool {final long handle;int remaining=SETS_PER_POOL;Pool(long handle){this.handle=handle;}}
    private final NativePassResources resources;
    private final Map<OverhaulPipeline,List<Pool>> pools=new HashMap<>();
    private final Map<Key,Long> sets=new HashMap<>();
    private final Map<BufferView,Long> bufferViews=new HashMap<>();

    PassDescriptors(NativePassResources resources){this.resources=resources;}
    long set(OverhaulPipeline pipeline,Object[] uniforms){
        List<Object> activeValues=new ArrayList<>();var active=pipeline.activeUniforms();
        for(int slot=active.nextSetBit(0);slot>=0;slot=active.nextSetBit(slot+1)){
            if(slot>=uniforms.length||uniforms[slot]==null)throw new IllegalStateException("Missing uniform "+pipeline.uniforms().get(slot).name());
            activeValues.add(uniforms[slot]);
        }
        Key key=new Key(pipeline,List.copyOf(activeValues));Long existing=sets.get(key);if(existing!=null)return existing;
        try(MemoryStack stack=MemoryStack.stackPush()){
            Pool pool=pool(pipeline,stack);LongBuffer pointer=stack.mallocLong(1);
            check(vkAllocateDescriptorSets(resources.context().device(),VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(pool.handle).pSetLayouts(stack.longs(pipeline.descriptorLayout())),pointer),"overhaul descriptor allocation");
            pool.remaining--;long set=pointer.get(0);
            VkWriteDescriptorSet.Buffer writes=VkWriteDescriptorSet.calloc(active.cardinality(),stack);int output=0;
            VkPhysicalDeviceProperties properties=VkPhysicalDeviceProperties.malloc(stack);vkGetPhysicalDeviceProperties(resources.context().physicalDevice(),properties);
            for(int slot=active.nextSetBit(0);slot>=0;slot=active.nextSetBit(slot+1)){
                var declaration=pipeline.uniforms().get(slot);Object value=uniforms[slot];
                VkWriteDescriptorSet write=writes.get(output++).sType$Default().dstSet(set).dstBinding(slot).descriptorCount(1).descriptorType(OverhaulPipelineState.descriptor(declaration.type()));
                if(declaration.type()==UniformType.COMBINED_IMAGE_SAMPLER){
                    if(!(value instanceof TextureViewAndSampler pair))throw new IllegalArgumentException("Uniform is not an image/sampler: "+declaration.name());
                    var view=resources.texture(pair.view());int layout=VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
                    write.pImageInfo(VkDescriptorImageInfo.calloc(1,stack).imageView(view.handle()).sampler(resources.sampler(pair.sampler()).handle()).imageLayout(layout));
                }else{
                    if(!(value instanceof GpuBufferSlice slice))throw new IllegalArgumentException("Uniform is not a buffer slice: "+declaration.name());
                    var buffer=resources.buffer(slice.buffer());checkRange(slice,buffer.size());
                    if(declaration.type()==UniformType.UNIFORM_BUFFER){
                        if(slice.offset()%properties.limits().minUniformBufferOffsetAlignment()!=0||slice.length()>properties.limits().maxUniformBufferRange())throw new IllegalArgumentException("Uniform buffer range/alignment exceeds device limits");
                        write.pBufferInfo(VkDescriptorBufferInfo.calloc(1,stack).buffer(buffer.handle()).offset(slice.offset()).range(slice.length()));
                    }else{
                        if(declaration.gpuFormat()==null)throw new IllegalArgumentException("Texel buffer has no declared format");
                        int format=OverhaulFormats.format(declaration.gpuFormat());
                        if(slice.offset()%properties.limits().minTexelBufferOffsetAlignment()!=0||slice.length()%declaration.gpuFormat().blockSize()!=0
                                ||slice.length()/declaration.gpuFormat().blockSize()>Integer.toUnsignedLong(properties.limits().maxTexelBufferElements()))throw new IllegalArgumentException("Texel buffer alignment/range is invalid");
                        BufferView viewKey=new BufferView(slice,format);
                        Long view=bufferViews.get(viewKey);
                        if(view==null){
                            VkFormatProperties support=VkFormatProperties.malloc(stack);vkGetPhysicalDeviceFormatProperties(resources.context().physicalDevice(),format,support);
                            if((support.bufferFeatures()&VK_FORMAT_FEATURE_UNIFORM_TEXEL_BUFFER_BIT)==0)throw new UnsupportedOperationException("Uniform texel-buffer format unavailable");
                            check(vkCreateBufferView(resources.context().device(),VkBufferViewCreateInfo.calloc(stack).sType$Default().buffer(buffer.handle()).format(format).offset(slice.offset()).range(slice.length()),null,pointer),"overhaul texel-buffer view");
                            view=pointer.get(0);bufferViews.put(viewKey,view);
                        }
                        write.pTexelBufferView(stack.longs(view));
                    }
                }
            }
            vkUpdateDescriptorSets(resources.context().device(),writes,null);sets.put(key,set);return set;
        }
    }
    static void checkRange(GpuBufferSlice slice,long size){if(slice.offset()<0||slice.length()<1||slice.offset()>size||slice.length()>size-slice.offset())throw new IllegalArgumentException("Buffer slice outside native allocation");}
    private Pool pool(OverhaulPipeline pipeline,MemoryStack stack){
        List<Pool> available=pools.computeIfAbsent(pipeline,ignored->new ArrayList<>());
        if(!available.isEmpty()&&available.getLast().remaining>0)return available.getLast();
        EnumMap<UniformType,Integer> counts=new EnumMap<>(UniformType.class);
        for(var uniform:pipeline.uniforms())counts.merge(uniform.type(),SETS_PER_POOL,Integer::sum);
        VkDescriptorPoolSize.Buffer sizes=VkDescriptorPoolSize.calloc(counts.size(),stack);int index=0;
        for(var count:counts.entrySet())sizes.get(index++).type(OverhaulPipelineState.descriptor(count.getKey())).descriptorCount(count.getValue());
        LongBuffer pointer=stack.mallocLong(1);
        check(vkCreateDescriptorPool(resources.context().device(),VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(SETS_PER_POOL).pPoolSizes(sizes),null,pointer),"overhaul descriptor arena");
        Pool result=new Pool(pointer.get(0));available.add(result);return result;
    }
    @Override public void close(){
        for(var entries:pools.values())for(Pool pool:entries)vkDestroyDescriptorPool(resources.context().device(),pool.handle,null);
        for(long view:bufferViews.values())vkDestroyBufferView(resources.context().device(),view,null);
        pools.clear();sets.clear();bufferViews.clear();
    }
}
