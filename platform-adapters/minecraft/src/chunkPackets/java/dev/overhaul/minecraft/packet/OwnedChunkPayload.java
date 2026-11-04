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
    private volatile byte[] compatibility;

    OwnedChunkPayload(Cleaner cleaner,DirectSlabPool.Lease lease,int length){
        this.length=length;retirement=new Retirement(lease);cleanable=cleaner.register(this,retirement);
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
    private byte[] compatibility(){
        byte[] current=compatibility;if(current!=null)return current;
        synchronized(this){
            current=compatibility;
            if(current==null){
                try{current=new byte[length];storage().get(current);compatibility=current;}
                finally{Reference.reachabilityFence(this);}
            }
        }
        return current;
    }
    public ByteBuf publicReader(){return Unpooled.wrappedBuffer(compatibility());}
    private ByteBuf reader(){
        byte[] current=compatibility;
        return current==null?new NativeReader(this).asReadOnly():Unpooled.wrappedBuffer(current);
    }
    public Borrow borrow(){return new Borrow(this);}
    public void write(ByteBuf output){
        try{
            byte[] current=compatibility;
            if(current!=null)output.writeBytes(current);else output.writeBytes(storage().asReadOnlyBuffer().order(ByteOrder.BIG_ENDIAN));
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
    /** External-buffer constructor does not free slab memory; derived views retain this leaf and its payload. */
    private static final class NativeReader extends UnpooledDirectByteBuf {
        private final OwnedChunkPayload owner;
        NativeReader(OwnedChunkPayload owner){super(UnpooledByteBufAllocator.DEFAULT,owner.storage(),owner.length);this.owner=owner;}
        @Override public ByteBuf capacity(int capacity){
            if(capacity!=capacity())throw new UnsupportedOperationException("Cannot resize borrowed chunk bytes");return this;
        }
    }
}
