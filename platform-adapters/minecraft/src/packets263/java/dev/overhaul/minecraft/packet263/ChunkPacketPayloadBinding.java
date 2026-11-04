package dev.overhaul.minecraft.packet263;

import dev.overhaul.minecraft.packet.ChunkPacketPayloadAccess;
import dev.overhaul.minecraft.packet.OwnedChunkPayload;

/** Version-local construction hook; a packet accepts its payload exactly once. */
public interface ChunkPacketPayloadBinding extends ChunkPacketPayloadAccess {
    void overhaul$bindChunkPayload(OwnedChunkPayload payload);
}
