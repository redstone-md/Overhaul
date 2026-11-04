package dev.overhaul.minecraft.packet;

import dev.overhaul.core.memory.DirectSlabPool;
import io.netty.buffer.ByteBufAllocator;
import java.lang.ref.Cleaner;

/** One connection's bounded admission. Existing payloads survive connection closure until unreachable. */
public final class ChunkPacketMemory implements AutoCloseable {
    public static final int MIN_FRAME_BYTES=512;
    public static final int MIN_PAYLOAD_BYTES=4096;
    private static final int SLAB_BYTES=256*1024;
    private static final int SLAB_COUNT=8;
    private DirectSlabPool pool;
    private Cleaner cleaner;
    private boolean closed;

    public static boolean eligiblePayloadSize(int bytes){return bytes>=MIN_PAYLOAD_BYTES&&bytes<=SLAB_BYTES;}
    OwnedChunkPayload tryAllocate(int bytes,ByteBufAllocator allocator){
        if(!eligiblePayloadSize(bytes))return null;
        DirectSlabPool selected=pool(allocator);if(selected==null)return null;
        DirectSlabPool.Lease lease;
        try{lease=selected.acquire(bytes);}catch(IllegalStateException unavailable){return null;}
        try{
            return new OwnedChunkPayload(cleaner,lease,bytes);
        }catch(RuntimeException|Error failure){try{lease.close();}catch(RuntimeException|Error cleanup){failure.addSuppressed(cleanup);}throw failure;}
    }
    private synchronized DirectSlabPool pool(ByteBufAllocator allocator){
        if(closed)return null;
        if(pool==null){
            DirectSlabPool allocated=new DirectSlabPool(SLAB_BYTES,SLAB_COUNT,new NettySlabAllocator(allocator));
            try{cleaner=Cleaner.create();pool=allocated;}
            catch(RuntimeException|Error failure){try{allocated.close();}catch(RuntimeException|Error cleanup){failure.addSuppressed(cleanup);}throw failure;}
        }
        return pool;
    }
    @Override public synchronized void close(){
        if(closed)return;closed=true;
        if(pool!=null){pool.close();pool=null;}
    }
}
