package dev.overhaul.minecraft.blaze.mixin;

import net.minecraft.world.level.LevelHeightAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Alex's Mobs looks for its pupfish chunk by generating whole noise columns, top to bottom, to find the highest
 * water, and keeps a column only when that water tops out above y 31. Steps below that can only decide that a column
 * fails, which it does whether the water is found there or not at all, so the column now ends at the noise cell under
 * y 32. Noise cells sit at fixed heights, so every step still taken sees the same block as before.
 */
@Pseudo
@Mixin(targets = "com.github.alexthe666.alexsmobs.world.AMWorldData", remap = false)
abstract class AlexsMobsPupfishColumn121Mixin {
    /** The lowest water height the search accepts is 32; this is the start of the eight-block noise cell under it. */
    @Unique private static final int OVERHAUL$LOWEST_USEFUL_Y = 24;

    @ModifyArg(method = "getWaterHeight", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/NoiseBasedChunkGenerator;iterateNoiseColumn(Lnet/minecraft/world/level/LevelHeightAccessor;Lnet/minecraft/world/level/levelgen/RandomState;IILorg/apache/commons/lang3/mutable/MutableObject;Ljava/util/function/Predicate;)Ljava/util/OptionalInt;"),
            index = 0, require = 0)
    private LevelHeightAccessor overhaul$endColumnEarly(LevelHeightAccessor level) {
        int bottom = Math.max(level.getMinBuildHeight(), OVERHAUL$LOWEST_USEFUL_Y);
        int height = level.getMaxBuildHeight() - bottom;
        return height > 0 ? LevelHeightAccessor.create(bottom, height) : level;
    }
}