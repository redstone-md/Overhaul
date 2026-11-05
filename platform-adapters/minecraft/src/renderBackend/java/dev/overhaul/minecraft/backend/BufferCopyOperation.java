package dev.overhaul.minecraft.backend;

import dev.overhaul.vulkan.VulkanStack;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import static org.lwjgl.vulkan.VK10.*;

/** GPU-ordered copy; a later upload cannot overwrite bytes an earlier draw still needs. */
final class BufferCopyOperation implements OverhaulGpuOperation {
    private final OverhaulDeviceBackend owner;
    private final OverhaulBuffer source,target;
    private final BackendResource sourceResource,targetResource;
    private final long sourceOffset,targetOffset,length;
    private final BackendPins pins;
    private long stagingBytes;

    /** Snapshots constructor input now; the queue owns its private staging allocation thereafter. */
    static BufferCopyOperation initialUpload(OverhaulDeviceBackend owner,OverhaulBuffer target,java.nio.ByteBuffer data) {
        try (var staging=new OverhaulBuffer(owner,
                com.mojang.renderpearl.api.buffers.GpuBuffer.USAGE_MAP_WRITE|com.mojang.renderpearl.api.buffers.GpuBuffer.USAGE_COPY_SRC,
                data.remaining(),data)) {
            var operation=new BufferCopyOperation(owner,staging.slice(),target.slice(0,data.remaining()));
            operation.stagingBytes=data.remaining();
            return operation;
        }
    }

    BufferCopyOperation(OverhaulDeviceBackend owner,GpuBufferSlice source,GpuBufferSlice target) {
        this.owner=owner;
        if (!(source.buffer() instanceof OverhaulBuffer src)||src.owner()!=owner
                ||!(target.buffer() instanceof OverhaulBuffer dst)||dst.owner()!=owner) throw new IllegalArgumentException("Foreign copy buffer");
        this.source=src; this.target=dst; sourceOffset=source.offset(); targetOffset=target.offset(); length=source.length();
        if (length<1||length>target.length()||sourceOffset<0||sourceOffset>src.size()-length
                ||targetOffset<0||targetOffset>dst.size()-length) throw new IllegalArgumentException("Buffer copy range exceeds allocation");
        if (src==dst&&overlap(sourceOffset,length,targetOffset,length)) throw new IllegalArgumentException("Buffer copy ranges overlap");
        sourceResource=owner.backendResource(src); targetResource=owner.backendResource(dst);
        pins=new BackendPins(owner,sourceResource,targetResource);
    }
    @Override public void record(VkCommandBuffer command) {
        try (MemoryStack stack=VulkanStack.push()) {
            var before=VkMemoryBarrier.calloc(1,stack).sType$Default()
                    .srcAccessMask(VK_ACCESS_MEMORY_READ_BIT|VK_ACCESS_MEMORY_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT|VK_ACCESS_TRANSFER_WRITE_BIT);
            vkCmdPipelineBarrier(command,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,0,before,null,null);
            vkCmdCopyBuffer(command,source.nativeBuffer().handle(),target.nativeBuffer().handle(),
                    VkBufferCopy.calloc(1,stack).srcOffset(sourceOffset).dstOffset(targetOffset).size(length));
            var after=VkMemoryBarrier.calloc(1,stack).sType$Default().srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_MEMORY_READ_BIT|VK_ACCESS_MEMORY_WRITE_BIT|VK_ACCESS_HOST_READ_BIT);
            vkCmdPipelineBarrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_ALL_COMMANDS_BIT|VK_PIPELINE_STAGE_HOST_BIT,0,after,null,null);
        }
    }
    @Override public boolean usesBuffer(OverhaulBuffer buffer,long offset,long size) {
        return buffer==source&&overlap(sourceOffset,length,offset,size)||buffer==target&&overlap(targetOffset,length,offset,size);
    }
    @Override public boolean usesResource(BackendResource resource) { return resource==sourceResource||resource==targetResource; }
    @Override public long stagingBytes() { return stagingBytes; }
    static boolean overlap(long first,long length,long second,long size) { return length>0&&size>0&&first<second+size&&second<first+length; }
    @Override public void close() { pins.close(); }
}
