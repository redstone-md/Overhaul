package dev.overhaul.minecraft.backendmixin;

import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Accessor mixin exposes methods without exposing the package-private storage type or its arrays. */
@Mixin(targets="net.minecraft.client.renderer.state.level.QuadParticleRenderState$Storage")
public interface ParticleStorageAccess {
    @Invoker("count") int overhaul$count();
    @Invoker("forEachParticle") void overhaul$forEachParticle(QuadParticleRenderState.ParticleConsumer consumer);
}
