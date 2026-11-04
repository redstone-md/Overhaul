package dev.overhaul.minecraft.packetmixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.overhaul.minecraft.packet.ChunkPacketPayloadAccess;
import dev.overhaul.minecraft.packet.OwnedChunkPayload;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** Only synchronous section application borrows native bytes; public packet readers stay writable heap buffers. */
@Mixin(LevelChunk.class)
public abstract class ChunkPacketApply263Mixin {
    @Unique private OwnedChunkPayload.Borrow overhaul$currentChunk;
    @Unique private ClientboundLevelChunkPacketData overhaul$currentPacket;
    @WrapMethod(method="replaceWithPacketData(IILnet/minecraft/network/protocol/game/ClientboundLevelChunkPacketData;)V")
    private void overhaul$apply(int x,int z,ClientboundLevelChunkPacketData data,Operation<Void> original){
        OwnedChunkPayload payload=((ChunkPacketPayloadAccess)(Object)data).overhaul$chunkPayload();
        if(payload==null){original.call(x,z,data);return;}
        var previous=overhaul$currentChunk;var previousPacket=overhaul$currentPacket;
        try(var borrowed=payload.borrow()){
            overhaul$currentChunk=borrowed;overhaul$currentPacket=data;
            try{original.call(x,z,data);}
            finally{overhaul$currentChunk=previous;overhaul$currentPacket=previousPacket;}
        }
    }
    @WrapOperation(method="replaceWithPacketData(IILnet/minecraft/network/protocol/game/ClientboundLevelChunkPacketData;)V",
            at=@At(value="INVOKE",target="Lnet/minecraft/network/protocol/game/ClientboundLevelChunkPacketData;getReadBuffer()Lnet/minecraft/network/FriendlyByteBuf;"),require=1,allow=1)
    private FriendlyByteBuf overhaul$nativeReader(ClientboundLevelChunkPacketData data,Operation<FriendlyByteBuf> original){
        return data==overhaul$currentPacket&&overhaul$currentChunk!=null?new FriendlyByteBuf(overhaul$currentChunk.buffer()):original.call(data);
    }
}
