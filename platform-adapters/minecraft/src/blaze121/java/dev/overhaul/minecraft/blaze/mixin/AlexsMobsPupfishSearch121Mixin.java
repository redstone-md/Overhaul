package dev.overhaul.minecraft.blaze.mixin;

import java.util.concurrent.CompletableFuture;
import net.minecraft.Util;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Alex's Mobs looks for the one chunk pupfish may spawn in every ten ticks, for up to five minutes after a world opens,
 * and each try samples the world generator's noise down a whole column on the server thread. A try reads only the
 * seed, the generator and its own counter, so it runs on a background thread instead, one at a time: the tries come in
 * the same order and the first chunk that fits is still the one kept. While a try is running the next one waits.
 */
@Pseudo
@Mixin(targets = "com.github.alexthe666.alexsmobs.world.AMWorldData", remap = false)
abstract class AlexsMobsPupfishSearch121Mixin {
    @Shadow private ServerLevel level;
    @Unique private CompletableFuture<Void> overhaul$search;

    @Shadow
    private void searchForPupfishChunk() {}

    @Inject(method = "searchForPupfishChunk()V", at = @At("HEAD"), cancellable = true, require = 0)
    private void overhaul$searchOffThread(CallbackInfo callback) {
        ServerLevel world = level;
        if (world == null || !world.getServer().isSameThread()) return;
        callback.cancel();
        CompletableFuture<Void> running = overhaul$search;
        if (running != null && !running.isDone()) return;
        overhaul$search = CompletableFuture.runAsync(() -> searchForPupfishChunk(), Util.backgroundExecutor());
    }
}
