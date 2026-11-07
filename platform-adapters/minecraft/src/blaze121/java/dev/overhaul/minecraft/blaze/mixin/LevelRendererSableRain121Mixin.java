package dev.overhaul.minecraft.blaze.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.overhaul.minecraft.blaze.BlazeSableLevels;
import dev.overhaul.minecraft.blaze.RainHeightCheck;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Sable raises every rain column's height to the top of any sub-level over it, a world query with five allocations per
 * column per frame. With no sub-level loaded its answer is vanilla's, so that case asks vanilla directly. Wraps outside
 * Sable's wrapper (higher priority applies later, so it lands outermost) and only while no other wrapper is on the call.
 */
@Mixin(value = LevelRenderer.class, priority = 1500)
abstract class LevelRendererSableRain121Mixin {
    @Unique private static final boolean overhaul$ready = BlazeSableLevels.ready() && RainHeightCheck.clean();

    @WrapOperation(method = "renderSnowAndRain", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getHeight(Lnet/minecraft/world/level/levelgen/Heightmap$Types;II)I"),
            require = 0)
    private int overhaul$skipEmptySable(Level level, Heightmap.Types types, int x, int z, Operation<Integer> original) {
        if (overhaul$ready && BlazeSableLevels.empty(level)) return level.getHeight(types, x, z);
        return original.call(level, types, x, z);
    }
}
