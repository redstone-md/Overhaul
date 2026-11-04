package dev.overhaul.minecraft.packet;

import io.netty.buffer.AbstractReferenceCountedByteBuf;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.UnpooledByteBufAllocator;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.Reference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.GatheringByteChannel;
import java.nio.channels.ScatteringByteChannel;

/** Fixed-capacity leaf: every existing derived view follows the payload's canonical backing. */
final class CanonicalChunkByteBuf extends AbstractReferenceCountedByteBuf {
    private final OwnedChunkPayload owner;

    CanonicalChunkByteBuf(OwnedChunkPayload owner){super(owner.length());this.owner=owner;setIndex(0,owner.length());}
    @Override public int capacity(){return owner.length();}
    @Override public ByteBuf capacity(int capacity){
        ensureAccessible();
        if(capacity!=capacity())throw new UnsupportedOperationException("Cannot resize chunk payload bytes");return this;
    }
    @Override public ByteBufAllocator alloc(){return UnpooledByteBufAllocator.DEFAULT;}
    @Override public ByteOrder order(){return ByteOrder.BIG_ENDIAN;}
    @Override public ByteBuf unwrap(){return null;}
    @Override public boolean isDirect(){return owner.backing().isDirect();}
    @Override public boolean hasArray(){return owner.hasCompatibility();}
    @Override public byte[] array(){ensureAccessible();return owner.compatibility();}
    @Override public int arrayOffset(){ensureAccessible();owner.compatibility();return 0;}
    @Override public boolean hasMemoryAddress(){return false;}
    @Override public long memoryAddress(){throw new UnsupportedOperationException("Chunk payload has no exposed native address");}
    @Override protected void deallocate(){/* Only payload reachability owns its slab lease. */}

    private long read(int index,int width,boolean little){
        try{
            ByteBuf bytes=owner.backing();
            return switch(width){
                case 1->bytes.getByte(index);
                case 2->little?bytes.getShortLE(index):bytes.getShort(index);
                case 3->little?bytes.getUnsignedMediumLE(index):bytes.getUnsignedMedium(index);
                case 4->little?bytes.getIntLE(index):bytes.getInt(index);
                case 8->little?bytes.getLongLE(index):bytes.getLong(index);
                default->throw new AssertionError("Unexpected scalar width");
            };
        }finally{Reference.reachabilityFence(owner);}
    }
    private void write(int index,long value,int width,boolean little){
        try{
            synchronized(owner){
                ByteBuf bytes=owner.backing();
                switch(width){
                    case 1->bytes.setByte(index,(int)value);
                    case 2->{if(little)bytes.setShortLE(index,(int)value);else bytes.setShort(index,(int)value);}
                    case 3->{if(little)bytes.setMediumLE(index,(int)value);else bytes.setMedium(index,(int)value);}
                    case 4->{if(little)bytes.setIntLE(index,(int)value);else bytes.setInt(index,(int)value);}
                    case 8->{if(little)bytes.setLongLE(index,value);else bytes.setLong(index,value);}
                    default->throw new AssertionError("Unexpected scalar width");
                }
            }
        }finally{Reference.reachabilityFence(owner);}
    }
    @Override protected byte _getByte(int index){return (byte)read(index,1,false);}
    @Override protected short _getShort(int index){return (short)read(index,2,false);}
    @Override protected short _getShortLE(int index){return (short)read(index,2,true);}
    @Override protected int _getUnsignedMedium(int index){return (int)read(index,3,false);}
    @Override protected int _getUnsignedMediumLE(int index){return (int)read(index,3,true);}
    @Override protected int _getInt(int index){return (int)read(index,4,false);}
    @Override protected int _getIntLE(int index){return (int)read(index,4,true);}
    @Override protected long _getLong(int index){return read(index,8,false);}
    @Override protected long _getLongLE(int index){return read(index,8,true);}
    @Override protected void _setByte(int index,int value){write(index,value,1,false);}
    @Override protected void _setShort(int index,int value){write(index,value,2,false);}
    @Override protected void _setShortLE(int index,int value){write(index,value,2,true);}
    @Override protected void _setMedium(int index,int value){write(index,value,3,false);}
    @Override protected void _setMediumLE(int index,int value){write(index,value,3,true);}
    @Override protected void _setInt(int index,int value){write(index,value,4,false);}
    @Override protected void _setIntLE(int index,int value){write(index,value,4,true);}
    @Override protected void _setLong(int index,long value){write(index,value,8,false);}
    @Override protected void _setLongLE(int index,long value){write(index,value,8,true);}

