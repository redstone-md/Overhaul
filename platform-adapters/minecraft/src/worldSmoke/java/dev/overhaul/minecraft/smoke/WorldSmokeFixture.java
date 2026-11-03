package dev.overhaul.minecraft.smoke;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/** Actual blocks in the smoke's new world, changed by its integrated-server owner only. */
final class WorldSmokeFixture {
    private record Entry(String id,BlockPos position,BlockState state) { }
    private final Thread owner=Thread.currentThread();
    private final List<Entry> entries=List.of(
            new Entry("minecraft:oak_slab",new BlockPos(2,4,4),Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE,SlabType.BOTTOM)),
            new Entry("minecraft:glass",new BlockPos(4,4,4),Blocks.GLASS.defaultBlockState()),
            new Entry("minecraft:dandelion",new BlockPos(6,4,4),Blocks.DANDELION.defaultBlockState()),
            new Entry("minecraft:oak_stairs",new BlockPos(8,4,4),Blocks.OAK_STAIRS.defaultBlockState()),
            new Entry("minecraft:oak_leaves",new BlockPos(10,4,4),Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT,true)));
    private volatile String serverThread="not placed";
    private int clientMatches;

    CompletableFuture<Void> place(IntegratedServer server) {
        requireOwner();
        return server.submit(()-> {
            if (!server.isSameThread()) throw new IllegalStateException("Fixture mutations must run on integrated-server thread");
            for (Entry entry:entries) {
                server.overworld().setBlock(entry.position,entry.state,Block.UPDATE_ALL);
                if (!server.overworld().getBlockState(entry.position).equals(entry.state)) {
                    throw new IllegalStateException("Server fixture placement failed: "+entry.id);
                }
            }
            serverThread=Thread.currentThread().getName();
            Logger.getLogger("overhaul").info("Overhaul world smoke placed "+entries.size()+" actual block fixtures on "+serverThread);
        });
    }

    boolean clientMatches(ClientLevel level) {
        requireOwner();
        int matched=0;
        for (Entry entry:entries) if (level.getBlockState(entry.position).equals(entry.state)) matched++;
        clientMatches=matched;
        return matched==entries.size();
    }

    int verifiedClientStates() { requireOwner(); return clientMatches; }
    String placementThread() { requireOwner(); return serverThread; }

    String toJson() {
        requireOwner();
        StringBuilder out=new StringBuilder("[");
        for (int index=0;index<entries.size();index++) {
            Entry entry=entries.get(index);
            if (index>0) out.append(',');
            out.append("{\"block\":").append(SmokeJson.quote(entry.id))
                    .append(",\"x\":").append(entry.position.getX())
                    .append(",\"y\":").append(entry.position.getY())
                    .append(",\"z\":").append(entry.position.getZ())
                    .append(",\"state\":").append(SmokeJson.quote(entry.state.toString())).append('}');
        }
        return out.append(']').toString();
    }

    private void requireOwner() {
        if (Thread.currentThread()!=owner) throw new IllegalStateException("Fixture capture belongs to the client owner");
    }
}
