package dev.overhaul.minecraft.smoke;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** A wall with an open route above it preserves vanilla section connectivity. */
final class OcclusionFixtures {
    static final BlockPos TARGET=new BlockPos(8,20,24);
    static final BlockPos[] SECTIONS={new BlockPos(-8,20,8),new BlockPos(8,20,8),TARGET};
    static long sectionNode(BlockPos position){return SectionPos.asLong(SectionPos.blockToSectionCoord(position.getX()),1,SectionPos.blockToSectionCoord(position.getZ()));}

    CompletableFuture<Void> place(IntegratedServer server,UUID playerId,int stage) {
        return server.submit(()-> {
            if(!server.isSameThread())throw new IllegalStateException("Occlusion fixtures require the server owner");
            var level=server.overworld();
            for(int x=-8;x<=15;x++)for(int y=16;y<=27;y++) {
                boolean hole=stage==1&&x>=6&&x<=10&&y>=19&&y<=23;
                level.setBlock(new BlockPos(x,y,8),(hole?Blocks.AIR:Blocks.STONE).defaultBlockState(),Block.UPDATE_ALL);
            }
            level.setBlock(TARGET,Blocks.DIAMOND_BLOCK.defaultBlockState(),Block.UPDATE_ALL);
            var player=server.getPlayerList().getPlayer(playerId);
            if(player==null)throw new IllegalStateException("Occlusion fixture player is absent");
            player.getAbilities().mayfly=true;player.getAbilities().flying=true;player.onUpdateAbilities();
            player.setNoGravity(true);player.setDeltaMovement(0,0,0);
            if(!player.teleportTo(level,stage==3?28:8,20,0.5,Set.of(),stage==3?40.4f:0,3,true))
                throw new IllegalStateException("Cannot place occlusion camera");
        });
    }
    boolean matches(ClientLevel level,int stage) {
        if(!level.getBlockState(TARGET).is(Blocks.DIAMOND_BLOCK))return false;
        for(int x=-8;x<=15;x++)for(int y=16;y<=27;y++) {
            boolean hole=stage==1&&x>=6&&x<=10&&y>=19&&y<=23;
            if(!level.getBlockState(new BlockPos(x,y,8)).is(hole?Blocks.AIR:Blocks.STONE))return false;
        }
        return true;
    }
}
