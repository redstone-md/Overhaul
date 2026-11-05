package dev.overhaul.minecraft.blaze.mixin;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.overhaul.minecraft.blaze.BlazeCompactSection;
import dev.overhaul.minecraft.blaze.BlazeFrontend;
import dev.overhaul.minecraft.blaze.BlazeGameDrawState;
import dev.overhaul.minecraft.blaze.BlazeVertexBufferAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** MeshData and resort results release their CPU allocations after staging has copied the bytes. */
@Mixin(VertexBuffer.class)
public abstract class VertexBufferUpload121Mixin {
    @Shadow private int vertexBufferId, indexCount;
    @Shadow private VertexFormat format;
    @Shadow private VertexFormat.Mode mode;
    @Shadow private VertexFormat.IndexType indexType;

    @Inject(method="upload(Lcom/mojang/blaze3d/vertex/MeshData;)V", at=@At("HEAD"), cancellable=true, require=1)
    private void overhaul$upload(MeshData source, CallbackInfo callback) {
        var access = (BlazeVertexBufferAccess) this;
        if (!access.overhaul$owned()) {
            if (BlazeFrontend.current() != null) {
                source.close();
                throw new IllegalStateException("OpenGL vertex buffer cannot migrate between devices");
            }
            return;
        }
        try (source) {
            var renderer = BlazeFrontend.require();
            if (vertexBufferId < 0) { callback.cancel(); return; }
            var game = source.drawState();
            var state = BlazeGameDrawState.capture(game.format(), game.vertexCount(), game.indexCount(), game.mode(), game.indexType());
            var mesh = access.overhaul$uploadMesh();
            if (!access.overhaul$sectionUploads() || !renderer.compactsSections()
                    || !BlazeCompactSection.upload(mesh, state, source.vertexBuffer(), source.indexBuffer())) {
                mesh.upload(state, source.vertexBuffer(), source.indexBuffer());
            }
            format = game.format(); mode = game.mode(); indexType = game.indexType(); indexCount = game.indexCount();
            callback.cancel();
        }
    }

    @Inject(method="uploadIndexBuffer(Lcom/mojang/blaze3d/vertex/ByteBufferBuilder$Result;)V", at=@At("HEAD"), cancellable=true, require=1)
    private void overhaul$indices(ByteBufferBuilder.Result source, CallbackInfo callback) {
        var access = (BlazeVertexBufferAccess) this;
        if (!access.overhaul$owned()) {
            if (BlazeFrontend.current() != null) {
                source.close();
                throw new IllegalStateException("OpenGL vertex buffer cannot migrate between devices");
            }
            return;
        }
        try (source) {
            access.overhaul$existingMesh().uploadIndices(source.byteBuffer());
            callback.cancel();
        }
    }
}
