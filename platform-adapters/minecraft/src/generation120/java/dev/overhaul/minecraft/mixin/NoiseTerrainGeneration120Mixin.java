package dev.overhaul.minecraft.mixin;

import dev.overhaul.minecraft.world.GenerationStateAccess;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Retains vanilla's section acquisition and whenCompleteAsync release on the priority lane. */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseTerrainGeneration120Mixin {
    @Redirect(method = "fillFromNoise(Ljava/util/concurrent/Executor;Lnet/minecraft/world/level/levelgen/blending/Blender;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/chunk/ChunkAccess;)Ljava/util/concurrent/CompletableFuture;",
            at = @At(value = "INVOKE", target = "Ljava/util/concurrent/CompletableFuture;supplyAsync(Ljava/util/function/Supplier;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;", remap = false),
            require = 1, allow = 1)
    private CompletableFuture<ChunkAccess> overhaul$terrain(Supplier<ChunkAccess> supplier, Executor original,
            Executor priorityExecutor, Blender blender, RandomState state, StructureManager structures, ChunkAccess chunk) {
        Executor executor = ((GenerationStateAccess) (Object) state)
                .overhaul$generationExecutor("buildTerrain", original);
        return CompletableFuture.supplyAsync(supplier, executor);
    }
}
