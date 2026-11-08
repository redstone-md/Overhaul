package dev.overhaul.minecraft.blaze;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Whether a chunk section may hold a block that a collision walk has to look at outside the box it sweeps: one whose
 * collision shape reaches past its cell, such as a fence or a wall, or a moving piston. A section whose palette holds
 * neither lets a particle read only the cells its box touches instead of the ring of cells around them.
 *
 * <p>Each section keeps its answer for one tick of its level, and only the thread that first asked, the one ticking
 * particles, reads or writes it. A block placed during the tick is seen on the next one, which for a particle is one tick
 * of a drop passing a fence edge.
 */
public final class BlazeRimSections {
    /** Mixed into {@link LevelChunkSection}: the tick an answer was found on, plus one, and the answer. */
    public interface Rim {
        long overhaul$rimTick();

        boolean overhaul$rimReach();

        void overhaul$rim(long tick, boolean reach);
    }

    /** Read on every particle walk; a plain field so a measurement can flip it in a running game. */
    static boolean on = BlazeOptimization.PARTICLE_RIMS.on();
    private static volatile Thread owner;

    private BlazeRimSections() {}

    /** True when the walk must test the ring cells of this section; also true on any thread but the particle one. */
    static boolean mayReach(Level level, LevelChunkSection section) {
        Thread thread = Thread.currentThread();
        if (owner != thread) {
            if (owner != null) return true;
            owner = thread;
        }
        if (!(section instanceof Rim rim)) return true;
        long stamp = level.getGameTime() + 1;
        if (rim.overhaul$rimTick() == stamp) return rim.overhaul$rimReach();
        boolean reach = section.maybeHas(BlazeRimSections::reaches);
        rim.overhaul$rim(stamp, reach);
        return reach;
    }

    private static boolean reaches(BlockState state) {
        return state.hasLargeCollisionShape() || state.is(Blocks.MOVING_PISTON);
    }
}
