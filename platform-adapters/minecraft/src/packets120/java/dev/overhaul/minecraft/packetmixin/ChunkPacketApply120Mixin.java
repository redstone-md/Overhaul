package dev.overhaul.minecraft.packetmixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.overhaul.minecraft.packet.ChunkPacketPayloadAccess;
import dev.overhaul.minecraft.packet.OwnedChunkPayload;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** Private synchronous application borrows native bytes; external packet readers still get the lazy heap view. */
@Mixin(ClientPacketListener.class)
public abstract class ChunkPacketApply120Mixin {
    @Unique private OwnedChunkPayload.Borrow overhaul$currentChunk;
    @Unique private ClientboundLevelChunkPacketData overhaul$currentPacket;

    @WrapMethod(method="updateLevelChunk")
    private void overhaul$apply(int x, int z, ClientboundLevelChunkPacketData data, Operation<Void> original) {
        var payload = ((ChunkPacketPayloadAccess) data).overhaul$chunkPayload();
        if (payload == null) { original.call(x, z, data); return; }
        var previous = overhaul$currentChunk;
        var previousPacket = overhaul$currentPacket;
        try (var borrowed = payload.borrow()) {
            overhaul$currentChunk = borrowed; overhaul$currentPacket = data;
            try { original.call(x, z, data); }
            finally { overhaul$currentChunk = previous; overhaul$currentPacket = previousPacket; }
        }
    }

    @WrapOperation(method="updateLevelChunk", at=@At(value="INVOKE",
            target="Lnet/minecraft/network/protocol/game/ClientboundLevelChunkPacketData;getReadBuffer()Lnet/minecraft/network/FriendlyByteBuf;"), require=1, allow=1)
    private FriendlyByteBuf overhaul$nativeReader(ClientboundLevelChunkPacketData data, Operation<FriendlyByteBuf> original) {
        return data == overhaul$currentPacket && overhaul$currentChunk != null
                ? new FriendlyByteBuf(overhaul$currentChunk.buffer()) : original.call(data);
    }
}
