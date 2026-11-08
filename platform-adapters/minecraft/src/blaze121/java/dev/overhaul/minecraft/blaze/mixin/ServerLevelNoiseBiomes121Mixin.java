package dev.overhaul.minecraft.blaze.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.overhaul.minecraft.blaze.BlazeNoiseBiomes;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;

/**
 * A server level's noise biome is its generator's biome source sampled at one quart, a pure function of the quart.
 * World generation asks for it for every quart outside the chunks it holds, the same quarts many times over as
 * surface rules and biome blending walk a chunk's edge, so each thread hands back the biome it last sampled there
 * (see {@link BlazeNoiseBiomes}).
 */
@Mixin(ServerLevel.class)
abstract class ServerLevelNoiseBiomes121Mixin {
    @WrapMethod(method = "getUncachedNoiseBiome", require = 0)
    @SuppressWarnings("unchecked")
    private Holder<Biome> overhaul$kept(int x, int y, int z, Operation<Holder<Biome>> sample) {
        BlazeNoiseBiomes biomes = BlazeNoiseBiomes.current();
        if (biomes.find(this, x, y, z)) return (Holder<Biome>) biomes.biome();
        Holder<Biome> biome = sample.call(x, y, z);
        biomes.keep(this, x, y, z, biome);
        return biome;
    }
}
