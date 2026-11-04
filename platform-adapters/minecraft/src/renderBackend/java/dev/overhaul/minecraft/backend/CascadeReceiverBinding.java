package dev.overhaul.minecraft.backend;

import dev.overhaul.minecraft.shader.CascadeShadowFrame;
import dev.overhaul.vulkan.FrameRing;
import dev.overhaul.vulkan.GpuBuffer;
import dev.overhaul.vulkan.GpuImageView;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import static dev.overhaul.vulkan.VulkanException.check;
import static org.lwjgl.vulkan.VK10.*;

/** One main pass's immutable receiver data: this frame's cascades, whenever the pass is recorded. */
final class CascadeReceiverBinding implements AutoCloseable {
    private static final DescriptorPoolCache.Shape SHAPE=new DescriptorPoolCache.Shape(1,1,0,0,1);
    private final OverhaulDeviceBackend owner;
    private final CascadeShadowGeneration source;
    private final CascadeShadowFrame frame;
    private GpuBuffer uniforms;
    private DescriptorPoolCache.Lease pool;
    private long set;

    CascadeReceiverBinding(OverhaulDeviceBackend owner,CascadeShadowGeneration source){this.owner=owner;this.source=source;frame=source.frame();}
    boolean accepts(PassBindings state){return CascadeTerrainPipeline.accepts(state.pipeline());}
    /** Compiles and uploads while the draw is captured, so a failure never lands inside a native render pass. */
    GpuImageView prepare(PassBindings state){
        source.terrain().pipeline(state.pipeline());
        if(uniforms==null){
            uniforms=source.uniforms().acquire(CascadeShadowFrame.RECEIVER_BYTES);
            ByteBuffer data=ByteBuffer.allocateDirect(CascadeShadowFrame.RECEIVER_BYTES).order(ByteOrder.nativeOrder());
            frame.writeReceiver(data);uniforms.write(data);
        }
        return source.sampledDepth();
    }
    void bind(VkCommandBuffer command,PassBindings state,PassAttachments attachments,PassDescriptors descriptors){
        source.terrain().pipeline(state.pipeline()).bind(command,state,attachments,descriptors,set());
    }
    private long set(){
        if(set!=0)return set;
        if(uniforms==null)throw new IllegalStateException("Receiver draw recorded without its captured cascades");
        try(MemoryStack stack=MemoryStack.stackPush()){
            pool=owner.descriptorPools().acquire(SHAPE);LongBuffer pointer=stack.mallocLong(1);
            check(vkAllocateDescriptorSets(owner.context().device(),VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(pool.handle()).pSetLayouts(stack.longs(source.terrain().receiverLayout())),pointer),"cascade receiver descriptor allocation");
            pool.allocated();long allocated=pointer.get(0);
            VkWriteDescriptorSet.Buffer writes=VkWriteDescriptorSet.calloc(2,stack);
            writes.get(0).sType$Default().dstSet(allocated).dstBinding(0).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                    .pBufferInfo(VkDescriptorBufferInfo.calloc(1,stack).buffer(uniforms.handle()).range(uniforms.size()));
            writes.get(1).sType$Default().dstSet(allocated).dstBinding(1).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .pImageInfo(VkDescriptorImageInfo.calloc(1,stack).imageView(source.sampledDepth().handle()).sampler(source.depthSampler()).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL));
            vkUpdateDescriptorSets(owner.context().device(),writes,null);
            set=allocated;return set;
        }
    }
    void submitted(FrameRing.Submission completion){if(pool!=null)pool.submitted(completion);}
    @Override public void close(){
        try{if(pool!=null){pool.close();pool=null;}}
        finally{if(uniforms!=null){source.uniforms().release(uniforms);uniforms=null;}set=0;}
    }
}
