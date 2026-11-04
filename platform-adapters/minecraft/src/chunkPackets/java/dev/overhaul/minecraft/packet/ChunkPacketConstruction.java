package dev.overhaul.minecraft.packet;

import io.netty.buffer.ByteBuf;
import java.lang.ref.Reference;

/** Private construction capability; escaped writer views keep their payload alive without owning slab retirement. */
public final class ChunkPacketConstruction implements AutoCloseable {
    private final OwnedChunkPayload payload;
    private boolean writerExposed,extracted,sealed;

    ChunkPacketConstruction(OwnedChunkPayload payload){
        this.payload=payload;
        // Match new byte[length], including unwritten tails on legacy serializers and reused-slab contents.
        payload.initialize(output->{
            while(output.remaining()>=Long.BYTES)output.putLong(0);
            while(output.hasRemaining())output.put((byte)0);
            return true;
        });
    }
    public ByteBuf writer(){
        if(extracted||sealed)throw new IllegalStateException("Chunk packet extraction is already complete");
        ByteBuf view=payload.constructionWriter();writerExposed=true;return view;
    }
    public void extracted(){
        if(!writerExposed||extracted||sealed)throw new IllegalStateException("Unexpected chunk packet extraction phase");
        extracted=true;
    }
    /** Called only at the original packet constructor's successful return. */
    public OwnedChunkPayload seal(){
        if(!extracted||sealed)throw new IllegalStateException("Chunk packet constructor has not completed extraction");
        sealed=true;return payload;
    }
    @Override public void close(){Reference.reachabilityFence(payload);}
}
