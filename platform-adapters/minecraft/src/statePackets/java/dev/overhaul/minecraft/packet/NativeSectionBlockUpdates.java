package dev.overhaul.minecraft.packet;

import java.nio.ByteBuffer;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** Received records retain the exact decode-time state identities without per-packet state/position arrays. */
public final class NativeSectionBlockUpdates {
    private static final class Decoder implements Predicate<ByteBuffer> {
        private final FriendlyByteBuf source;
        private final int count;
        private final IdMapperSnapshotAccess mapper;
        private IdMapperSnapshot snapshot;
        Decoder(FriendlyByteBuf source,int count,IdMapperSnapshotAccess mapper){this.source=source;this.count=count;this.mapper=mapper;}
        @Override public boolean test(ByteBuffer output){
            snapshot=mapper.overhaul$identitySnapshot();if(snapshot==null)return false;
            for(int index=0;index<count;index++){
                long packed=source.readVarLong();int stateId=(int)(packed>>>12);
                if(Block.BLOCK_STATE_REGISTRY.byId(stateId)!=snapshot.byId(stateId)){
                    mapper.overhaul$invalidateIdentitySnapshot(snapshot);return false;
                }
                output.putLong(packed);
            }
            return mapper.overhaul$isCurrent(snapshot);
        }
    }
    private final OwnedChunkPayload payload;
    private final IdMapperSnapshot snapshot;
    private final int count;
    private NativeSectionBlockUpdates(OwnedChunkPayload payload,IdMapperSnapshot snapshot,int count){this.payload=payload;this.snapshot=snapshot;this.count=count;}

    public static NativeSectionBlockUpdates tryRead(FriendlyByteBuf source,int count,ChunkPacketDecode scope){
        if(count<=0)return null;long bytes=(long)count*Long.BYTES;
        if(bytes>Integer.MAX_VALUE||!ChunkPacketMemory.eligiblePayloadSize((int)bytes))return null;
        if(count>source.readableBytes())return null;
        int start=source.readerIndex();
        var decoder=new Decoder(source,count,(IdMapperSnapshotAccess)Block.BLOCK_STATE_REGISTRY);
        var payload=scope.tryCreate((int)bytes,decoder);
        if(payload==null){source.readerIndex(start);return null;}
        return new NativeSectionBlockUpdates(payload,decoder.snapshot,count);
    }
    public int count(){return count;}
    private BlockState state(long packed){return (BlockState)snapshot.byId((int)(packed>>>12));}
    public void runUpdates(SectionPos section,BiConsumer<BlockPos,BlockState> consumer){
        var position=new BlockPos.MutableBlockPos();
        try(var borrowed=payload.borrow()){
            var records=borrowed.buffer();
            for(int index=0;index<count;index++){
                long packed=records.readLong();short local=(short)(packed&4095L);
                position.set(section.relativeToBlockX(local),section.relativeToBlockY(local),section.relativeToBlockZ(local));
                consumer.accept(position,state(packed));
            }
        }
    }
    /** The original writer canonicalizes resolved states, including null/unmapped -> ID zero. */
    public void writeRecords(FriendlyByteBuf target){
        try(var borrowed=payload.borrow()){
            var records=borrowed.buffer();
            for(int index=0;index<count;index++){
                long packed=records.readLong();target.writeVarLong(((long)Block.getId(state(packed))<<12)|(packed&4095L));
            }
        }
    }
}
