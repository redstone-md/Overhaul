package dev.overhaul.minecraft.packet;

import java.util.Objects;

/** Internal write-once binding used only after the original constructor/factory has returned successfully. */
public interface ChunkPacketPayloadBinding extends ChunkPacketPayloadAccess {
    void overhaul$bindChunkPayload(OwnedChunkPayload payload);
    static OwnedChunkPayload validate(OwnedChunkPayload previous,byte[] originalSections,boolean metadataReady,OwnedChunkPayload next){
        Objects.requireNonNull(next,"payload");
        if(previous!=null)throw new IllegalStateException("Chunk packet payload was already bound");
        if(originalSections==null||originalSections.length!=0||!metadataReady)
            throw new IllegalStateException("Chunk packet payload binding is outside its completed native constructor phase");
        return next;
    }
}
