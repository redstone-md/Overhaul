package dev.overhaul.minecraft.mixin;

import dev.overhaul.minecraft.world.ChunkGenerationAccess;
import dev.overhaul.minecraft.world.ChunkGenerationExecutor;
import dev.overhaul.minecraft.world.GenerationStateAccess;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkTaskDispatcher;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import net.minecraft.world.level.levelgen.RandomState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Associates actual generation inputs at construction, not a world lookup from worker code. */
@Mixin(ChunkMap.class)
public abstract class ChunkMapGenerationMixin {
    @Shadow @Final private RandomState randomState;
    @Shadow @Final private WorldGenContext worldGenContext;
    @Shadow @Final private ChunkTaskDispatcher worldgenTaskDispatcher;
    @Unique private ChunkGenerationExecutor.World overhaul$generationWorld;

    @Inject(method = "<init>", at = @At("RETURN"), require = 1, allow = 1)
    private void overhaul$bindGeneration(CallbackInfo callback) {
        // WorldGenContext is assigned at bytecode 505, immediately before return.
        // ServerLevel's chunk source is still constructing, so never look it up here.
        overhaul$generationWorld = ((ChunkGenerationAccess) worldGenContext.level().getServer())
                .overhaul$createGenerationWorld((GenerationStateAccess) (Object) randomState,
                        worldgenTaskDispatcher::close);
    }

    @Inject(method = "close()V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ChunkTaskDispatcher;close()V",
            ordinal = 0, shift = At.Shift.AFTER), require = 1, allow = 1)
    private void overhaul$generationClosed(CallbackInfo callback) {
        if (overhaul$generationWorld != null) overhaul$generationWorld.close();
    }
}
