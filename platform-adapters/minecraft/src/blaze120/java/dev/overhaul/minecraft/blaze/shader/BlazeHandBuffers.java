package dev.overhaul.minecraft.blaze.shader;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

import java.util.LinkedHashMap;

/** Buffers the hand draws to while {@link BlazeHandReplay} records it; every call goes on to the game's buffers. */
final class BlazeHandBuffers extends MultiBufferSource.BufferSource {
    private final BlazeHandReplay replay;

    BlazeHandBuffers(BlazeHandReplay replay) {
        super(new BufferBuilder(256), new LinkedHashMap<>());
        this.replay = replay;
    }

    @Override
    public VertexConsumer getBuffer(RenderType type) {
        return replay.buffer(type);
    }

    @Override
    public void endLastBatch() {
        replay.flush(MultiBufferSource.BufferSource::endLastBatch);
    }

    @Override
    public void endBatch() {
        replay.flush(MultiBufferSource.BufferSource::endBatch);
    }

    @Override
    public void endBatch(RenderType type) {
        replay.flush(game -> game.endBatch(type));
    }
}
