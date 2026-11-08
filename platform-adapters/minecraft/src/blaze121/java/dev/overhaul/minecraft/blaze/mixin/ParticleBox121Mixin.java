package dev.overhaul.minecraft.blaze.mixin;

import dev.overhaul.minecraft.blaze.BlazeParticleBox;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.SingleQuadParticle;
import org.joml.FrustumIntersection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/** The box SingleQuadParticle.getRenderBoundingBox builds, tested in place with the arithmetic Frustum.isVisible uses. */
@Mixin(SingleQuadParticle.class)
abstract class ParticleBox121Mixin extends Particle implements BlazeParticleBox {
    /** Room for a size that does not change monotonically across one tick. */
    @Unique private static final float SIZE_MARGIN = 1.125F;

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

    @Override
    public void overhaul$bounds(float[] boxes, int at, double anchorX, double anchorY, double anchorZ) {
        // The box sits on the current position, not the interpolated one, and only its size follows the partial tick.
        boxes[at] = (float) (x - anchorX);
        boxes[at + 1] = (float) (y - anchorY);
        boxes[at + 2] = (float) (z - anchorZ);
        boxes[at + 3] = Math.max(Math.abs(getQuadSize(0.0F)), Math.abs(getQuadSize(1.0F))) * SIZE_MARGIN;
    }
}
