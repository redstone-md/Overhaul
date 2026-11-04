package dev.overhaul.minecraft.blaze;

import java.nio.ByteBuffer;

/**
 * Uniform values of consecutive draws side by side in one host chunk, so a descriptor set written once serves every
 * draw through a dynamic offset. Passes pin the chunk they read until their fence; owner-thread only.
 */
final class BlazeUniformArena implements AutoCloseable {
    private static final int CHUNK_BYTES=1024*1024;
    private final BlazeBufferPool pool;
    private final int alignment;
    private BlazeBufferPool.Slice chunk;
    private int position;

    BlazeUniformArena(BlazeBufferPool pool,int alignment){this.pool=pool;this.alignment=alignment;}

    /** The chunk the next write of this size lands in; a full chunk is left to the passes that pinned it. */
    BlazeBufferPool.Slice reserve(int bytes){
        if(chunk==null||position+bytes>chunk.size()){
            BlazeBufferPool.Slice next=pool.acquire(Math.max(CHUNK_BYTES,bytes));
            if(chunk!=null)chunk.close();
            chunk=next;position=0;
        }
        return chunk;
    }
    /** Writes into the reserved chunk and returns the dynamic offset, relative to the chunk start. */
    int write(ByteBuffer values,int reserved){
        int offset=position;
        chunk.buffer().write(chunk.offset()+offset,values);
        position=Math.toIntExact(((long)offset+reserved+alignment-1)/alignment*alignment);
        return offset;
    }
    @Override public void close(){if(chunk!=null){chunk.close();chunk=null;}}
}
