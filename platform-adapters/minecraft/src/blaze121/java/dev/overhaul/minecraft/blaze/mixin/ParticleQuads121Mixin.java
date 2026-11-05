package dev.overhaul.minecraft.blaze.mixin;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.overhaul.minecraft.blaze.BlazeParticleVertices;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.SingleQuadParticle;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Four corners from two rotated axes, written straight into the buffer; any other consumer gets the game's code. */
@Mixin(SingleQuadParticle.class)
abstract class ParticleQuads121Mixin extends Particle {
    private ParticleQuads121Mixin(ClientLevel level, double x, double y, double z) {
        super(level, x, y, z);
    }

    @Shadow public abstract float getQuadSize(float partialTick);
    @Shadow protected abstract float getU0();
    @Shadow protected abstract float getU1();
    @Shadow protected abstract float getV0();
    @Shadow protected abstract float getV1();

    @Inject(method = "renderRotatedQuad(Lcom/mojang/blaze3d/vertex/VertexConsumer;Lorg/joml/Quaternionf;FFFF)V",
            at = @At("HEAD"), cancellable = true, require = 1)
    private void overhaul$writeQuad(VertexConsumer consumer, Quaternionf rotation, float x, float y, float z, float partialTick, CallbackInfo callback) {
        if (!(consumer instanceof BlazeParticleVertices vertices)) return;
        long address = vertices.overhaul$reserveParticleVertices(4);
        if (address == 0L) return;
        callback.cancel();
        float size = getQuadSize(partialTick);
        float u0 = getU0(), u1 = getU1(), v0 = getV0(), v1 = getV1();
        int light = getLightColor(partialTick);
        int color = ((int) (rCol * 255.0F) & 255) | ((int) (gCol * 255.0F) & 255) << 8
                | ((int) (bCol * 255.0F) & 255) << 16 | ((int) (alpha * 255.0F) & 255) << 24;
        Vector3f right = rotation.transform(1.0F, 0.0F, 0.0F, new Vector3f()).mul(size);
        Vector3f up = rotation.transform(0.0F, 1.0F, 0.0F, new Vector3f()).mul(size);
        overhaul$vertex(address, x + right.x - up.x, y + right.y - up.y, z + right.z - up.z, u1, v1, color, light);
        overhaul$vertex(address + BlazeParticleVertices.PARTICLE_STRIDE, x + right.x + up.x, y + right.y + up.y, z + right.z + up.z, u1, v0, color, light);
        overhaul$vertex(address + 2L * BlazeParticleVertices.PARTICLE_STRIDE, x - right.x + up.x, y - right.y + up.y, z - right.z + up.z, u0, v0, color, light);
        overhaul$vertex(address + 3L * BlazeParticleVertices.PARTICLE_STRIDE, x - right.x - up.x, y - right.y - up.y, z - right.z - up.z, u0, v1, color, light);
    }

    private static void overhaul$vertex(long address, float x, float y, float z, float u, float v, int color, int light) {
        MemoryUtil.memPutFloat(address, x);
        MemoryUtil.memPutFloat(address + 4L, y);
        MemoryUtil.memPutFloat(address + 8L, z);
        MemoryUtil.memPutFloat(address + 12L, u);
        MemoryUtil.memPutFloat(address + 16L, v);
        MemoryUtil.memPutInt(address + 20L, color);
        MemoryUtil.memPutInt(address + 24L, light);
    }
}
