package dev.overhaul.minecraft.backend;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import java.nio.ByteBuffer;

/**
 * Staging for buffer writes, apart from the frontend's transient arenas. Only queued copies read these slices,
 * so a burst of chunk uploads can submit them, wait, and rewind in the middle of a frame instead of running out.
 */
final class OverhaulUploadStaging implements AutoCloseable {
    private static final int CAPACITY=32*1024*1024;
    private final OverhaulDeviceBackend owner;
    private GpuBuffer buffer;
    private int capacity,cursor;

    OverhaulUploadStaging(OverhaulDeviceBackend owner){this.owner=owner;}

    GpuBufferSlice upload(ByteBuffer source){
        int bytes=source.remaining();long alignment=Math.max(4,owner.queue().mappingAlignment());
        int offset=Math.toIntExact((cursor+alignment-1)&-alignment);
        if(buffer==null||offset>capacity-bytes){
            retire();offset=0;
            if(buffer==null||bytes>capacity){
                if(buffer!=null)buffer.close();
                capacity=Math.max(CAPACITY,bytes);
                buffer=owner.createBuffer(()->"overhaul upload staging",GpuBuffer.USAGE_MAP_WRITE|GpuBuffer.USAGE_COPY_SRC,capacity);
            }
        }
        cursor=offset+bytes;
        GpuBufferSlice slice=buffer.slice(offset,bytes);
        try(GpuBufferSlice.MappedView view=slice.map(false,true)){view.data().put(source.duplicate());}
        return slice;
    }
    private void retire(){
        if(cursor>0)owner.queue().await(owner.queue().flush(),-1L);
        cursor=0;
    }
    @Override public void close(){
        if(buffer!=null){buffer.close();buffer=null;}
    }
}
