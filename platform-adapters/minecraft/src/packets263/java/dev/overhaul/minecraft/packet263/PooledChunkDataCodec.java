package dev.overhaul.minecraft.packet263;

import com.mojang.datafixers.util.Function3;
import dev.overhaul.minecraft.packet.ChunkPacketDecode;
import dev.overhaul.minecraft.packet.ChunkPacketPayloadBinding;
import dev.overhaul.minecraft.packet.OwnedChunkPayload;
import java.util.Objects;
import java.util.function.Function;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;

/** The original three component codecs/factory, with call-local ownership of section bytes. */
public final class PooledChunkDataCodec<H,E> implements StreamCodec<RegistryFriendlyByteBuf,ClientboundLevelChunkPacketData> {
    private static final int MAX_SECTION_BYTES=2097152;
    private static final byte[] NO_HEAP_SECTIONS=new byte[0];
    private final StreamCodec<RegistryFriendlyByteBuf,ClientboundLevelChunkPacketData> original;
    private final StreamCodec<? super RegistryFriendlyByteBuf,H> heights;
    private final Function<ClientboundLevelChunkPacketData,H> heightGetter;
    private final StreamCodec<? super RegistryFriendlyByteBuf,byte[]> sections;
    private final StreamCodec<? super RegistryFriendlyByteBuf,E> entities;
    private final Function<ClientboundLevelChunkPacketData,E> entityGetter;
    private final Function3<H,byte[],E,ClientboundLevelChunkPacketData> factory;

    public PooledChunkDataCodec(StreamCodec<RegistryFriendlyByteBuf,ClientboundLevelChunkPacketData> original,
            StreamCodec<? super RegistryFriendlyByteBuf,H> heights,Function<ClientboundLevelChunkPacketData,H> heightGetter,
            StreamCodec<? super RegistryFriendlyByteBuf,byte[]> sections,
            StreamCodec<? super RegistryFriendlyByteBuf,E> entities,Function<ClientboundLevelChunkPacketData,E> entityGetter,
            Function3<H,byte[],E,ClientboundLevelChunkPacketData> factory){
        this.original=Objects.requireNonNull(original);this.heights=Objects.requireNonNull(heights);
        this.heightGetter=Objects.requireNonNull(heightGetter);this.sections=Objects.requireNonNull(sections);
        this.entities=Objects.requireNonNull(entities);this.entityGetter=Objects.requireNonNull(entityGetter);
        this.factory=Objects.requireNonNull(factory);
    }

    @Override public ClientboundLevelChunkPacketData decode(RegistryFriendlyByteBuf input){
        ChunkPacketDecode scope=ChunkPacketDecode.find(input);
        if(scope==null)return original.decode(input);
        H heightData=heights.decode(input);
        int prefix=input.readerIndex();
        int length=VarInt.read(input);
        OwnedChunkPayload payload=length>=0&&length<=MAX_SECTION_BYTES?scope.read(input,length):null;
        byte[] sectionData;
        if(payload==null){
            // Preserve the original byte-array codec's validation, exception and cursor semantics.
            input.readerIndex(prefix);sectionData=sections.decode(input);
        }else sectionData=NO_HEAP_SECTIONS;
        E entityData=entities.decode(input);
        ClientboundLevelChunkPacketData result=factory.apply(heightData,sectionData,entityData);
        // The decode journal already owns a pooled lease if either component/factory throws.
        if(payload!=null)((ChunkPacketPayloadBinding)(Object)result).overhaul$bindChunkPayload(payload);
        return result;
    }

    @Override public void encode(RegistryFriendlyByteBuf output,ClientboundLevelChunkPacketData value){
        OwnedChunkPayload payload=((ChunkPacketPayloadBinding)(Object)value).overhaul$chunkPayload();
        if(payload==null){original.encode(output,value);return;}
        heights.encode(output,heightGetter.apply(value));
        VarInt.write(output,payload.length());payload.write(output);
        entities.encode(output,entityGetter.apply(value));
    }
}
