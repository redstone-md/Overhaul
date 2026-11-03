package dev.overhaul.minecraft.mixin;

import dev.overhaul.minecraft.world.ChunkGenerationAccess;
import dev.overhaul.minecraft.world.ChunkGenerationExecutor;
import dev.overhaul.minecraft.world.GenerationStateAccess;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkTaskPriorityQueueSorter;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.util.thread.BlockableEventLoop;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The world keeps its own generation inputs and the original priority dependency queue. */
@Mixin(ChunkMap.class)
public abstract class ChunkMapMappedGenerationMixin {
    @Shadow @Final private ServerLevel level;
    @Shadow @Final private RandomState randomState;
    @Shadow @Final private ChunkTaskPriorityQueueSorter queueSorter;
    @Shadow @Final private BlockableEventLoop<Runnable> mainThreadExecutor;
    @Unique private ChunkGenerationExecutor.World overhaul$generationWorld;

    @Inject(method = "<init>", at = @At("RETURN"), require = 1, allow = 1)
    private void overhaul$bindGeneration(CallbackInfo callback) {
        overhaul$generationWorld = ((ChunkGenerationAccess) level.getServer())
                .overhaul$createGenerationWorld((GenerationStateAccess) (Object) randomState, queueSorter::close,
                        mainThreadExecutor::managedBlock);
    }

    @Redirect(method = "close()V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ChunkTaskPriorityQueueSorter;close()V"), require = 1, allow = 1)
    private void overhaul$closeGeneration(ChunkTaskPriorityQueueSorter original) {
        if (overhaul$generationWorld == null) original.close();
        else overhaul$generationWorld.close();
    }
}
