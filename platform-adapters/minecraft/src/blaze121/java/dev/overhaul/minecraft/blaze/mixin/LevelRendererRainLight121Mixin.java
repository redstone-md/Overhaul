package dev.overhaul.minecraft.blaze.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.overhaul.minecraft.blaze.BlazeRainColumns;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Rain and snow light every column around the camera every frame, each a sky and a block light lookup. Like particle
 * light, each column's light is sampled once per client tick and kept until the next.
 */
@Mixin(LevelRenderer.class)
abstract class LevelRendererRainLight121Mixin {
    @Unique private final BlazeRainColumns overhaul$lightColumns = new BlazeRainColumns();
    @Unique private final int[] overhaul$light = new int[32 * 32];

    @WrapOperation(method = "renderSnowAndRain", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;getLightColor(Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;)I"),
            require = 0)
    private int overhaul$tickLight(BlockAndTintGetter getter, BlockPos pos, Operation<Integer> light) {
        if (!(getter instanceof Level level)) return light.call(getter, pos);
        BlazeRainColumns columns = overhaul$lightColumns;
        if (columns.fresh(level, pos)) return overhaul$light[columns.slot()];
        int answer = light.call(getter, pos);
        overhaul$light[columns.slot()] = answer;
        return answer;
    }
}
