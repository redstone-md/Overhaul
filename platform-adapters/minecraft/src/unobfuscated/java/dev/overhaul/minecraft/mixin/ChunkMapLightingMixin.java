package dev.overhaul.minecraft.mixin;

import dev.overhaul.minecraft.world.ChunkLightAccess;
import dev.overhaul.minecraft.world.ChunkLightingExecutor;
import java.util.concurrent.Executor;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.thread.ConsecutiveExecutor;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Owns backing workers while retaining the original engine's serial and priority queues. */
@Mixin(ChunkMap.class)
public abstract class ChunkMapLightingMixin {
    @Shadow @Final private ServerLevel level;
    @Unique private ChunkLightingExecutor.Strand overhaul$lightStrand;

    // Pinned 26.3 assigns level at bytecode 130, before the second NEW at 328.
    // Factory redirect returns the same vanilla class, with both constructor
    // arguments preserved except for its backing execution policy.
    @Redirect(method = "<init>", at = @At(value = "NEW",
            target = "net/minecraft/util/thread/ConsecutiveExecutor", ordinal = 1), require = 1, allow = 1)
    private ConsecutiveExecutor overhaul$lightExecutor(Executor vanillaExecutor, String name) {
        if (!"light".equals(name)) throw new IllegalStateException("Expected the Minecraft light strand");
        overhaul$lightStrand = ((ChunkLightAccess) level.getServer()).overhaul$createLightStrand(vanillaExecutor, name);
        return overhaul$lightStrand.executor();
    }

    @Inject(method = "close()V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ChunkTaskDispatcher;close()V",
            ordinal = 1, shift = At.Shift.AFTER), require = 1, allow = 1)
    private void overhaul$lightClosed(CallbackInfo callback) {
        // The light dispatcher has closed this exact ConsecutiveExecutor; drain
        // its backing wakeups before ChunkMap proceeds to PoiManager.close().
        if (overhaul$lightStrand != null) overhaul$lightStrand.close();
    }
}
