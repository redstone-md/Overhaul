package dev.overhaul.minecraft.mixin;

import dev.overhaul.minecraft.world.GenerationStateAccess;
import java.util.Set;
import java.util.concurrent.Executor;
import net.minecraft.TracingExecutor;
import net.minecraft.core.Holder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** 26.3 combines noise fill, surface and carving inside the original buildTerrain supplier. */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseTerrainGenerationMixin {
    @Redirect(method = "buildTerrain(Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/levelgen/blending/Blender;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/biome/BiomeManager;Lnet/minecraft/server/level/WorldGenRegion;Ljava/util/Set;)Ljava/util/concurrent/CompletableFuture;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/TracingExecutor;forName(Ljava/lang/String;)Ljava/util/concurrent/Executor;"),
            require = 1, allow = 1)
    private Executor overhaul$terrainExecutor(TracingExecutor original, String name, ChunkAccess chunk,
            Blender blender, RandomState randomState, StructureManager structures, BiomeManager biomes,
            WorldGenRegion region, Set<Holder<Biome>> possibleBiomes) {
        return ((GenerationStateAccess) (Object) randomState).overhaul$generationExecutor(name, original.forName(name));
    }
}
