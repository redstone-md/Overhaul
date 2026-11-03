package dev.overhaul.minecraft.mixin;

import dev.overhaul.minecraft.world.ChunkLightAccess;
import dev.overhaul.minecraft.world.ChunkLightingExecutor;
import java.util.concurrent.Executor;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.thread.ProcessorMailbox;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Original mailbox and chunk priority ordering remain authoritative for every PRE/POST task. */
@Mixin(ChunkMap.class)
public abstract class ChunkMapMappedLightingMixin {
    @Shadow @Final private ServerLevel level;
    @Unique private ChunkLightingExecutor.Strand<ProcessorMailbox<Runnable>> overhaul$lightStrand;

    @Redirect(method = "<init>", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/util/thread/ProcessorMailbox;create(Ljava/util/concurrent/Executor;Ljava/lang/String;)Lnet/minecraft/util/thread/ProcessorMailbox;",
            ordinal = 1), require = 1, allow = 1)
    private ProcessorMailbox<Runnable> overhaul$lightExecutor(Executor vanillaExecutor, String name) {
        if (!"light".equals(name)) throw new IllegalStateException("Expected the Minecraft light mailbox");
        overhaul$lightStrand = ((ChunkLightAccess) level.getServer()).overhaul$createLightStrand(
                vanillaExecutor, name, ProcessorMailbox::create, ProcessorMailbox::close);
        return overhaul$lightStrand.executor();
    }

    @Inject(method = "close()V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/ai/village/poi/PoiManager;close()V"), require = 1, allow = 1)
    private void overhaul$lightClosed(CallbackInfo callback) {
        // Generation's original-future drain has completed, and queueSorter.close
        // has closed this exact mailbox. Join only its backing wakeups before IO closes.
        if (overhaul$lightStrand != null) overhaul$lightStrand.close();
    }
}
