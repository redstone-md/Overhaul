package dev.overhaul.minecraft.backend;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import java.nio.ByteBuffer;
import static org.lwjgl.vulkan.VK10.*;

/** RenderPearl metadata and mapping leases over an owned Overhaul VMA allocation. */
public final class OverhaulBuffer extends BackendResource implements GpuBuffer {
    private final int usage;
    private final long size;
    private dev.overhaul.vulkan.GpuBuffer storage;

    OverhaulBuffer(OverhaulDeviceBackend owner,int usage,long size,ByteBuffer initial) {
        super(owner);
        if (size<0 || size>Integer.MAX_VALUE) { close(); throw new IllegalArgumentException("Buffer size exceeds the backend allocation limit"); }
        this.usage=usage; this.size=size;
        try {
            int nativeUsage=VK_BUFFER_USAGE_TRANSFER_SRC_BIT|VK_BUFFER_USAGE_TRANSFER_DST_BIT;
            if ((usage&USAGE_VERTEX)!=0) nativeUsage|=VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;
            if ((usage&USAGE_INDEX)!=0) nativeUsage|=VK_BUFFER_USAGE_INDEX_BUFFER_BIT;
            if ((usage&USAGE_UNIFORM)!=0) nativeUsage|=VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT;
            if ((usage&USAGE_UNIFORM_TEXEL_BUFFER)!=0) nativeUsage|=VK_BUFFER_USAGE_UNIFORM_TEXEL_BUFFER_BIT;
            if ((usage&USAGE_INDIRECT_PARAMETERS)!=0) nativeUsage|=VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT;
            storage=owner.allocator().hostBuffer(Math.max(1,size),nativeUsage);
            if (initial!=null) storage.write(initial);
        } catch (RuntimeException|Error failure) { close(); throw failure; }
    }

    @Override public long size() { return size; }
    @Override public int usage() { return usage; }
    public dev.overhaul.vulkan.GpuBuffer nativeBuffer() { requireNativeAlive(); return storage; }

    @Override public GpuBufferSlice.MappedView map(long offset,long length,boolean read,boolean write) {
        requireOpen();
        if (offset<0 || length<=0 || length>size || offset>size-length || (!read&&!write)) {
            throw new IllegalArgumentException("Invalid buffer mapping range or access");
        }
        if ((read&&(usage&USAGE_MAP_READ)==0) || (write&&(usage&USAGE_MAP_WRITE)==0)) {
            throw new IllegalArgumentException("Buffer mapping access was not requested at creation");
        }
        owner().queue().awaitBuffer(this,offset,length);
        AutoCloseable pin=pin();
        try {
            var mapping=storage.mapRange(Math.toIntExact(offset),Math.toIntExact(length),read,write);
            return new GpuBufferSlice.MappedView(new GpuBufferSlice(this,offset,length),mapping.buffer(),()-> {
                try { mapping.close(); }
                finally { release(pin); }
            });
        } catch (RuntimeException|Error failure) { release(pin); throw failure; }
    }

    private static void release(AutoCloseable pin) {
        try { pin.close(); }
        catch (RuntimeException error) { throw error; }
        catch (Exception error) { throw new IllegalStateException("Cannot release buffer mapping",error); }
    }

    @Override protected void destroyNative() { if (storage!=null) storage.close(); }
}
