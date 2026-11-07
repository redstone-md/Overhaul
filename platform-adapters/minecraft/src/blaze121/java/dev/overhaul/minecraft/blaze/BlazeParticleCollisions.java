package dev.overhaul.minecraft.blaze;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * {@code Entity.collideBoundingBox} for a particle, which has no entity and no extra shapes: vanilla gathers the block
 * shapes around the swept box through a {@code BlockCollisions} iterator into an immutable list, then clips the motion
 * against them. This visits the same blocks in the same order with the same filters, straight into a plain list, and
 * clips the same way, so the answer is vanilla's without the iterator, the builder and the list copies. Rain resting
 * on the ground or falling through open air calls this for every drop on every tick.
 */
public final class BlazeParticleCollisions {
    private static final double EPSILON = 1.0E-7;

    private BlazeParticleCollisions() {}

    public static Vec3 collide(Level level, Vec3 motion, AABB box) {
        List<VoxelShape> shapes = shapes(level, box.expandTowards(motion));
        return shapes.isEmpty() ? motion : clip(motion, box, shapes);
    }

    /** {@code BlockCollisions} with a null entity, unrolled: x fastest, then y, then z, as its cursor walks. */
    private static List<VoxelShape> shapes(Level level, AABB swept) {
        int minX = Mth.floor(swept.minX - EPSILON) - 1, maxX = Mth.floor(swept.maxX + EPSILON) + 1;
        int minY = Mth.floor(swept.minY - EPSILON) - 1, maxY = Mth.floor(swept.maxY + EPSILON) + 1;
        int minZ = Mth.floor(swept.minZ - EPSILON) - 1, maxZ = Mth.floor(swept.maxZ + EPSILON) + 1;
        CollisionContext context = CollisionContext.empty();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        List<VoxelShape> shapes = List.of();
        VoxelShape sweptShape = null;
        BlockGetter chunk = null;
        int chunkX = 0, chunkZ = 0;
        boolean cached = false;
        for (int z = minZ; z <= maxZ; z++) {
            int edgeZ = z == minZ || z == maxZ ? 1 : 0;
            for (int y = minY; y <= maxY; y++) {
                int edgeYZ = edgeZ + (y == minY || y == maxY ? 1 : 0);
                for (int x = minX; x <= maxX; x++) {
                    int edges = edgeYZ + (x == minX || x == maxX ? 1 : 0);
                    if (edges == 3) continue;
                    if (!cached || x >> 4 != chunkX || z >> 4 != chunkZ) {
                        chunkX = x >> 4;
                        chunkZ = z >> 4;
                        chunk = level.getChunkForCollisions(chunkX, chunkZ);
                        cached = true;
                    }
                    if (chunk == null) continue;
                    pos.set(x, y, z);
                    BlockState state = chunk.getBlockState(pos);
                    if (edges == 1 && !state.hasLargeCollisionShape()) continue;
                    if (edges == 2 && !state.is(Blocks.MOVING_PISTON)) continue;
                    VoxelShape shape = state.getCollisionShape(level, pos, context);
                    if (shape.isEmpty()) continue;
                    VoxelShape placed;
                    if (shape == Shapes.block()) {
                        if (!swept.intersects(x, y, z, x + 1.0, y + 1.0, z + 1.0)) continue;
                        placed = shape.move(x, y, z);
                    } else {
                        placed = shape.move(x, y, z);
                        if (sweptShape == null) sweptShape = Shapes.create(swept);
                        if (!Shapes.joinIsNotEmpty(placed, sweptShape, BooleanOp.AND)) continue;
                    }
                    if (shapes.isEmpty()) shapes = new ArrayList<>(4);
                    shapes.add(placed);
                }
            }
        }
        return shapes;
    }

    /** {@code Entity.collideWithShapes}: y first, then the larger of x and z last. */
    private static Vec3 clip(Vec3 motion, AABB box, List<VoxelShape> shapes) {
        double x = motion.x, y = motion.y, z = motion.z;
        if (y != 0.0) {
            y = Shapes.collide(Direction.Axis.Y, box, shapes, y);
            if (y != 0.0) box = box.move(0.0, y, 0.0);
        }
        boolean zFirst = Math.abs(x) < Math.abs(z);
        if (zFirst && z != 0.0) {
            z = Shapes.collide(Direction.Axis.Z, box, shapes, z);
            if (z != 0.0) box = box.move(0.0, 0.0, z);
        }
        if (x != 0.0) {
            x = Shapes.collide(Direction.Axis.X, box, shapes, x);
            if (!zFirst && x != 0.0) box = box.move(x, 0.0, 0.0);
        }
        if (!zFirst && z != 0.0) z = Shapes.collide(Direction.Axis.Z, box, shapes, z);
        return new Vec3(x, y, z);
    }
}
