package dev.overhaul.minecraft.blaze.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.overhaul.minecraft.blaze.BlazeParticleBounds;
import dev.overhaul.minecraft.blaze.BlazeParticleBox;
import java.util.function.Predicate;
import net.minecraft.client.Camera;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every particle's render box was built as a fresh AABB only to be read once by the frustum. A particle whose class keeps
 * SingleQuadParticle's box answers the test itself, or from the copy {@link BlazeParticleBounds} took after the tick,
 * and the box handed on is a marker the frustum call turns back into that answer. Particles with their own box keep the
 * game's path.
 */
@Mixin(ParticleEngine.class)
abstract class ParticleCulling121Mixin {
    private static final String RENDER = "render(Lnet/minecraft/client/renderer/LightTexture;Lnet/minecraft/client/Camera;FLnet/minecraft/client/renderer/culling/Frustum;Ljava/util/function/Predicate;)V";
    @Unique private static final AABB HIDDEN = new AABB(0, 0, 0, 0, 0, 0);

    @Inject(method = RENDER, at = @At("HEAD"), require = 0)
    private void overhaul$cullAll(LightTexture light, Camera camera, float partialTick, Frustum frustum, Predicate<ParticleRenderType> filter, CallbackInfo callback) {
        Frustum121Accessor view = (Frustum121Accessor) frustum;
        BlazeParticleBounds.cull(view.overhaul$intersection(), view.overhaul$camX(), view.overhaul$camY(), view.overhaul$camZ());
    }

    @WrapOperation(method = RENDER, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/particle/Particle;getRenderBoundingBox(F)Lnet/minecraft/world/phys/AABB;"), require = 0)
    private AABB overhaul$box(Particle particle, float partialTick, Operation<AABB> original, @Local(argsOnly = true) Frustum frustum) {
        Frustum121Accessor view = (Frustum121Accessor) frustum;
        if (BlazeParticleBounds.on) {
            int at = BlazeParticleBounds.find(particle);
            if (at >= 0) {
                byte seen = BlazeParticleBounds.visibility(at);
                if (seen != BlazeParticleBounds.UNTESTED) return seen == BlazeParticleBounds.SHOWN ? AABB.INFINITE : HIDDEN;
                float[] boxes = BlazeParticleBounds.boxes();
                float x = boxes[at] - (float) (view.overhaul$camX() - BlazeParticleBounds.anchorX());
                float y = boxes[at + 1] - (float) (view.overhaul$camY() - BlazeParticleBounds.anchorY());
                float z = boxes[at + 2] - (float) (view.overhaul$camZ() - BlazeParticleBounds.anchorZ());
                float half = boxes[at + 3];
                return view.overhaul$intersection().testAab(x - half, y - half, z - half, x + half, y + half, z + half) ? AABB.INFINITE : HIDDEN;
            }
        }
        if (!(particle instanceof BlazeParticleBox box) || !BlazeParticleBounds.plainBox(particle)) return original.call(particle, partialTick);
        return box.overhaul$inView(view.overhaul$intersection(), view.overhaul$camX(), view.overhaul$camY(), view.overhaul$camZ(), partialTick) ? AABB.INFINITE : HIDDEN;
    }

    @WrapOperation(method = RENDER, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/culling/Frustum;isVisible(Lnet/minecraft/world/phys/AABB;)Z"), require = 0)
    private boolean overhaul$visible(Frustum frustum, AABB box, Operation<Boolean> original) {
        return box != HIDDEN && original.call(frustum, box);
    }
}
