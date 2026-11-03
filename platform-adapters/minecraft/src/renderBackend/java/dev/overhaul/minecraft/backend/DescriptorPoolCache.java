package dev.overhaul.minecraft.backend;

import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import dev.overhaul.vulkan.FrameRing;
import dev.overhaul.vulkan.VulkanContext;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import java.nio.LongBuffer;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.lwjgl.vulkan.VK10.*;
import static dev.overhaul.vulkan.VulkanException.check;

/** Device-owned idle pools contain capacity metadata only, never pipelines or bound resources. */
final class DescriptorPoolCache implements AutoCloseable {
    record Shape(int uniformBuffers,int combinedSamplers,int texelBuffers,int storageBuffers,int maxSets){
        Shape(int uniformBuffers,int combinedSamplers,int texelBuffers,int maxSets){this(uniformBuffers,combinedSamplers,texelBuffers,0,maxSets);}
        Shape{
            if(uniformBuffers<0||combinedSamplers<0||texelBuffers<0||storageBuffers<0||maxSets<1)throw new IllegalArgumentException("Invalid descriptor capacity");
            Math.multiplyExact(uniformBuffers,maxSets);Math.multiplyExact(combinedSamplers,maxSets);Math.multiplyExact(texelBuffers,maxSets);Math.multiplyExact(storageBuffers,maxSets);
        }
        static Shape of(List<BindGroupLayout.UniformDescription> uniforms,int sets){
            int buffers=0,samplers=0,texels=0;
            for(var uniform:uniforms)switch(uniform.type()){case UNIFORM_BUFFER->buffers++;case COMBINED_IMAGE_SAMPLER->samplers++;case TEXEL_BUFFER->texels++;}
            return new Shape(buffers,samplers,texels,sets);
        }
        long weight(){return((long)uniformBuffers+combinedSamplers+texelBuffers+storageBuffers)*maxSets;}
    }
    record Stats(long createdPools,long reusedPools,long resetPools,long destroyedPools,long evictedPools,
                 int idlePools,int activePools,long idleDescriptors){}
    private static final class Pool{final Shape shape;long handle;Pool(Shape shape,long handle){this.shape=shape;this.handle=handle;}}
    private final VulkanContext context;
    private final int maxIdlePools;
    private final long maxIdleDescriptors;
    private final Map<Shape,ArrayDeque<Pool>> idle=new HashMap<>();
    private final LinkedHashSet<Pool> idleOrder=new LinkedHashSet<>();
    private final Set<Lease> active=Collections.newSetFromMap(new IdentityHashMap<>());
    private long idleDescriptors,createdPools,reusedPools,resetPools,destroyedPools,evictedPools;
    private volatile boolean closed;

