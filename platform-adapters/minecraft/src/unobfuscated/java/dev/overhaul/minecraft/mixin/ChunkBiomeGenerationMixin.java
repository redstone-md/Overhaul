package dev.overhaul.minecraft.mixin;

import dev.overhaul.minecraft.world.GenerationStateAccess;
import java.util.concurrent.Executor;
import net.minecraft.TracingExecutor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Selects execution for the game's unchanged asynchronous biome supplier. */
@Mixin(ChunkGenerator.class)
public abstract class ChunkBiomeGenerationMixin {
    @Redirect(method = "createBiomes(Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/levelgen/blending/Blender;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/chunk/ChunkAccess;)Ljava/util/concurrent/CompletableFuture;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/TracingExecutor;forName(Ljava/lang/String;)Ljava/util/concurrent/Executor;"),
            require = 1, allow = 1)
    private Executor overhaul$biomeExecutor(TracingExecutor original, String name, RandomState randomState,
            Blender blender, StructureManager structures, ChunkAccess chunk) {
        return ((GenerationStateAccess) (Object) randomState).overhaul$generationExecutor(name, original.forName(name));
    }
}
