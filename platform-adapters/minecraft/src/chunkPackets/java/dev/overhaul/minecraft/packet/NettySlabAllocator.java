package dev.overhaul.minecraft.packet;

import dev.overhaul.core.memory.DirectBufferAllocator;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import java.nio.ByteBuffer;

/** One direct Netty allocation backs one slab pool; no channel or connection is retained. */
final class NettySlabAllocator implements DirectBufferAllocator {
    private final ByteBufAllocator allocator;
    private ByteBuf allocation;
    private ByteBuffer view;
    NettySlabAllocator(ByteBufAllocator allocator){this.allocator=allocator;}
    @Override public ByteBuffer allocate(int bytes){
        if(allocation!=null)throw new IllegalStateException("Slab backing is already allocated");
        ByteBuf created=allocator.directBuffer(bytes,bytes);
        try{view=created.nioBuffer(0,bytes).slice();allocation=created;return view;}
        catch(RuntimeException|Error failure){try{created.release();}catch(RuntimeException|Error cleanup){failure.addSuppressed(cleanup);}throw failure;}
    }
    @Override public void release(ByteBuffer storage){
        if(storage!=view||allocation==null)throw new IllegalArgumentException("Foreign slab backing");
        ByteBuf released=allocation;allocation=null;view=null;released.release();
    }
}
