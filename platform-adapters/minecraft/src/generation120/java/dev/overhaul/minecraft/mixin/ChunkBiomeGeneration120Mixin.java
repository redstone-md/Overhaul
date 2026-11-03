package dev.overhaul.minecraft.mixin;

import dev.overhaul.minecraft.world.GenerationStateAccess;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Changes only computation dispatch; the supplied priority executor keeps its original role. */
@Mixin({ChunkGenerator.class, NoiseBasedChunkGenerator.class})
public abstract class ChunkBiomeGeneration120Mixin {
    @Redirect(method = "createBiomes(Ljava/util/concurrent/Executor;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/levelgen/blending/Blender;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/chunk/ChunkAccess;)Ljava/util/concurrent/CompletableFuture;",
            at = @At(value = "INVOKE", target = "Ljava/util/concurrent/CompletableFuture;supplyAsync(Ljava/util/function/Supplier;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;", remap = false),
            require = 1, allow = 1)
    private CompletableFuture<ChunkAccess> overhaul$biomes(Supplier<ChunkAccess> supplier, Executor original,
            Executor priorityExecutor, RandomState state, Blender blender, StructureManager structures, ChunkAccess chunk) {
        Executor executor = ((GenerationStateAccess) (Object) state)
                .overhaul$generationExecutor("createBiomes", original);
        return CompletableFuture.supplyAsync(supplier, executor);
    }
}
