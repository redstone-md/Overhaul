package dev.overhaul.minecraft.particle;

import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/** Synchronous fixed-size copy from extracted game storage to a frame-owned mapped allocation. */
public final class ParticleInstanceWriter implements QuadParticleRenderState.ParticleConsumer {
    private final ByteBuffer target;
    private final int expected;
    private int written;
    public ParticleInstanceWriter(ByteBuffer target,int expected) {
        this.target=Objects.requireNonNull(target).order(ByteOrder.nativeOrder());this.expected=expected;
        if(expected<0||target.remaining()!=(long)expected*InstancedParticlePipelines.INSTANCE_STRIDE)
            throw new IllegalArgumentException("Particle allocation must contain exactly the expected records");
    }
    @Override public void consume(float x,float y,float z,float qx,float qy,float qz,float qw,float size,
            float u0,float u1,float v0,float v1,int color,int light) {
        if(written==expected)throw new IllegalStateException("Particle storage grew during preparation");
        target.putFloat(x).putFloat(y).putFloat(z).putFloat(qx).putFloat(qy).putFloat(qz).putFloat(qw).putFloat(size)
                .putFloat(u0).putFloat(u1).putFloat(v0).putFloat(v1).putInt(color).putInt(light);written++;
    }
    public void finish(){if(written!=expected)throw new IllegalStateException("Particle storage changed during preparation: "+written+" != "+expected);}
}
