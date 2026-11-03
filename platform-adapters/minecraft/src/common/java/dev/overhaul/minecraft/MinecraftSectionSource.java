package dev.overhaul.minecraft;

import dev.overhaul.adapter.SectionKey;
import dev.overhaul.adapter.SectionSource;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** Main-thread-only sampling. The mesher currently accepts opaque cube models only. */
public final class MinecraftSectionSource implements SectionSource {
    private final Minecraft minecraft;
    private final BlockPos.MutableBlockPos cursor=new BlockPos.MutableBlockPos();

    public MinecraftSectionSource(Minecraft minecraft) { this.minecraft=minecraft; }

    @Override public int material(int x,int y,int z) {
        cursor.set(x,y,z);
        BlockState state=minecraft.level.getBlockState(cursor);
        return BlockClassifier.opaque(state,minecraft,cursor) ? Block.getId(state) : 0;
    }

    @Override public int emission(int x,int y,int z) {
        cursor.set(x,y,z);
        return minecraft.level.getBlockState(cursor).getLightEmission();
    }

    public static SectionKey playerSection(Minecraft minecraft) {
        BlockPos position=minecraft.player.blockPosition();
        return new SectionKey(Math.floorDiv(position.getX(),16),Math.floorDiv(position.getY(),16),
                Math.floorDiv(position.getZ(),16));
    }

    public boolean haloLoaded(SectionKey key) {
        int x=key.x()*16,z=key.z()*16,y=key.y()*16;
        for (int dx=-1;dx<=16;dx+=17) for (int dz=-1;dz<=16;dz+=17) {
            cursor.set(x+dx,y,z+dz);
            if (!minecraft.level.hasChunkAt(cursor)) return false;
        }
        return true;
    }
}
