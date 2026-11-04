package dev.overhaul.minecraft.packetmixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.overhaul.minecraft.packet.ChunkPacketMemory;
import dev.overhaul.minecraft.packet.ServerChunkPacketMemoryAccess;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(MinecraftServer.class)
public abstract class ServerChunkPacketMemoryMixin implements ServerChunkPacketMemoryAccess {
    @Unique private final ChunkPacketMemory overhaul$serverPackets=new ChunkPacketMemory();
    @Override public ChunkPacketMemory overhaul$serverChunkPacketMemory(){return overhaul$serverPackets;}
    @WrapMethod(method="stopServer()V")
    private void overhaul$closeAdmission(Operation<Void> original){
        // Finally cleanup preserves the original failure; live packet/view leases remain valid after stop.
        try(var memory=overhaul$serverPackets){original.call();}
    }
}
