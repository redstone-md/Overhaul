package dev.overhaul.minecraft.backend;

import dev.overhaul.vulkan.GpuBuffer;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT;

/** Uniform buffers return only after their render pass retires, so a queued draw keeps its bytes unchanged. */
final class CascadeUniformPool implements AutoCloseable {
    private final OverhaulDeviceBackend owner;
    private final Map<Integer,ArrayDeque<GpuBuffer>> free=new HashMap<>();
    CascadeUniformPool(OverhaulDeviceBackend owner){this.owner=owner;}
    GpuBuffer acquire(int size){
        var available=free.get(size);GpuBuffer reused=available==null?null:available.poll();
        return reused!=null?reused:owner.allocator().hostBuffer(size,VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
    }
    void release(GpuBuffer buffer){free.computeIfAbsent(buffer.size(),ignored->new ArrayDeque<>()).add(buffer);}
    @Override public void close(){for(var buffers:free.values())for(var buffer:buffers)buffer.close();free.clear();}
}
