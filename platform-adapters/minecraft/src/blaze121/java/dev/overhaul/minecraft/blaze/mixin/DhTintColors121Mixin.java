package dev.overhaul.minecraft.blaze.mixin;

import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalIntRef;
import com.seibel.distanthorizons.common.wrappers.block.BiomeWrapper_neoforge;
import com.seibel.distanthorizons.common.wrappers.block.BlockStateWrapper_neoforge;
import dev.overhaul.minecraft.blaze.BlazeDhTints;
import net.minecraft.world.level.ColorResolver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Every tinted face of a LOD asks Distant Horizons' shared colour cache for its block and biome pair. The colour a
 * thread last got from that cache for the same wrappers is handed back first (see {@link BlazeDhTints}).
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.common.wrappers.block.AbstractDhTintGetter_neoforge", remap = false)
abstract class DhTintColors121Mixin {
    private static final String LOOKUP = "tryGetClientBiomeColor(Lnet/minecraft/world/level/ColorResolver;Lcom/seibel/distanthorizons/common/wrappers/block/BiomeWrapper_neoforge;)I";

    @Shadow protected BlockStateWrapper_neoforge blockStateWrapper;

    @Inject(method = LOOKUP, at = @At("HEAD"), cancellable = true, require = 0)
    private void overhaul$kept(ColorResolver resolver, BiomeWrapper_neoforge biome, CallbackInfoReturnable<Integer> callback, @Share("generation") LocalIntRef generation) {
        int current = BlazeDhTints.generation();
        generation.set(current);
        BlazeDhTints tints = BlazeDhTints.current();
        if (tints.find(blockStateWrapper, biome, current)) callback.setReturnValue(tints.color());
    }

    /** With a resolver the colour returned is the one the shared cache now holds; without one nothing was cached. */
    @Inject(method = LOOKUP, at = @At("RETURN"), require = 0)
    private void overhaul$keep(ColorResolver resolver, BiomeWrapper_neoforge biome, CallbackInfoReturnable<Integer> callback, @Share("generation") LocalIntRef generation) {
        if (resolver != null) BlazeDhTints.current().keep(blockStateWrapper, biome, generation.get(), callback.getReturnValueI());
    }

    @Inject(method = {"clear", "setStaticColor"}, at = @At("HEAD"), require = 0)
    private static void overhaul$changed(CallbackInfo callback) {
        BlazeDhTints.invalidate();
    }
}
