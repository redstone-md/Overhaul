package dev.overhaul.minecraft.blaze;

/**
 * A byte buffer that can hand out room ahead of what was asked for. The unused tail stays counted as written until the
 * buffer does anything else, when it is handed back, so a run of small writes pays for one reservation.
 */
public interface BlazeBufferSlack {
    /** Read once per particle quad; a plain field so a measurement can flip it in a running game. */
    final class Switch {
        public static boolean on = BlazeOptimization.PARTICLE_RESERVE.on();

        private Switch() {}
    }

    /** Address of {@code bytes} bytes, reserving {@code ahead} more for the next calls when the slack runs out. */
    long overhaul$reserveAhead(int bytes, int ahead);
}
