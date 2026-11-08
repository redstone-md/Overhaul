package dev.overhaul.minecraft.blaze.mixin;

import dev.overhaul.minecraft.blaze.BlazeOptimization;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Flywheel's instancing backend asks for an extension flag, which a function table with no driver behind it never
 * reports. Its programs compile natively when raw programs are on, so instancing counts as supported then.
 */
@Pseudo
@Mixin(targets = "dev.engine_room.flywheel.backend.gl.GlCompat", remap = false)
public abstract class FlywheelCompatBlazeMixin {
    @Inject(method = "isInstancingSupported", at = @At("HEAD"), cancellable = true, require = 0)
    private static void overhaul$instancing(CallbackInfoReturnable<Boolean> callback) {
        if (BlazeOptimization.RAW_PROGRAMS.on() && BlazeOptimization.RAW_DRAWS.on()) callback.setReturnValue(true);
    }
}