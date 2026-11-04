package dev.overhaul.minecraft.packetmixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.overhaul.minecraft.packet.ChunkPacketDecode;
import dev.overhaul.minecraft.packet.NativeSectionBlockUpdates;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientboundSectionBlocksUpdatePacket.class)
public abstract class SectionBlockUpdatesMixin {
    @Shadow @Final private SectionPos sectionPos;
    @Unique private NativeSectionBlockUpdates overhaul$updates;
    @WrapOperation(method="<init>(Lnet/minecraft/network/FriendlyByteBuf;)V",at=@At(value="INVOKE",target="Lnet/minecraft/network/FriendlyByteBuf;readVarInt()I"),require=1,allow=1)
    private int overhaul$records(FriendlyByteBuf source,Operation<Integer> original){
        int count=original.call(source);var scope=ChunkPacketDecode.find(source);
        if(scope!=null)overhaul$updates=NativeSectionBlockUpdates.tryRead(source,count,scope);
        return overhaul$updates==null?count:0;
    }
    @WrapOperation(method="write(Lnet/minecraft/network/FriendlyByteBuf;)V",at=@At(value="INVOKE",target="Lnet/minecraft/network/FriendlyByteBuf;writeVarInt(I)Lnet/minecraft/network/FriendlyByteBuf;"),require=1,allow=1)
    private FriendlyByteBuf overhaul$write(FriendlyByteBuf target,int count,Operation<FriendlyByteBuf> original){
        var result=original.call(target,overhaul$updates==null?count:overhaul$updates.count());
        if(overhaul$updates!=null)overhaul$updates.writeRecords(target);
        return result;
    }
    @Inject(method="runUpdates",at=@At("HEAD"),cancellable=true)
    private void overhaul$run(BiConsumer<BlockPos,BlockState> consumer,CallbackInfo callback){
        if(overhaul$updates!=null){overhaul$updates.runUpdates(sectionPos,consumer);callback.cancel();}
    }
}
