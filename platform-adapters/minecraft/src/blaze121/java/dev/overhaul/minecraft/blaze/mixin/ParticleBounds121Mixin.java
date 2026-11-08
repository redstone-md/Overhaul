package dev.overhaul.minecraft.blaze.mixin;

import dev.overhaul.minecraft.blaze.BlazeParticleBounds;
import java.util.Map;
import java.util.Queue;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleRenderType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hands {@link BlazeParticleBounds} the particles once each tick has moved, added and removed them, and clears it with them. */
@Mixin(ParticleEngine.class)
abstract class ParticleBounds121Mixin {
    @Shadow @Final private Map<ParticleRenderType, Queue<Particle>> particles;

    @Inject(method = "tick", at = @At("TAIL"), require = 1)
    private void overhaul$captureBounds(CallbackInfo callback) {
        BlazeParticleBounds.capture(particles);
    }

    @Inject(method = "setLevel", at = @At("HEAD"), require = 1)
    private void overhaul$forgetBounds(ClientLevel level, CallbackInfo callback) {
        BlazeParticleBounds.clear();
    }

    @Inject(method = "clearParticles", at = @At("HEAD"), require = 0)
    private void overhaul$dropBounds(CallbackInfo callback) {
        BlazeParticleBounds.clear();
    }
}
