package dev.overhaul.minecraft.mixin;

import dev.overhaul.minecraft.world.ChunkParseAccess;
import java.util.concurrent.Executor;
import net.minecraft.TracingExecutor;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Redirects the existing parse stage without replacing its future or publication. */
@Mixin(ChunkMap.class)
public abstract class ChunkMapParseMixin {
    @Shadow @Final private ServerLevel level;

    @Redirect(method = "scheduleChunkLoad(Lnet/minecraft/world/level/ChunkPos;)Ljava/util/concurrent/CompletableFuture;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/TracingExecutor;forName(Ljava/lang/String;)Ljava/util/concurrent/Executor;"),
            require = 1, allow = 1)
    private Executor overhaul$parseExecutor(TracingExecutor original, String name) {
        Executor vanilla = original.forName(name);
        return ((ChunkParseAccess) level.getServer()).overhaul$chunkParseExecutor(vanilla);
    }
}
