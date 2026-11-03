package dev.overhaul.minecraft.mixin;

import dev.overhaul.minecraft.world.GenerationStateAccess;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Admission surrounds actual factory invocations, before locks and through final release futures. */
@Mixin(ChunkStatus.class)
public abstract class ChunkStatusGeneration120Mixin {
    // The exact invocation selects one static factory lambda independently of its synthetic name.
    @Redirect(method = "*", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/ChunkGenerator;fillFromNoise(Ljava/util/concurrent/Executor;Lnet/minecraft/world/level/levelgen/blending/Blender;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/chunk/ChunkAccess;)Ljava/util/concurrent/CompletableFuture;"),
            require = 1, allow = 1)
    private static CompletableFuture<ChunkAccess> overhaul$noiseInvocation(ChunkGenerator generator, Executor executor,
            Blender blender, RandomState state, StructureManager structures, ChunkAccess chunk) {
        return ((GenerationStateAccess) (Object) state).overhaul$generationInvocation(
                () -> generator.fillFromNoise(executor, blender, state, structures, chunk));
    }

    @Redirect(method = "*", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/ChunkGenerator;createBiomes(Ljava/util/concurrent/Executor;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/levelgen/blending/Blender;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/chunk/ChunkAccess;)Ljava/util/concurrent/CompletableFuture;"),
            require = 1, allow = 1)
    private static CompletableFuture<ChunkAccess> overhaul$biomeInvocation(ChunkGenerator generator, Executor executor,
            RandomState state, Blender blender, StructureManager structures, ChunkAccess chunk) {
        return ((GenerationStateAccess) (Object) state).overhaul$generationInvocation(
                () -> generator.createBiomes(executor, state, blender, structures, chunk));
    }
}
