package dev.overhaul.minecraft.blaze.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Xaero writes the map on the render thread and checks its time budget only after each write, and one chunk outlasts the
 * budget of a fast frame, so a lower budget alone still writes every frame. Each write therefore also pays for itself with
 * a pause, which fills the map more slowly and leaves the frame alone.
 */
@Pseudo
@Mixin(targets = "xaero.map.MapWriter", remap = false)
abstract class XaeroMapWriter121Mixin {
    @Unique private static final long NANOS_OF_WRITING_PER_MILLISECOND = 21740L;
    /** Writing then takes about 3% of the thread's time. */
    @Unique private static final long PAUSE_PER_WRITING = 30;

    @Unique private long overhaul$writeStarted;
    @Unique private long overhaul$nextWrite;

    /** No budget ends Xaero's loop at the first refused write instead of letting it ask a hundred times. */
    @ModifyConstant(method = "onRender", constant = @Constant(longValue = 86960L), require = 0)
    private long overhaul$writeBudget(long original) {
        return overhaul$paused() ? 0 : NANOS_OF_WRITING_PER_MILLISECOND;
    }

    @Inject(method = "writeMap", at = @At("HEAD"), cancellable = true, require = 0)
    private void overhaul$pause(CallbackInfoReturnable<Boolean> callback) {
        if (overhaul$paused()) callback.setReturnValue(false);
        else overhaul$writeStarted = System.nanoTime();
    }

    @Inject(method = "writeMap", at = @At("RETURN"), require = 0)
    private void overhaul$payForWrite(CallbackInfoReturnable<Boolean> callback) {
        long now = System.nanoTime();
        overhaul$nextWrite = now + (now - overhaul$writeStarted) * PAUSE_PER_WRITING;
    }

    @Unique
    private boolean overhaul$paused() {
        return System.nanoTime() - overhaul$nextWrite < 0;
    }
}
