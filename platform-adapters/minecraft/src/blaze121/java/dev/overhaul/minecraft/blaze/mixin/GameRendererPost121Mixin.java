package dev.overhaul.minecraft.blaze.mixin;

import dev.overhaul.minecraft.blaze.BlazePostProcess;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Anti-aliases and sharpens the world and hand once they are drawn, before the GUI. */
@Mixin(value = GameRenderer.class, priority = 1100)
abstract class GameRendererPost121Mixin {
    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void overhaul$post(DeltaTracker delta, CallbackInfo info) {
        BlazePostProcess.get().worldDone(Minecraft.getInstance());
    }
}
