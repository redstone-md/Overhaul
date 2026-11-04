package dev.overhaul.minecraft.packet;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.DuplicatedByteBuf;
import java.util.ArrayList;
import java.util.List;

/** Call-local decode journal; no thread-local routing, protocol mutation, or retained network frame slices. */
public final class ChunkPacketDecode implements AutoCloseable {
    private static final class Input extends DuplicatedByteBuf {
        private final ChunkPacketDecode scope;
        Input(ByteBuf source,ChunkPacketDecode scope){super(source);this.scope=scope;}
    }
    private final ChunkPacketMemory owner;
    private final ByteBufAllocator allocator;
    private final ByteBuf original;
    private final Input input;
    private final List<OwnedChunkPayload> unpublished=new ArrayList<>();
    public ChunkPacketDecode(ChunkPacketMemory owner,ByteBufAllocator allocator,ByteBuf original){
        this.owner=owner;this.allocator=allocator;this.original=original;input=new Input(original,this);
    }
    public ByteBuf buffer(){return input;}
    public static ChunkPacketDecode find(ByteBuf source){
        for(ByteBuf cursor=source;cursor!=null;){
            if(cursor instanceof Input)return ((Input)cursor).scope;
            ByteBuf next=cursor.unwrap();if(next==cursor)return null;cursor=next;
        }
        return null;
    }
    public OwnedChunkPayload read(ByteBuf source,int bytes){
        OwnedChunkPayload payload=owner.tryRead(source,bytes,allocator);if(payload==null)return null;
        try{unpublished.add(payload);return payload;}
        catch(RuntimeException|Error failure){try{payload.discardUnpublished();}catch(RuntimeException|Error cleanup){failure.addSuppressed(cleanup);}throw failure;}
    }
    /** Called only after the decoder has added its packet to Netty's output list successfully. */
    public void published(){unpublished.clear();}
    @Override public void close(){
        Throwable failure=null;
        try{original.readerIndex(input.readerIndex());}catch(RuntimeException|Error error){failure=error;}
        for(OwnedChunkPayload payload:unpublished)try{payload.discardUnpublished();}
        catch(RuntimeException|Error error){if(failure==null)failure=error;else failure.addSuppressed(error);}
        unpublished.clear();
        if(failure instanceof RuntimeException)throw (RuntimeException)failure;if(failure instanceof Error)throw (Error)failure;
    }
}
