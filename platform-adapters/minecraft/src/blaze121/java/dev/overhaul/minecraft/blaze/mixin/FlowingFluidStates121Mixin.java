package dev.overhaul.minecraft.blaze.mixin;

import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A spreading fluid asks for its source or flowing state at every neighbour it checks, and each answer looked up the
 * fluid's default state and walked the property table once or twice for the level and falling values. States never
 * change once the fluid exists, so each fluid keeps the answers it already gave. Filling the slots from several
 * threads at once is harmless: every thread finds the same state.
 */
@Mixin(FlowingFluid.class)
abstract class FlowingFluidStates121Mixin {
    /** Levels 1 to 8 still, then the same levels falling. */
    @Unique private static final int OVERHAUL$LEVELS = 8;
    @Unique private FluidState overhaul$source, overhaul$fallingSource;
    @Unique private FluidState[] overhaul$flowing;

    @Inject(method = "getSource(Z)Lnet/minecraft/world/level/material/FluidState;", at = @At("HEAD"), cancellable = true, require = 0)
    private void overhaul$knownSource(boolean falling, CallbackInfoReturnable<FluidState> callback) {
        FluidState known = falling ? overhaul$fallingSource : overhaul$source;
        if (known != null) callback.setReturnValue(known);
    }

    @Inject(method = "getSource(Z)Lnet/minecraft/world/level/material/FluidState;", at = @At("RETURN"), require = 0)
    private void overhaul$keepSource(boolean falling, CallbackInfoReturnable<FluidState> callback) {
        if (falling) overhaul$fallingSource = callback.getReturnValue();
        else overhaul$source = callback.getReturnValue();
    }

    @Inject(method = "getFlowing(IZ)Lnet/minecraft/world/level/material/FluidState;", at = @At("HEAD"), cancellable = true, require = 0)
    private void overhaul$knownFlowing(int level, boolean falling, CallbackInfoReturnable<FluidState> callback) {
        FluidState[] states = overhaul$flowing;
        if (states == null || level < 1 || level > OVERHAUL$LEVELS) return;
        FluidState known = states[level - 1 + (falling ? OVERHAUL$LEVELS : 0)];
        if (known != null) callback.setReturnValue(known);
    }

    @Inject(method = "getFlowing(IZ)Lnet/minecraft/world/level/material/FluidState;", at = @At("RETURN"), require = 0)
    private void overhaul$keepFlowing(int level, boolean falling, CallbackInfoReturnable<FluidState> callback) {
        if (level < 1 || level > OVERHAUL$LEVELS) return;
        FluidState[] states = overhaul$flowing;
        if (states == null) overhaul$flowing = states = new FluidState[OVERHAUL$LEVELS * 2];
        states[level - 1 + (falling ? OVERHAUL$LEVELS : 0)] = callback.getReturnValue();
    }
}
