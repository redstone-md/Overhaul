package dev.overhaul.minecraft.blaze.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * The F3 lines gathered at 10 Hz. Gathering them samples the world generator's noise at the player and formats every
 * figure again, close to half of what the HUD cost with F3 open; the lines are read far slower than frames come.
 * The whole method is wrapped, so lines other mods add at its return are kept with the rest instead of skipped.
 * Each caller gets its own copy, since mods append to the list they are handed.
 */
@Mixin(DebugScreenOverlay.class)
abstract class DebugTextBlazeMixin {
    @Unique private static final long OVERHAUL_INTERVAL = 100_000_000L;
    @Unique private List<String> overhaul$game, overhaul$system;
    @Unique private long overhaul$gameAt, overhaul$systemAt;

    @WrapMethod(method = "getGameInformation")
    private List<String> overhaul$reuseGame(Operation<List<String>> original) {
        long now = System.nanoTime();
        if (overhaul$game == null || now - overhaul$gameAt >= OVERHAUL_INTERVAL) {
            overhaul$game = new ArrayList<>(original.call());
            overhaul$gameAt = now;
        }
        return new ArrayList<>(overhaul$game);
    }

    @WrapMethod(method = "getSystemInformation")
    private List<String> overhaul$reuseSystem(Operation<List<String>> original) {
        long now = System.nanoTime();
        if (overhaul$system == null || now - overhaul$systemAt >= OVERHAUL_INTERVAL) {
            overhaul$system = new ArrayList<>(original.call());
            overhaul$systemAt = now;
        }
        return new ArrayList<>(overhaul$system);
    }
}
