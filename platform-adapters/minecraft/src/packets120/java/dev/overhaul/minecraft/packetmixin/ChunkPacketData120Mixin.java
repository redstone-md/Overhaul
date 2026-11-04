package dev.overhaul.minecraft.packetmixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.overhaul.minecraft.packet.ChunkPacketDecode;
import dev.overhaul.minecraft.packet.ChunkPacketPayloadAccess;
import dev.overhaul.minecraft.packet.OwnedChunkPayload;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Desc;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Only received section bytes move to the shared owner; NBT and block-entity codecs stay original. */
@Mixin(ClientboundLevelChunkPacketData.class)
public abstract class ChunkPacketData120Mixin implements ChunkPacketPayloadAccess {
    @Unique private OwnedChunkPayload overhaul$payload;

    @Override public OwnedChunkPayload overhaul$chunkPayload() { return overhaul$payload; }

    @WrapOperation(method="<init>(Lnet/minecraft/network/FriendlyByteBuf;II)V",
            at=@At(value="INVOKE", target="Lnet/minecraft/network/FriendlyByteBuf;readVarInt()I"), require=1, allow=1)
    private int overhaul$sectionBytes(FriendlyByteBuf source, Operation<Integer> original) {
        int length = original.call(source);
        if (length < 0 || length > 2097152) return length;
        var scope = ChunkPacketDecode.find(source);
        if (scope != null) overhaul$payload = scope.read(source, length);
        return overhaul$payload == null ? length : 0;
    }

    @Inject(method="getReadBuffer", at=@At("HEAD"), cancellable=true)
    private void overhaul$publicHeapReader(CallbackInfoReturnable<FriendlyByteBuf> result) {
        if (overhaul$payload != null) result.setReturnValue(new FriendlyByteBuf(overhaul$payload.publicReader()));
    }

    @WrapOperation(method="write", at=@At(value="INVOKE",
            target="Lnet/minecraft/network/FriendlyByteBuf;writeVarInt(I)Lnet/minecraft/network/FriendlyByteBuf;"), require=1, allow=1)
    private FriendlyByteBuf overhaul$length(FriendlyByteBuf target, int length, Operation<FriendlyByteBuf> original) {
        return original.call(target, overhaul$payload == null ? length : overhaul$payload.length());
    }

    @WrapOperation(method="write", at=@At(value="INVOKE", remap=false,
            desc=@Desc(owner=FriendlyByteBuf.class, value="writeBytes", args={byte[].class}, ret=ByteBuf.class)), require=1, allow=1)
    private ByteBuf overhaul$writeBytes(FriendlyByteBuf target, byte[] data, Operation<ByteBuf> original) {
        if (overhaul$payload == null) return original.call(target, data);
        overhaul$payload.write(target);
        return target;
    }
}
