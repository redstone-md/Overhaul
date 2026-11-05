package dev.overhaul.minecraft.blaze.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Sorting every entity into radar categories each tick is costly in a crowd. Marks read live positions, so only membership lags, by at most three ticks. */
@Pseudo
@Mixin(targets = "xaero.hud.minimap.radar.state.RadarStateUpdater", remap = false)
abstract class XaeroRadar121Mixin {
    @Unique private static final int TICKS_PER_UPDATE = 4;
    @Unique private int overhaul$ticks;

    @Inject(method = "update", at = @At("HEAD"), cancellable = true, require = 0)
    private void overhaul$skipTick(CallbackInfo callback) {
        if (overhaul$ticks++ % TICKS_PER_UPDATE != 0) callback.cancel();
    }
}
