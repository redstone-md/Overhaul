package dev.overhaul.minecraft.blaze.mixin;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.overhaul.minecraft.blaze.BlazeFrontend;
import dev.overhaul.minecraft.blaze.BlazeGameDrawState;
import dev.overhaul.minecraft.blaze.BlazeVertexBufferAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** RenderedBuffer owns pooled CPU storage until the shared native mesh copies the upload. */
@Mixin(VertexBuffer.class)
public abstract class VertexBufferUpload120Mixin {
    @Shadow private int vertexBufferId, indexCount;
    @Shadow private VertexFormat format;
    @Shadow private VertexFormat.Mode mode;
    @Shadow private VertexFormat.IndexType indexType;

    @Inject(method="upload(Lcom/mojang/blaze3d/vertex/BufferBuilder$RenderedBuffer;)V", at=@At("HEAD"), cancellable=true, require=1)
    private void overhaul$upload(BufferBuilder.RenderedBuffer source, CallbackInfo callback) {
        var access = (BlazeVertexBufferAccess) this;
        if (!access.overhaul$owned()) {
            if (BlazeFrontend.current() != null) {
                source.release();
                throw new IllegalStateException("OpenGL vertex buffer cannot migrate between devices");
            }
            return;
        }
        try {
            BlazeFrontend.require();
            if (vertexBufferId < 0) { callback.cancel(); return; }
            var game = source.drawState();
            var state = BlazeGameDrawState.capture(game.format(), game.vertexCount(), game.indexCount(), game.mode(), game.indexType());
            var indices = game.sequentialIndex() ? null : source.indexBuffer();
            if (game.indexOnly()) {
                var mesh = access.overhaul$existingMesh();
                if (!state.equals(mesh.state())) throw new IllegalArgumentException("Index-only upload changes existing vertex metadata");
                mesh.uploadIndices(indices);
            } else {
                access.overhaul$uploadMesh().upload(state, source.vertexBuffer(), indices);
            }
            format = game.format(); mode = game.mode(); indexType = game.indexType(); indexCount = game.indexCount();
            callback.cancel();
        } finally {
            source.release();
        }
    }
}
