package dev.overhaul.minecraft.blaze;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Vanilla gathers the block shapes around a moving box through a {@code BlockCollisions} iterator, then copies them
 * into an immutable list. {@link #shapes} visits the same blocks in the same order with the same filters, straight
 * into a plain list, so the answer is vanilla's without the iterator. Every moving entity asks it on every tick, and
 * {@link #collide} serves particles, which have no entity and no extra shapes: rain resting on the ground or falling
 * through open air calls it for every drop on every tick. {@link #supportingBlock} walks the same blocks for the one
 * an entity stands on.
 */
public final class BlazeBlockCollisions {
    private static final double EPSILON = 1.0E-7;
    private static final boolean SECTIONS = BlazeOptimization.COLLISION_SECTIONS.on();
    private static final ClassValue<Boolean> VANILLA_SHAPES = vanilla("getBlockCollisions");
    private static final ClassValue<Boolean> VANILLA_SUPPORT = vanilla("findSupportingBlock");

    private BlazeBlockCollisions() {}

    /** Whether the level's class gathers block collisions as {@link CollisionGetter} does. */
    public static boolean vanillaShapes(Level level) {
        return VANILLA_SHAPES.get(level.getClass());
    }

    /** Whether the level's class finds supporting blocks as {@link CollisionGetter} does. */
    public static boolean vanillaSupport(Level level) {
        return VANILLA_SUPPORT.get(level.getClass());
    }

    /** {@code Entity.collideBoundingBox} with no entity and no extra shapes. */
    public static Vec3 collide(Level level, Vec3 motion, AABB box) {
        Gather gather = new Gather();
        gather.walk(level, CollisionContext.empty(), box.expandTowards(motion), BlazeRimSections.on);
        return gather.shapes.isEmpty() ? motion : clip(motion, box, gather.shapes);
    }

    /** {@code BlockCollisions} that are not only suffocating, gathered into a list. */
    public static List<VoxelShape> shapes(Level level, CollisionContext context, AABB swept) {
        Gather gather = new Gather();
        gather.walk(level, context, swept, false);
        return gather.shapes;
    }

    /** {@code CollisionGetter.findSupportingBlock}: the colliding block nearest the entity, ties to the greatest. */
    public static Optional<BlockPos> supportingBlock(Level level, Entity entity, AABB box) {
        Support support = new Support(entity.position());
        support.walk(level, CollisionContext.of(entity), box, false);
        return Optional.ofNullable(support.best);
    }

    private static ClassValue<Boolean> vanilla(String method) {
        return new ClassValue<>() {
            @Override
            protected Boolean computeValue(Class<?> type) {
                try {
                    return type.getMethod(method, Entity.class, AABB.class).getDeclaringClass() == CollisionGetter.class;
                } catch (NoSuchMethodException missing) {
                    return false;
                }
            }
        };
    }

    /**
     * The {@code BlockCollisions} cursor unrolled: x fastest, then y, then z, with its edge and shape filters. With
     * {@code rims}, the ring cells of a section {@link BlazeRimSections} clears are passed over unread.
     */
    private abstract static class Walk {
        abstract void hit(BlockPos.MutableBlockPos pos, VoxelShape placed);

        final void walk(Level level, CollisionContext context, AABB swept, boolean rims) {
            int minX = Mth.floor(swept.minX - EPSILON) - 1, maxX = Mth.floor(swept.maxX + EPSILON) + 1;
            int minY = Mth.floor(swept.minY - EPSILON) - 1, maxY = Mth.floor(swept.maxY + EPSILON) + 1;
            int minZ = Mth.floor(swept.minZ - EPSILON) - 1, maxZ = Mth.floor(swept.maxZ + EPSILON) + 1;
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            VoxelShape sweptShape = null;
            BlockGetter chunk = null;
            LevelChunkSection[] sections = null;
            int chunkX = 0, chunkZ = 0, minSection = 0;
            boolean cached = false, direct = SECTIONS && !level.isDebug();
            LevelChunkSection rimSection = null;
            boolean rimReach = true;
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
                            sections = direct && chunk != null && chunk.getClass() == LevelChunk.class ? ((LevelChunk) chunk).getSections() : null;
                            if (sections != null) minSection = ((LevelChunk) chunk).getMinSection();
                        }
                        if (chunk == null) continue;
                        pos.set(x, y, z);
                        BlockState state;
                        if (sections != null) {
                            // LevelChunk.getBlockState answers air here, and air passes none of the filters below.
                            int index = (y >> 4) - minSection;
                            if (index < 0 || index >= sections.length) continue;
                            LevelChunkSection section = sections[index];
                            if (section.hasOnlyAir()) continue;
                            if (rims && edges != 0) {
                                if (section != rimSection) {
                                    rimSection = section;
                                    rimReach = BlazeRimSections.mayReach(level, section);
                                }
                                if (!rimReach) continue;
                            }
                            state = section.getBlockState(x & 15, y & 15, z & 15);
                        } else state = chunk.getBlockState(pos);
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
                        hit(pos, placed);
                    }
                }
            }
        }
    }

    private static final class Gather extends Walk {
        List<VoxelShape> shapes = List.of();

        @Override
        void hit(BlockPos.MutableBlockPos pos, VoxelShape placed) {
            if (shapes.isEmpty()) shapes = new ArrayList<>(4);
            shapes.add(placed);
        }
    }

    private static final class Support extends Walk {
        private final Vec3 from;
        BlockPos best;
        private double bestDistance = Double.MAX_VALUE;

        Support(Vec3 from) {
            this.from = from;
        }

        @Override
        void hit(BlockPos.MutableBlockPos pos, VoxelShape placed) {
            double distance = pos.distToCenterSqr(from);
            if (distance < bestDistance || distance == bestDistance && (best == null || best.compareTo(pos) < 0)) {
                best = pos.immutable();
                bestDistance = distance;
            }
        }
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
