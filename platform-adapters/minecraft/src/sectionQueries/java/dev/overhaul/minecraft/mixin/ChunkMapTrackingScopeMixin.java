package dev.overhaul.minecraft.mixin;

import dev.overhaul.minecraft.world.EntityTrackingScope;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Bounds the tracking grid to the tick that updates every tracked entity. */
@Mixin(ChunkMap.class)
public abstract class ChunkMapTrackingScopeMixin {
    @Inject(method = "tick()V", at = @At("HEAD"), require = 1)
    private void overhaul$openTrackingScope(CallbackInfo callback) {
        EntityTrackingScope.begin();
    }

    @Inject(method = "tick()V", at = @At("RETURN"), require = 1)
    private void overhaul$closeTrackingScope(CallbackInfo callback) {
        EntityTrackingScope.end();
    }
}
