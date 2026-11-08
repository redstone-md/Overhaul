package dev.overhaul.minecraft.blaze.mixin;

import dev.overhaul.minecraft.blaze.BlazeEventListeners;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A server starts after every mod has registered its listeners, so that is when their annotations are made plain. */
@Mixin(MinecraftServer.class)
abstract class ServerEventListeners121Mixin {
    @Inject(method = "runServer", at = @At("HEAD"), require = 0)
    private void overhaul$plainListeners(CallbackInfo info) {
        BlazeEventListeners.plainAnnotations();
    }
}