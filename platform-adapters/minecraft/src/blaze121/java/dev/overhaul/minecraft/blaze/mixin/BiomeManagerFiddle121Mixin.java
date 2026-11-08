package dev.overhaul.minecraft.blaze.mixin;

import dev.overhaul.minecraft.blaze.BlazeBiomeFiddle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.LinearCongruentialGenerator;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * A block's biome is read from the nearest of the eight quart corners around it, each pushed by a seeded jitter that
 * costs six random steps to work out. Biome tinting asks this for every block in a 5x5 square around each tinted
 * block, so while a section is meshed the same corners are worked out over and over. The jitter depends only on the
 * seed and the corner, so it is kept per thread (see {@link BlazeBiomeFiddle}); distances are summed in the game's
 * order and the biome at the picked corner is still read every time, so the answer is the game's to the bit.
 */
@Mixin(BiomeManager.class)
abstract class BiomeManagerFiddle121Mixin {
    @Shadow @Final private BiomeManager.NoiseBiomeSource noiseBiomeSource;
    @Shadow @Final private long biomeZoomSeed;

    @Shadow
    private static double getFiddle(long seed) {
        throw new AssertionError();
    }

    /**
     * @author Overhaul
     * @reason Keeps the corner jitter, which depends only on the seed and the corner, instead of working it out again.
     */
    @Overwrite
    public Holder<Biome> getBiome(BlockPos pos) {
        int x = pos.getX() - 2, y = pos.getY() - 2, z = pos.getZ() - 2;
        int quartX = x >> 2, quartY = y >> 2, quartZ = z >> 2;
        double fracX = (x & 3) / 4.0, fracY = (y & 3) / 4.0, fracZ = (z & 3) / 4.0;
        BlazeBiomeFiddle kept = BlazeBiomeFiddle.current();
        double[] jitter = kept.jitter();
        int start = kept.find(biomeZoomSeed, quartX, quartY, quartZ);
        if (start < 0) {
            start = ~start;
            for (int corner = 0; corner < 8; corner++) {
                overhaul$jitter((corner & 4) == 0 ? quartX : quartX + 1, (corner & 2) == 0 ? quartY : quartY + 1,
                        (corner & 1) == 0 ? quartZ : quartZ + 1, jitter, start + corner * 3);
            }
        }
        int best = 0;
        double nearest = Double.POSITIVE_INFINITY;
        for (int corner = 0, at = start; corner < 8; corner++, at += 3) {
            double noiseX = (corner & 4) == 0 ? fracX : fracX - 1.0;
            double noiseY = (corner & 2) == 0 ? fracY : fracY - 1.0;
            double noiseZ = (corner & 1) == 0 ? fracZ : fracZ - 1.0;
            double distance = Mth.square(noiseZ + jitter[at + 2]) + Mth.square(noiseY + jitter[at + 1]) + Mth.square(noiseX + jitter[at]);
            if (nearest > distance) {
                best = corner;
                nearest = distance;
            }
        }
        return noiseBiomeSource.getNoiseBiome((best & 4) == 0 ? quartX : quartX + 1,
                (best & 2) == 0 ? quartY : quartY + 1, (best & 1) == 0 ? quartZ : quartZ + 1);
    }

    /** The game's jitter of one corner, x then y then z, from the same random steps as its fiddled distance. */
    @Unique
    private void overhaul$jitter(int x, int y, int z, double[] into, int at) {
        long seed = biomeZoomSeed;
        long state = LinearCongruentialGenerator.next(seed, x);
        state = LinearCongruentialGenerator.next(state, y);
        state = LinearCongruentialGenerator.next(state, z);
        state = LinearCongruentialGenerator.next(state, x);
        state = LinearCongruentialGenerator.next(state, y);
        state = LinearCongruentialGenerator.next(state, z);
        into[at] = getFiddle(state);
        state = LinearCongruentialGenerator.next(state, seed);
        into[at + 1] = getFiddle(state);
        state = LinearCongruentialGenerator.next(state, seed);
        into[at + 2] = getFiddle(state);
    }
}
