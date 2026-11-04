package dev.overhaul.minecraft.backend;

import dev.overhaul.vulkan.FrameRing;
import dev.overhaul.vulkan.ImageCommand;
import org.lwjgl.vulkan.VkCommandBuffer;
import java.util.ArrayList;
import java.util.List;

/** Image commands, endpoint pins and completion callbacks share one fence lifetime. */
final class OverhaulImageOperation implements OverhaulGpuOperation {
    private record BufferRange(OverhaulBuffer buffer,long offset,long length) {
        boolean overlaps(OverhaulBuffer other,long start,long size) {
            return buffer==other&&length>0&&size>0&&offset<start+size&&start<offset+length;
        }
    }
    private final OverhaulDeviceBackend owner;
    private final BackendResource[] resources;
    private final BackendPins pins;
    private final Runnable callback;
    private final List<ImageCommand> commands=new ArrayList<>();
    private final List<BufferRange> ranges=new ArrayList<>();
    private boolean submitted,closed;
    private long stagingBytes;

    OverhaulImageOperation(OverhaulDeviceBackend owner,Runnable callback,Object... resources) {
        this.owner=owner; this.callback=callback; this.resources=new BackendResource[resources.length];
        for (int index=0;index<resources.length;index++) {
            this.resources[index]=resources[index]==null?null:owner.backendResource(resources[index]);
        }
        pins=new BackendPins(owner,(Object[])this.resources);
    }
    void add(ImageCommand command) {
        commands.add(command); stagingBytes=Math.addExact(stagingBytes,command.stagingBytes());
    }
    void touches(com.mojang.renderpearl.api.buffers.GpuBuffer buffer,long offset,long length) {
        if (!(buffer instanceof OverhaulBuffer own)||own.owner()!=owner) throw new IllegalArgumentException("Foreign buffer");
        if (offset<0||length<0||offset>buffer.size()-length) throw new IllegalArgumentException("Invalid image transfer buffer range");
        ranges.add(new BufferRange(own,offset,length));
    }
    @Override public void record(VkCommandBuffer command) { for (var image:commands) image.record(command); }
    @Override public void submitted(FrameRing.Submission submission) {
        for (var image:commands) image.complete();
        submitted=true;
    }
    @Override public long stagingBytes() { return stagingBytes; }
    @Override public boolean usesBuffer(OverhaulBuffer buffer,long offset,long length) {
        return ranges.stream().anyMatch(range->range.overlaps(buffer,offset,length));
    }
    @Override public boolean usesResource(BackendResource resource) {
        for (BackendResource endpoint:resources) if (resource==endpoint) return true;
        return false;
    }
    @Override public void close() {
        if (closed) return;
        closed=true;
        RuntimeException failure=null;
        for (int index=commands.size()-1;index>=0;index--) {
            try { commands.get(index).close(); }
            catch (RuntimeException error) { if (failure==null) failure=error; else failure.addSuppressed(error); }
        }
        try { pins.close(); }
        catch (RuntimeException error) { if (failure==null) failure=error; else failure.addSuppressed(error); }
        if (failure!=null) throw failure;
        // FrameRing owns retirement here. Callbacks may map or enqueue, so run them later.
        if (submitted&&callback!=null) owner.queue().afterCompletion(callback);
    }
}
