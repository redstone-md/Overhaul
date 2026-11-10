package dev.overhaul.minecraft.blaze.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Distant Horizons blends every tinted LOD column over a square of neighbouring biomes, and each sample resolves its
 * block and biome to a pair through two nested hash maps before looking the pair's colour up in a third. Neighbours
 * almost always share one biome, so the render loader threads repeat the same three lookups dozens of times per
 * column. Each tint getter is thread-local, so it remembers the colour it last resolved for its block and biome
 * without locking. A new block forgets it, and a generation counter bumped by DH's cache clear drops it after a
 * resource reload.
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.common.wrappers.block.AbstractDhTintGetter_neoforge", remap = false)
abstract class DhTintPairCache121Mixin {
    @Unique private static volatile int overhaul$generation;

    @Unique private Object overhaul$block;
    @Unique private Object overhaul$biome;
    @Unique private Integer overhaul$color;
    @Unique private int overhaul$colorGeneration;

    @Inject(method = "update", require = 0, at = @At("HEAD"))
    private void overhaul$retarget(@Coerce Object biome, @Coerce Object block, @Coerce Object data, @Coerce Object level, CallbackInfo callback) {
        if (block != overhaul$block) {
            overhaul$block = block;
            overhaul$color = null;
        }
    }

    @Inject(method = "tryGetClientBiomeColor", require = 0, cancellable = true, at = @At("HEAD"))
    private void overhaul$remembered(@Coerce Object resolver, @Coerce Object biome, CallbackInfoReturnable<Integer> callback) {
        Integer color = overhaul$color;
        if (color != null && biome == overhaul$biome && overhaul$colorGeneration == overhaul$generation) {
            callback.setReturnValue(color);
        }
    }

    @Inject(method = "tryGetClientBiomeColor", require = 0, at = @At("RETURN"))
    private void overhaul$remember(@Coerce Object resolver, @Coerce Object biome, CallbackInfoReturnable<Integer> callback) {
        int color = callback.getReturnValueI();
        if (color == -1) return;
        overhaul$biome = biome;
        overhaul$color = color;
        overhaul$colorGeneration = overhaul$generation;
    }

    @Inject(method = "clear", require = 0, at = @At("HEAD"))
    private static void overhaul$forget(CallbackInfo callback) {
        overhaul$generation++;
    }
}
