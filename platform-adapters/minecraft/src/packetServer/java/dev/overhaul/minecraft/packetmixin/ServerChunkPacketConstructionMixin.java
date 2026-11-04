package dev.overhaul.minecraft.packetmixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.overhaul.minecraft.packet.ChunkPacketConstruction;
import dev.overhaul.minecraft.packet.ChunkPacketPayloadBinding;
import dev.overhaul.minecraft.packet.ServerChunkPacketMemoryAccess;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Storage changes at the private allocation seam; the original extraction and constructor body still run once. */
@Mixin(ClientboundLevelChunkPacketData.class)
public abstract class ServerChunkPacketConstructionMixin {
    @Unique private ChunkPacketConstruction overhaul$construction;
    @WrapOperation(method="<init>(Lnet/minecraft/world/level/chunk/LevelChunk;)V",at=@At(value="INVOKE",target="Lnet/minecraft/network/protocol/game/ClientboundLevelChunkPacketData;calculateChunkSize(Lnet/minecraft/world/level/chunk/LevelChunk;)I"),require=1,allow=1)
    private int overhaul$storage(LevelChunk chunk,Operation<Integer> original){
        int bytes=original.call(chunk);
        if(chunk.getLevel() instanceof ServerLevel level){
            var owner=(ServerChunkPacketMemoryAccess)level.getServer();
            overhaul$construction=owner.overhaul$serverChunkPacketMemory().beginConstruction(bytes,PooledByteBufAllocator.DEFAULT);
        }
        return overhaul$construction==null?bytes:0;
    }
    @WrapOperation(method="<init>(Lnet/minecraft/world/level/chunk/LevelChunk;)V",at=@At(value="INVOKE",target="Lnet/minecraft/network/protocol/game/ClientboundLevelChunkPacketData;getWriteBuffer()Lio/netty/buffer/ByteBuf;"),require=1,allow=1)
    private ByteBuf overhaul$writer(ClientboundLevelChunkPacketData packet,Operation<ByteBuf> original){
        return overhaul$construction==null?original.call(packet):overhaul$construction.writer();
    }
    @WrapOperation(method="<init>(Lnet/minecraft/world/level/chunk/LevelChunk;)V",at=@At(value="INVOKE",target="Lnet/minecraft/network/protocol/game/ClientboundLevelChunkPacketData;extractChunkData(Lnet/minecraft/network/FriendlyByteBuf;Lnet/minecraft/world/level/chunk/LevelChunk;)V"),require=1,allow=1)
    private void overhaul$extract(FriendlyByteBuf output,LevelChunk chunk,Operation<Void> original){
        var construction=overhaul$construction;
        if(construction==null){original.call(output,chunk);return;}
        try(construction){original.call(output,chunk);construction.extracted();}
    }
    @Inject(method="<init>(Lnet/minecraft/world/level/chunk/LevelChunk;)V",at=@At("RETURN"))
    private void overhaul$completed(LevelChunk chunk,CallbackInfo callback){
        var construction=overhaul$construction;
        if(construction!=null){((ChunkPacketPayloadBinding)this).overhaul$bindChunkPayload(construction.seal());overhaul$construction=null;}
    }
}
