package dev.overhaul.minecraft.blaze;

import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.ai.goal.MoveToBlockGoal;
import net.minecraft.world.entity.ai.goal.RemoveBlockGoal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * A mob that breaks one kind of block, like a zombie after turtle eggs, walks up to 49 by 49 columns of a few blocks
 * every time its goal comes up, asking each block whether it is that kind. A target must be that block in a loaded
 * full chunk, so when no loaded section the walk covers has the block in its palette the walk cannot find one, and
 * {@link #mayFind} answers that from a few palettes.
 */
public final class BlazeBlockSearch {
    private static final ClassValue<Boolean> VANILLA = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            return declarer(type, "isValidTarget", LevelReader.class, BlockPos.class) == RemoveBlockGoal.class
                    && declarer(type, "findNearestBlock") == MoveToBlockGoal.class;
        }
    };

    private BlazeBlockSearch() {}

    /** Whether the goal finds its target as {@code RemoveBlockGoal} does. */
    public static boolean vanilla(MoveToBlockGoal goal) {
        return VANILLA.get(goal.getClass());
    }

    /**
     * Whether any loaded full chunk section within the search box around {@code center} may hold {@code block}: the
     * box spans {@code range - 1} blocks each way across and {@code below} under to {@code above} over.
     */
    public static boolean mayFind(Level level, BlockPos center, int range, int below, int above, Block block) {
        int reach = range - 1;
        int minSectionX = SectionPos.blockToSectionCoord(center.getX() - reach), maxSectionX = SectionPos.blockToSectionCoord(center.getX() + reach);
        int minSectionZ = SectionPos.blockToSectionCoord(center.getZ() - reach), maxSectionZ = SectionPos.blockToSectionCoord(center.getZ() + reach);
        int minY = center.getY() - below, maxY = center.getY() + above;
        Predicate<BlockState> wanted = state -> state.is(block);
        for (int sectionZ = minSectionZ; sectionZ <= maxSectionZ; sectionZ++) {
            for (int sectionX = minSectionX; sectionX <= maxSectionX; sectionX++) {
                ChunkAccess chunk = level.getChunk(sectionX, sectionZ, ChunkStatus.FULL, false);
                if (chunk == null) continue;
                LevelChunkSection[] sections = chunk.getSections();
                int first = Math.max(0, chunk.getSectionIndex(minY)), last = Math.min(sections.length - 1, chunk.getSectionIndex(maxY));
                for (int index = first; index <= last; index++) {
                    LevelChunkSection section = sections[index];
                    if (!section.hasOnlyAir() && section.maybeHas(wanted)) return true;
                }
            }
        }
        return false;
    }

    private static Class<?> declarer(Class<?> type, String name, Class<?>... parameters) {
        for (Class<?> at = type; at != null; at = at.getSuperclass()) {
            try {
                at.getDeclaredMethod(name, parameters);
                return at;
            } catch (NoSuchMethodException missing) {
                // Not declared here; look further up.
            }
        }
        return null;
    }
}
