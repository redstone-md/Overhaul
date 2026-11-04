package dev.overhaul.minecraft.packetmixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.overhaul.minecraft.packet.ChunkPacketDecode;
import dev.overhaul.minecraft.packet.ChunkPacketMemory;
import dev.overhaul.minecraft.packet.ChunkPacketMemoryAccess;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import java.util.List;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketDecoder;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.PacketFlow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(PacketDecoder.class)
public abstract class ChunkPacketDecoder263Mixin {
    @Shadow @Final private ProtocolInfo<?> protocolInfo;
    @WrapMethod(method="decode(Lio/netty/channel/ChannelHandlerContext;Lio/netty/buffer/ByteBuf;Ljava/util/List;)V",remap=false)
    private void overhaul$scope(ChannelHandlerContext context,ByteBuf input,List<Object> output,Operation<Void> original) throws Exception {
        if(protocolInfo.flow()!=PacketFlow.CLIENTBOUND||input.readableBytes()<ChunkPacketMemory.MIN_FRAME_BYTES){original.call(context,input,output);return;}
        Connection connection=context.pipeline().get(Connection.class);
        if(!(connection instanceof ChunkPacketMemoryAccess access)){original.call(context,input,output);return;}
        try(var scope=new ChunkPacketDecode(access.overhaul$chunkPacketMemory(),context.alloc(),input)){
            original.call(context,scope.buffer(),output);
        }
    }
    @WrapOperation(method="decode(Lio/netty/channel/ChannelHandlerContext;Lio/netty/buffer/ByteBuf;Ljava/util/List;)V",remap=false,
            at=@At(value="INVOKE",target="Ljava/util/List;add(Ljava/lang/Object;)Z",remap=false),require=1,allow=1)
    private boolean overhaul$published(List<Object> output,Object packet,Operation<Boolean> original,
            ChannelHandlerContext context,ByteBuf input,List<Object> enclosingOutput){
        boolean added=original.call(output,packet);
        if(added){var scope=ChunkPacketDecode.find(input);if(scope!=null)scope.published();}
        return added;
    }
}
