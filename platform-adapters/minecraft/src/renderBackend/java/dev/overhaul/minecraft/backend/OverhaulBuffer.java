package dev.overhaul.minecraft.backend;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.backend.common.BaseGpuBuffer;
import java.nio.ByteBuffer;
import static org.lwjgl.vulkan.VK10.*;

/** RenderPearl metadata and mapping leases over an owned Overhaul VMA allocation. */
public final class OverhaulBuffer extends BaseGpuBuffer {
    private final BufferResource resource;

    OverhaulBuffer(OverhaulDeviceBackend owner,int usage,long size,ByteBuffer initial) {
        super(usage,checkedSize(size));
        resource=new BufferResource(owner,usage,size);
        try {
            if (initial!=null&&initial.hasRemaining()) {
                if (resource.hostAccessible) resource.storage.write(initial);
                else owner.queue().enqueue(BufferCopyOperation.initialUpload(owner,this,initial));
            }
        } catch (RuntimeException|Error failure) {
            try { close(); } catch (RuntimeException|Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private static long checkedSize(long size) {
        if (size<0||size>Integer.MAX_VALUE) throw new IllegalArgumentException("Buffer size exceeds the backend allocation limit");
        return size;
    }
    BackendResource resource() { return resource; }
    public OverhaulDeviceBackend owner() { return resource.owner(); }
    public AutoCloseable pin() { return resource.pin(); }
    @Override public boolean isClosed() { return resource.isClosed(); }
    @Override public void close() { resource.close(); }
    @Override public void checkCanBeUsed() { resource.requireOpen(); }
    public dev.overhaul.vulkan.GpuBuffer nativeBuffer() { resource.requireNativeAlive(); return resource.storage; }

    @Override public GpuBufferSlice.MappedView map(long offset,long length,boolean read,boolean write) {
        checkCanBeUsed();
        long size=size();
        int usage=usage();
        if (offset<0 || length<=0 || length>size || offset>size-length || (!read&&!write)) {
            throw new IllegalArgumentException("Invalid buffer mapping range or access");
        }
        if ((read&&(usage&USAGE_MAP_READ)==0) || (write&&(usage&USAGE_MAP_WRITE)==0)) {
            throw new IllegalArgumentException("Buffer mapping access was not requested at creation");
        }
        owner().queue().awaitBuffer(this,offset,length);
        AutoCloseable pin=pin();
        try {
            var mapping=resource.storage.mapRange(Math.toIntExact(offset),Math.toIntExact(length),read,write);
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

    private static final class BufferResource extends BackendResource {
        private final boolean hostAccessible;
        private dev.overhaul.vulkan.GpuBuffer storage;

        private BufferResource(OverhaulDeviceBackend owner,int usage,long size) {
            super(owner);
            hostAccessible=(usage&(USAGE_MAP_READ|USAGE_MAP_WRITE))!=0;
            try {
                int nativeUsage=VK_BUFFER_USAGE_TRANSFER_SRC_BIT|VK_BUFFER_USAGE_TRANSFER_DST_BIT;
                if ((usage&USAGE_VERTEX)!=0) nativeUsage|=VK_BUFFER_USAGE_VERTEX_BUFFER_BIT|VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
                if ((usage&USAGE_INDEX)!=0) nativeUsage|=VK_BUFFER_USAGE_INDEX_BUFFER_BIT;
                if ((usage&USAGE_UNIFORM)!=0) nativeUsage|=VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT;
                if ((usage&USAGE_UNIFORM_TEXEL_BUFFER)!=0) nativeUsage|=VK_BUFFER_USAGE_UNIFORM_TEXEL_BUFFER_BIT;
                if ((usage&USAGE_INDIRECT_PARAMETERS)!=0) nativeUsage|=VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT;
                storage=hostAccessible?owner.allocator().hostBuffer(Math.max(1,size),nativeUsage)
                        :owner.allocator().deviceBuffer(Math.max(1,size),nativeUsage);
                owner.bufferStorage().allocated(hostAccessible,storage.size());
            } catch (RuntimeException|Error failure) {
                try { close(); } catch (RuntimeException|Error cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }

        @Override protected void destroyNative() {
            if (storage!=null) {
                storage.close();
                owner().bufferStorage().released(hostAccessible,storage.size());
                storage=null;
            }
        }
    }
}
