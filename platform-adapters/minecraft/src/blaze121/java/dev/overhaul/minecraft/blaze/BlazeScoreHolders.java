package dev.overhaul.minecraft.blaze;

import java.util.Set;
import net.minecraft.world.scores.Objective;

/** A scoreboard that knows, for each objective, the names of the holders that may have a score in it. */
public interface BlazeScoreHolders {
    /** Every holder with a score in the objective, and maybe a few more, or null when none was ever given one. */
    Set<String> overhaul$holders(Objective objective);
}
