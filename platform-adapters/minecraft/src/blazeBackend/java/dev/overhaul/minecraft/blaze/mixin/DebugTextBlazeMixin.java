package dev.overhaul.minecraft.blaze.mixin;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The F3 lines gathered at 10 Hz. Gathering them samples the world generator's noise at the player and formats every
 * figure again, close to half of what the HUD cost with F3 open; the lines are read far slower than frames come.
 * Each caller gets its own copy, since mods append to the list they are handed.
 */
@Mixin(DebugScreenOverlay.class)
abstract class DebugTextBlazeMixin {
    @Unique private static final long OVERHAUL_INTERVAL = 100_000_000L;
    @Unique private List<String> overhaul$game, overhaul$system;
    @Unique private long overhaul$gameAt, overhaul$systemAt;

    @Inject(method = "getGameInformation", at = @At("HEAD"), cancellable = true)
    private void overhaul$reuseGame(CallbackInfoReturnable<List<String>> callback) {
        if (overhaul$game != null && System.nanoTime() - overhaul$gameAt < OVERHAUL_INTERVAL) callback.setReturnValue(new ArrayList<>(overhaul$game));
    }

    @Inject(method = "getGameInformation", at = @At("RETURN"))
    private void overhaul$keepGame(CallbackInfoReturnable<List<String>> callback) {
        if (overhaul$game != null && System.nanoTime() - overhaul$gameAt < OVERHAUL_INTERVAL) return;
        overhaul$game = new ArrayList<>(callback.getReturnValue());
        overhaul$gameAt = System.nanoTime();
    }

    @Inject(method = "getSystemInformation", at = @At("HEAD"), cancellable = true)
    private void overhaul$reuseSystem(CallbackInfoReturnable<List<String>> callback) {
        if (overhaul$system != null && System.nanoTime() - overhaul$systemAt < OVERHAUL_INTERVAL) callback.setReturnValue(new ArrayList<>(overhaul$system));
    }

    @Inject(method = "getSystemInformation", at = @At("RETURN"))
    private void overhaul$keepSystem(CallbackInfoReturnable<List<String>> callback) {
        if (overhaul$system != null && System.nanoTime() - overhaul$systemAt < OVERHAUL_INTERVAL) return;
        overhaul$system = new ArrayList<>(callback.getReturnValue());
        overhaul$systemAt = System.nanoTime();
    }
}