    DescriptorPoolCache(VulkanContext context){this(context,16,8192);}
    DescriptorPoolCache(VulkanContext context,int maxIdlePools,long maxIdleDescriptors){
        if(maxIdlePools<0||maxIdleDescriptors<0)throw new IllegalArgumentException("Negative descriptor cache budget");
        this.context=java.util.Objects.requireNonNull(context);this.maxIdlePools=maxIdlePools;this.maxIdleDescriptors=maxIdleDescriptors;
    }
    Lease acquire(Shape shape){
        requireOpen();java.util.Objects.requireNonNull(shape);ArrayDeque<Pool> available=idle.get(shape);Pool pool;
        if(available!=null){pool=available.removeFirst();if(available.isEmpty())idle.remove(shape);idleOrder.remove(pool);idleDescriptors-=shape.weight();reusedPools++;}
        else pool=create(shape);
        try{Lease lease=new Lease(pool);active.add(lease);return lease;}
        catch(RuntimeException|Error failure){destroy(pool);throw failure;}
    }
    private Pool create(Shape shape){
        try(MemoryStack stack=MemoryStack.stackPush()){
            int count=(shape.uniformBuffers()>0?1:0)+(shape.combinedSamplers()>0?1:0)+(shape.texelBuffers()>0?1:0)+(shape.storageBuffers()>0?1:0);
            VkDescriptorPoolSize.Buffer sizes=VkDescriptorPoolSize.calloc(count,stack);int index=0;
            if(shape.uniformBuffers()>0)sizes.get(index++).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(shape.uniformBuffers()*shape.maxSets());
            if(shape.combinedSamplers()>0)sizes.get(index++).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(shape.combinedSamplers()*shape.maxSets());
            if(shape.texelBuffers()>0)sizes.get(index++).type(VK_DESCRIPTOR_TYPE_UNIFORM_TEXEL_BUFFER).descriptorCount(shape.texelBuffers()*shape.maxSets());
            if(shape.storageBuffers()>0)sizes.get(index).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(shape.storageBuffers()*shape.maxSets());
            LongBuffer pointer=stack.mallocLong(1);
            check(vkCreateDescriptorPool(context.device(),VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(shape.maxSets()).pPoolSizes(sizes),null,pointer),"overhaul descriptor pool");
            createdPools++;return new Pool(shape,pointer.get(0));
        }
    }
    private void recycle(Lease lease){
        requireOpen();if(!active.remove(lease))throw new IllegalStateException("Descriptor lease is not active");
        Pool pool=lease.pool;lease.returned=true;lease.submission=null;
        if(maxIdlePools==0||pool.shape.weight()>maxIdleDescriptors){destroy(pool);return;}
        try{check(vkResetDescriptorPool(context.device(),pool.handle,0),"overhaul descriptor pool reset");resetPools++;}
        catch(RuntimeException|Error failure){destroy(pool);throw failure;}
        idle.computeIfAbsent(pool.shape,ignored->new ArrayDeque<>()).addLast(pool);idleOrder.add(pool);idleDescriptors+=pool.shape.weight();
        while(idleOrder.size()>maxIdlePools||idleDescriptors>maxIdleDescriptors){
            Pool oldest=idleOrder.iterator().next();idleOrder.remove(oldest);ArrayDeque<Pool> shapePools=idle.get(oldest.shape);shapePools.remove(oldest);
            if(shapePools.isEmpty())idle.remove(oldest.shape);idleDescriptors-=oldest.shape.weight();evictedPools++;destroy(oldest);
        }
    }
    private void destroy(Pool pool){if(pool.handle!=0){vkDestroyDescriptorPool(context.device(),pool.handle,null);pool.handle=0;destroyedPools++;}}
    private void requireOpen(){context.device();if(closed)throw new IllegalStateException("Descriptor pool cache is closed");}
    Stats stats(){if(!closed)context.device();return new Stats(createdPools,reusedPools,resetPools,destroyedPools,evictedPools,idleOrder.size(),active.size(),idleDescriptors);}
    @Override public void close(){
        if(closed)return;requireOpen();if(!active.isEmpty())throw new IllegalStateException("Descriptor pools still have active leases");
        for(Pool pool:idleOrder)destroy(pool);idleOrder.clear();idle.clear();idleDescriptors=0;closed=true;
    }

    /** One pass exclusively owns a lease until its accepted submission has completed. */
    final class Lease implements AutoCloseable {
        private final Pool pool;
        private int remaining;
        private FrameRing.Submission submission;
        private boolean returned;
        private Lease(Pool pool){this.pool=pool;remaining=pool.shape.maxSets();}
        long handle(){requireActive();return pool.handle;}
        boolean hasCapacity(){requireActive();return remaining>0;}
        void allocated(){requireActive();if(remaining==0)throw new IllegalStateException("Descriptor pool capacity exhausted");remaining--;}
        void submitted(FrameRing.Submission completion){
            requireActive();java.util.Objects.requireNonNull(completion);
            if(submission!=null&&submission!=completion)throw new IllegalStateException("Descriptor pool lease belongs to a different submission");submission=completion;
        }
        private void requireActive(){requireOpen();if(returned)throw new IllegalStateException("Descriptor pool lease was returned");}
        @Override public void close(){
            if(returned)return;requireActive();
            // await(0) never blocks and is safe during FrameRing retirement; isComplete() would recursively poll.
            if(submission!=null&&!submission.await(0))throw new IllegalStateException("Descriptor pool submission has not completed");
            if(!returned)recycle(this); // Completion polling may already have retired this same lease.
        }
    }
}
