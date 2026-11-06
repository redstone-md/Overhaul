package dev.overhaul.minecraft.blaze.mixin;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Leaves the cursor free while a test client runs next to someone working on the same desktop: input arrives as window
 * messages, so the game never needs the mouse. Applied only under -Doverhaul.dev.noGrab=true.
 */
@Mixin(MouseHandler.class)
abstract class MouseGrabBlazeMixin {
    @Inject(method = "grabMouse", at = @At("HEAD"), cancellable = true)
    private void overhaul$leaveCursor(CallbackInfo callback) {
        callback.cancel();
    }
}
