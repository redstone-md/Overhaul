package dev.overhaul.minecraft.blaze.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.overhaul.minecraft.blaze.BlazeParticleBox;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Every particle's render box was built as a fresh AABB only to be read once by the frustum. A particle whose class keeps
 * SingleQuadParticle's box answers the test itself, and the box handed on is a marker the frustum call turns back into
 * that answer. Particles with their own box keep the game's path.
 */
@Mixin(ParticleEngine.class)
abstract class ParticleCulling121Mixin {
    private static final String RENDER = "render(Lnet/minecraft/client/renderer/LightTexture;Lnet/minecraft/client/Camera;FLnet/minecraft/client/renderer/culling/Frustum;Ljava/util/function/Predicate;)V";
    @Unique private static final AABB HIDDEN = new AABB(0, 0, 0, 0, 0, 0);
    @Unique private static final ClassValue<Boolean> PLAIN_BOX = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            try {
                return type.getMethod("getRenderBoundingBox", float.class).getDeclaringClass() == SingleQuadParticle.class;
            } catch (NoSuchMethodException missing) {
                return false;
            }
        }
    };

    @WrapOperation(method = RENDER, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/particle/Particle;getRenderBoundingBox(F)Lnet/minecraft/world/phys/AABB;"), require = 0)
    private AABB overhaul$box(Particle particle, float partialTick, Operation<AABB> original, @Local(argsOnly = true) Frustum frustum) {
        if (!(particle instanceof BlazeParticleBox box) || !PLAIN_BOX.get(particle.getClass())) return original.call(particle, partialTick);
        Frustum121Accessor view = (Frustum121Accessor) frustum;
        return box.overhaul$inView(view.overhaul$intersection(), view.overhaul$camX(), view.overhaul$camY(), view.overhaul$camZ(), partialTick) ? AABB.INFINITE : HIDDEN;
    }

    @WrapOperation(method = RENDER, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/culling/Frustum;isVisible(Lnet/minecraft/world/phys/AABB;)Z"), require = 0)
    private boolean overhaul$visible(Frustum frustum, AABB box, Operation<Boolean> original) {
        return box != HIDDEN && original.call(frustum, box);
    }
}
