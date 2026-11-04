package dev.overhaul.minecraft.packetmixin;

import dev.overhaul.minecraft.packet.ChunkPacketMemory;
import dev.overhaul.minecraft.packet.ChunkPacketMemoryAccess;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
public abstract class ConnectionChunkPacket263Mixin implements ChunkPacketMemoryAccess {
    @Unique private final ChunkPacketMemory overhaul$chunkPackets=new ChunkPacketMemory();
    @Override public ChunkPacketMemory overhaul$chunkPacketMemory(){return overhaul$chunkPackets;}
    @Inject(method={"disconnect(Lnet/minecraft/network/chat/Component;)V","disconnect(Lnet/minecraft/network/DisconnectionDetails;)V"},at=@At("HEAD"))
    private void overhaul$stopPacketAdmission(CallbackInfo callback){overhaul$chunkPackets.close();}
    @Inject(method="channelInactive(Lio/netty/channel/ChannelHandlerContext;)V",at=@At("HEAD"),remap=false)
    private void overhaul$channelInactive(CallbackInfo callback){overhaul$chunkPackets.close();}
}
