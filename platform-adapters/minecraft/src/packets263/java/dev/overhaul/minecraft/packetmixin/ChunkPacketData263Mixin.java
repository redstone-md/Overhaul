package dev.overhaul.minecraft.packetmixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.datafixers.util.Function3;
import dev.overhaul.minecraft.packet.OwnedChunkPayload;
import dev.overhaul.minecraft.packet263.ChunkPacketPayloadBinding;
import dev.overhaul.minecraft.packet263.PooledChunkDataCodec;
import java.util.Objects;
import java.util.function.Function;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientboundLevelChunkPacketData.class)
public abstract class ChunkPacketData263Mixin implements ChunkPacketPayloadBinding {
    @Unique private OwnedChunkPayload overhaul$payload;
    @Override public OwnedChunkPayload overhaul$chunkPayload(){return overhaul$payload;}
    @Override public void overhaul$bindChunkPayload(OwnedChunkPayload payload){
        Objects.requireNonNull(payload,"payload");
        if(overhaul$payload!=null)throw new IllegalStateException("Chunk packet payload was already bound");
        overhaul$payload=payload;
    }

    @WrapOperation(method="<clinit>",at=@At(value="INVOKE",target="Lnet/minecraft/network/codec/StreamCodec;composite(Lnet/minecraft/network/codec/StreamCodec;Ljava/util/function/Function;Lnet/minecraft/network/codec/StreamCodec;Ljava/util/function/Function;Lnet/minecraft/network/codec/StreamCodec;Ljava/util/function/Function;Lcom/mojang/datafixers/util/Function3;)Lnet/minecraft/network/codec/StreamCodec;"),require=1,allow=1)
    private static <H,E> StreamCodec<RegistryFriendlyByteBuf,ClientboundLevelChunkPacketData> overhaul$sectionCodec(
            StreamCodec<? super RegistryFriendlyByteBuf,H> heights,Function<ClientboundLevelChunkPacketData,H> heightGetter,
            StreamCodec<? super RegistryFriendlyByteBuf,byte[]> sections,Function<ClientboundLevelChunkPacketData,byte[]> sectionGetter,
            StreamCodec<? super RegistryFriendlyByteBuf,E> entities,Function<ClientboundLevelChunkPacketData,E> entityGetter,
            Function3<H,byte[],E,ClientboundLevelChunkPacketData> factory,
            Operation<StreamCodec<RegistryFriendlyByteBuf,ClientboundLevelChunkPacketData>> original){
        var vanilla=original.call(heights,heightGetter,sections,sectionGetter,entities,entityGetter,factory);
        return new PooledChunkDataCodec<>(vanilla,heights,heightGetter,sections,entities,entityGetter,factory);
    }

    @Inject(method="getReadBuffer",at=@At("HEAD"),cancellable=true)
    private void overhaul$publicHeapReader(CallbackInfoReturnable<FriendlyByteBuf> result){
        if(overhaul$payload!=null)result.setReturnValue(new FriendlyByteBuf(overhaul$payload.publicReader()));
    }
}
