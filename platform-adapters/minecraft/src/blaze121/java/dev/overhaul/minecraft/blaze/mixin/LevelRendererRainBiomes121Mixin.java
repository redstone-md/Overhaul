package dev.overhaul.minecraft.blaze.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.overhaul.minecraft.blaze.BlazeRainColumns;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Rain and snow ask the blended biome of every column within ten blocks of the camera, every frame, and each answer
 * blends eight biome cells. A block's biome holds still, so each column's answer is kept for the client tick.
 */
@Mixin(LevelRenderer.class)
abstract class LevelRendererRainBiomes121Mixin {
    @Unique private final BlazeRainColumns overhaul$biomeColumns = new BlazeRainColumns();
    @SuppressWarnings("unchecked")
    @Unique private final Holder<Biome>[] overhaul$biomes = new Holder[32 * 32];

    @WrapOperation(method = "renderSnowAndRain", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getBiome(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/Holder;"),
            require = 0)
    private Holder<Biome> overhaul$tickBiome(Level level, BlockPos pos, Operation<Holder<Biome>> biome) {
        BlazeRainColumns columns = overhaul$biomeColumns;
        if (columns.fresh(level, pos)) return overhaul$biomes[columns.slot()];
        Holder<Biome> answer = biome.call(level, pos);
        overhaul$biomes[columns.slot()] = answer;
        return answer;
    }
}
