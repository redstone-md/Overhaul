package dev.overhaul.minecraft.smoke;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import java.util.concurrent.CompletableFuture;

/** Mutations stay on the integrated-server owner; observations use received client states. */
final class TerrainFixtures {
    static final BlockPos LEFT=new BlockPos(15,4,4),RIGHT=new BlockPos(16,4,4),CHEST=new BlockPos(14,4,6);
    private volatile int placements;

    CompletableFuture<Void> place(IntegratedServer server,int stage) {
        return server.submit(()-> {
            if(!server.isSameThread())throw new IllegalStateException("Terrain fixtures require the server owner");
            if(stage==1) {
                basin(server,13,9,Blocks.WATER.defaultBlockState());
                basin(server,18,9,Blocks.LAVA.defaultBlockState());
                server.overworld().setBlock(CHEST,Blocks.CHEST.defaultBlockState(),Block.UPDATE_ALL);
            }
            // Multiple superseding states exercise the real packet/rebuild path; only the final state is accepted.
            for(int i=0;i<(stage==1?1:12);i++) {
                server.overworld().setBlock(LEFT,i%2==0?Blocks.STONE.defaultBlockState():Blocks.GLASS.defaultBlockState(),Block.UPDATE_ALL);
                server.overworld().setBlock(RIGHT,i%2==0?Blocks.OAK_LEAVES.defaultBlockState():Blocks.GLASS.defaultBlockState(),Block.UPDATE_ALL);
            }
            server.overworld().setBlock(LEFT,left(stage),Block.UPDATE_ALL);
            server.overworld().setBlock(RIGHT,right(stage),Block.UPDATE_ALL);
            placements++;
        });
    }
    private static void basin(IntegratedServer server,int x,int z,BlockState fluid) {
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)if(dx!=0||dz!=0)
            server.overworld().setBlock(new BlockPos(x+dx,4,z+dz),Blocks.GLASS.defaultBlockState(),Block.UPDATE_ALL);
        server.overworld().setBlock(new BlockPos(x,4,z),fluid,Block.UPDATE_ALL);
    }
    boolean matches(ClientLevel level,int stage) {
        return level.getBlockState(LEFT).equals(left(stage))&&level.getBlockState(RIGHT).equals(right(stage))
                &&level.getBlockState(CHEST).is(Blocks.CHEST)
                &&level.getBlockState(new BlockPos(13,4,9)).is(Blocks.WATER)
                &&level.getBlockState(new BlockPos(18,4,9)).is(Blocks.LAVA);
    }
    int placements(){return placements;}
    private static BlockState left(int stage){return(stage==1?Blocks.WOOL.red():stage==2?Blocks.GOLD_BLOCK:Blocks.IRON_BLOCK).defaultBlockState();}
    private static BlockState right(int stage){return(stage==1?Blocks.WOOL.blue():stage==2?Blocks.EMERALD_BLOCK:Blocks.DIAMOND_BLOCK).defaultBlockState();}
}
