package dev.overhaul.minecraft.blaze.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.lang.ref.WeakReference;
import java.util.Arrays;
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
 * blends eight biome cells. A block's biome holds still, so each column's answer is kept for the client tick in a
 * 32 by 32 table: the 21 columns of a row never share a slot, and a changed camera height or level asks again.
 */
@Mixin(LevelRenderer.class)
abstract class LevelRendererRainBiomes121Mixin {
    @Unique private final int[] overhaul$keys = new int[32 * 32 * 3];
    @Unique private final long[] overhaul$stamps = new long[32 * 32];
    @SuppressWarnings("unchecked")
    @Unique private final Holder<Biome>[] overhaul$biomes = new Holder[32 * 32];
    /** Weak, so a level the player left is not kept alive by the table. */
    @Unique private WeakReference<Level> overhaul$level = new WeakReference<>(null);

    @WrapOperation(method = "renderSnowAndRain", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getBiome(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/Holder;"),
            require = 0)
    private Holder<Biome> overhaul$tickBiome(Level level, BlockPos pos, Operation<Holder<Biome>> biome) {
        if (overhaul$level.get() != level) {
            overhaul$level = new WeakReference<>(level);
            Arrays.fill(overhaul$stamps, Long.MIN_VALUE);
            Arrays.fill(overhaul$biomes, null);
        }
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        int slot = (x & 31) << 5 | z & 31;
        long now = level.getGameTime();
        int[] keys = overhaul$keys;
        int key = slot * 3;
        if (overhaul$stamps[slot] == now && keys[key] == x && keys[key + 1] == y && keys[key + 2] == z) return overhaul$biomes[slot];
        Holder<Biome> answer = biome.call(level, pos);
        keys[key] = x;
        keys[key + 1] = y;
        keys[key + 2] = z;
        overhaul$stamps[slot] = now;
        overhaul$biomes[slot] = answer;
        return answer;
    }
}