    @Override public ByteBuf getBytes(int index,byte[] destination,int destinationIndex,int length){
        checkIndex(index,length);
        try{owner.backing().getBytes(index,destination,destinationIndex,length);return this;}
        finally{Reference.reachabilityFence(owner);}
    }
    @Override public ByteBuf getBytes(int index,ByteBuffer destination){
        checkIndex(index,destination.remaining());
        try{owner.backing().getBytes(index,destination);return this;}
        finally{Reference.reachabilityFence(owner);}
    }
    @Override public ByteBuf getBytes(int index,ByteBuf destination,int destinationIndex,int length){
        checkDstIndex(index,length,destinationIndex,destination.capacity());
        copyTo(owner,index,destination,destinationIndex,length);return this;
    }
    /** Copy into destination-owned storage; a source-native NIO view never crosses this boundary. */
    static void copyTo(OwnedChunkPayload owner,int index,ByteBuf destination,int destinationIndex,int length){
        try{
            if(destination instanceof CanonicalChunkByteBuf canonical){
                canonical.checkIndex(destinationIndex,length);
                try{synchronized(canonical.owner){canonical.owner.backing().setBytes(destinationIndex,owner.backing(),index,length);}}
                finally{Reference.reachabilityFence(canonical.owner);}
            }else if(destination.hasArray()){
                owner.backing().getBytes(index,destination.array(),destination.arrayOffset()+destinationIndex,length);
            }else if(destination.nioBufferCount()==1){
                ByteBuffer output=destination.internalNioBuffer(destinationIndex,length);
                owner.backing().getBytes(index,output);
            }else if(destination.nioBufferCount()>1){
                int offset=index;
                for(ByteBuffer output:destination.nioBuffers(destinationIndex,length)){
                    int bytes=output.remaining();owner.backing().getBytes(offset,output);offset+=bytes;
                }
            }else{
                int offset=0;
                for(;offset+Long.BYTES<=length;offset+=Long.BYTES)destination.setLong(destinationIndex+offset,owner.backing().getLong(index+offset));
                for(;offset<length;offset++)destination.setByte(destinationIndex+offset,owner.backing().getByte(index+offset));
            }
        }finally{Reference.reachabilityFence(owner);}
    }
    @Override public ByteBuf setBytes(int index,byte[] source,int sourceIndex,int length){
        checkIndex(index,length);
        try{synchronized(owner){owner.backing().setBytes(index,source,sourceIndex,length);}return this;}
        finally{Reference.reachabilityFence(owner);}
    }
    @Override public ByteBuf setBytes(int index,ByteBuffer source){
        checkIndex(index,source.remaining());
        try{synchronized(owner){owner.backing().setBytes(index,source);}return this;}
        finally{Reference.reachabilityFence(owner);}
    }
    @Override public ByteBuf setBytes(int index,ByteBuf source,int sourceIndex,int length){
        checkSrcIndex(index,length,sourceIndex,source.capacity());
        try{
            synchronized(owner){
                ByteBuf target=owner.backing();
                if(source==this)target.setBytes(index,target,sourceIndex,length);
                else if(source instanceof CanonicalChunkByteBuf canonical){
                    canonical.ensureAccessible();
                    try{target.setBytes(index,canonical.owner.backing(),sourceIndex,length);}
                    finally{Reference.reachabilityFence(canonical.owner);}
                }else if(aliasesOwner(source)){
                    // Derived slices hide their absolute offset. Canonical heap views give
                    // ByteBuffer's overlap-safe copy the correct shared-array offsets.
                    owner.compatibility();
                    owner.backing().setBytes(index,source.nioBuffer(sourceIndex,length));
                }else if(source.hasArray())target.setBytes(index,source.array(),source.arrayOffset()+sourceIndex,length);
                else{
                    // Arbitrary source implementations receive no alias of our native destination.
                    for(int offset=0;offset<length;offset++)target.setByte(index+offset,source.getByte(sourceIndex+offset));
                }
            }
            return this;
        }finally{Reference.reachabilityFence(owner);}
    }
    private boolean aliasesOwner(ByteBuf source){
        for(ByteBuf cursor=source;cursor!=null;){
            if(cursor instanceof CanonicalChunkByteBuf canonical)return canonical.owner==owner;
            ByteBuf next=cursor.unwrap();if(next==cursor)return false;cursor=next;
        }
        return false;
    }
    @Override public ByteBuf setZero(int index,int length){
        checkIndex(index,length);
        try{synchronized(owner){owner.backing().setZero(index,length);}return this;}
        finally{Reference.reachabilityFence(owner);}
    }

    // These APIs can expose storage to arbitrary IO implementations; materialize before handing it out.
    @Override public ByteBuf getBytes(int index,OutputStream output,int length) throws IOException{
        checkIndex(index,length);owner.heapBacking().getBytes(index,output,length);return this;
    }
    @Override public int getBytes(int index,GatheringByteChannel output,int length) throws IOException{
        checkIndex(index,length);return owner.heapBacking().getBytes(index,output,length);
    }
    @Override public int getBytes(int index,FileChannel output,long position,int length) throws IOException{
        checkIndex(index,length);return owner.heapBacking().getBytes(index,output,position,length);
    }
    @Override public int setBytes(int index,InputStream input,int length) throws IOException{
        checkIndex(index,length);return owner.heapBacking().setBytes(index,input,length);
    }
    @Override public int setBytes(int index,ScatteringByteChannel input,int length) throws IOException{
        checkIndex(index,length);return owner.heapBacking().setBytes(index,input,length);
    }
    @Override public int setBytes(int index,FileChannel input,long position,int length) throws IOException{
        checkIndex(index,length);return owner.heapBacking().setBytes(index,input,position,length);
    }
    @Override public int nioBufferCount(){return 1;}
    @Override public ByteBuffer nioBuffer(int index,int length){checkIndex(index,length);return owner.heapBacking().nioBuffer(index,length);}
    @Override public ByteBuffer internalNioBuffer(int index,int length){return nioBuffer(index,length);}
    @Override public ByteBuffer[] nioBuffers(int index,int length){return new ByteBuffer[]{nioBuffer(index,length)};}
    @Override public ByteBuf copy(int index,int length){
        checkIndex(index,length);ByteBuf copy=alloc().heapBuffer(length,maxCapacity());
        try{getBytes(index,copy,0,length);copy.writerIndex(length);return copy;}
        catch(RuntimeException|Error failure){copy.release();throw failure;}
    }
}
