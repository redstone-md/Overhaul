package dev.overhaul.minecraft.blaze.mixin;

import dev.overhaul.minecraft.blaze.BlazeRimSections;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Room on each chunk section for {@link BlazeRimSections}' answer of the current tick. */
@Mixin(LevelChunkSection.class)
abstract class SectionRim121Mixin implements BlazeRimSections.Rim {
    @Unique private long overhaul$rimTick;
    @Unique private boolean overhaul$rimReach;

    @Override
    public long overhaul$rimTick() {
        return overhaul$rimTick;
    }

    @Override
    public boolean overhaul$rimReach() {
        return overhaul$rimReach;
    }

    @Override
    public void overhaul$rim(long tick, boolean reach) {
        overhaul$rimTick = tick;
        overhaul$rimReach = reach;
    }
}
