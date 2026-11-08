package dev.overhaul.minecraft.blaze.mixin;

import dev.overhaul.minecraft.blaze.BlazeParticleBox;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.SingleQuadParticle;
import org.joml.FrustumIntersection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** The box SingleQuadParticle.getRenderBoundingBox builds, tested in place with the arithmetic Frustum.isVisible uses. */
@Mixin(SingleQuadParticle.class)
abstract class ParticleBox121Mixin extends Particle implements BlazeParticleBox {
    private ParticleBox121Mixin(ClientLevel level, double x, double y, double z) {
        super(level, x, y, z);
    }

    @Shadow public abstract float getQuadSize(float partialTick);

    @Override
    public boolean overhaul$inView(FrustumIntersection view, double cameraX, double cameraY, double cameraZ, float partialTick) {
        double size = getQuadSize(partialTick);
        return view.testAab((float) (x - size - cameraX), (float) (y - size - cameraY), (float) (z - size - cameraZ),
                (float) (x + size - cameraX), (float) (y + size - cameraY), (float) (z + size - cameraZ));
    }
}
