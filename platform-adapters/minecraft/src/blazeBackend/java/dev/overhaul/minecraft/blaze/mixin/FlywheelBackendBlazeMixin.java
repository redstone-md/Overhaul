package dev.overhaul.minecraft.blaze.mixin;

import dev.overhaul.minecraft.blaze.BlazeFrontend;
import dev.overhaul.minecraft.blaze.BlazeOptimization;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Flywheel's backends draw with raw OpenGL programs, which render nothing on Vulkan, and they take block entities away
 * from the ordinary renderers. Its backend starts as off and stays off, so Create and others draw through the frontend,
 * unless raw programs compile natively, when its instancing backend runs.
 */
@Pseudo
@Mixin(targets="dev.engine_room.flywheel.impl.BackendManagerImpl", remap=false)
public abstract class FlywheelBackendBlazeMixin {
    @Inject(method="chooseBackend", at=@At("HEAD"), cancellable=true, require=0)
    private static void overhaul$stayOff(CallbackInfo callback) {
        if (BlazeFrontend.current() != null && !(BlazeOptimization.RAW_PROGRAMS.on() && BlazeOptimization.RAW_DRAWS.on())) callback.cancel();
    }
}
