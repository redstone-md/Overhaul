package dev.overhaul.minecraft.blaze.mixin;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.overhaul.minecraft.blaze.BlazeBufferSlack;
import dev.overhaul.minecraft.blaze.BlazeParticleVertices;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(BufferBuilder.class)
abstract class BufferBuilderParticles121Mixin implements BlazeParticleVertices {
    /** Quads of room taken past each one asked for when the slack runs out. */
    @Unique private static final int AHEAD_QUADS = 63;

    @Shadow @Final private ByteBufferBuilder buffer;
    @Shadow @Final private VertexFormat format;
    @Shadow @Final private VertexFormat.Mode mode;
    @Shadow @Final private int vertexSize;
    @Shadow private long vertexPointer;
    @Shadow private int vertices;
    @Shadow private void ensureBuilding() {}
    @Shadow private void endLastVertex() {}

    @Override
    public long overhaul$reserveParticleVertices(int count) {
        if (format != DefaultVertexFormat.PARTICLE || mode != VertexFormat.Mode.QUADS) return 0L;
        ensureBuilding();
        endLastVertex();
        vertices += count;
        int bytes = vertexSize * count;
        long first = BlazeBufferSlack.Switch.on && buffer instanceof BlazeBufferSlack slack
                ? slack.overhaul$reserveAhead(bytes, bytes * AHEAD_QUADS) : buffer.reserve(bytes);
        vertexPointer = first + (long) vertexSize * (count - 1);
        return first;
    }
}
