package dev.overhaul.minecraft.packet;

import dev.overhaul.core.memory.DirectSlabPool;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.buffer.UnpooledDirectByteBuf;
import java.lang.ref.Cleaner;
import java.lang.ref.Reference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.Predicate;

/** Packet/reader reachability owns section bytes. Public readers materialize exactly one ordinary heap array. */
public final class OwnedChunkPayload {
    private static final class Retirement implements Runnable {
        private final DirectSlabPool.Lease lease;
        Retirement(DirectSlabPool.Lease lease){this.lease=lease;}
        @Override public void run(){lease.close();}
    }
    private final Retirement retirement;
    private final Cleaner.Cleanable cleanable;
    private final int length;
    private volatile ByteBuf backing;
    private volatile byte[] compatibility;

    OwnedChunkPayload(Cleaner cleaner,DirectSlabPool.Lease lease,int length){
        this.length=length;retirement=new Retirement(lease);
        backing=new NativeStorage(storage(),length);cleanable=cleaner.register(this,retirement);
    }
    public int length(){return length;}
    private ByteBuffer storage(){return retirement.lease.buffer().order(ByteOrder.BIG_ENDIAN);}
    /** Decoder-only initialization before the payload or any reader is published. The view must not escape. */
    boolean initialize(Predicate<ByteBuffer> writer){
        try{
            ByteBuffer output=storage();boolean accepted=writer.test(output);
            if(accepted&&(output.position()!=length||output.limit()!=length))throw new IllegalStateException("Payload initializer did not fill its complete byte range");
            return accepted;
        }finally{Reference.reachabilityFence(this);}
    }
    ByteBuf backing(){return backing;}
    boolean hasCompatibility(){return compatibility!=null;}
    ByteBuf heapBacking(){compatibility();return backing;}
    byte[] compatibility(){
        byte[] current=compatibility;if(current!=null)return current;
        synchronized(this){
            current=compatibility;
            if(current==null){
                try{
                    current=new byte[length];backing.getBytes(0,current);
                    backing=Unpooled.wrappedBuffer(current);compatibility=current;
                }
                finally{Reference.reachabilityFence(this);}
            }
        }
        return current;
    }
    public ByteBuf publicReader(){return Unpooled.wrappedBuffer(compatibility());}
    private ByteBuf reader(){
        return new CanonicalChunkByteBuf(this).asReadOnly();
    }
    /** Only the package-private construction builder can obtain a writable view. */
    ByteBuf constructionWriter(){return new CanonicalChunkByteBuf(this).setIndex(0,0);}
    public Borrow borrow(){return new Borrow(this);}
    public void write(ByteBuf output){
        try{
            output.ensureWritable(length);int index=output.writerIndex();
            CanonicalChunkByteBuf.copyTo(this,0,output,index,length);output.writerIndex(index+length);
        }finally{Reference.reachabilityFence(this);}
    }
    /** Only a failed decode which never published its packet may retire synchronously. */
    void discardUnpublished(){cleanable.clean();}

    public static final class Borrow implements AutoCloseable {
        private final OwnedChunkPayload payload;
        private ByteBuf buffer;
        private Borrow(OwnedChunkPayload payload){this.payload=payload;}
        public ByteBuf buffer(){if(buffer==null)buffer=payload.reader();return buffer;}
        @Override public void close(){Reference.reachabilityFence(payload);}
    }
    /** Private delegate only: no consumer can bypass the canonical leaf through unwrap(). */
    private static final class NativeStorage extends UnpooledDirectByteBuf {
        NativeStorage(ByteBuffer storage,int length){super(UnpooledByteBufAllocator.DEFAULT,storage,length);}
        @Override public ByteBuf capacity(int capacity){
            if(capacity!=capacity())throw new UnsupportedOperationException("Cannot resize borrowed chunk bytes");return this;
        }
    }
}
