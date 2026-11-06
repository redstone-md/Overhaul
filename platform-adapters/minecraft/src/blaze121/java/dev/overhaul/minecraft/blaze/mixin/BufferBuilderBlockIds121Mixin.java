package dev.overhaul.minecraft.blaze.mixin;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import dev.overhaul.minecraft.blaze.BlazePackBlockFormat;
import dev.overhaul.minecraft.blaze.BlazePackVertexTag;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Writes the block id field of pack terrain vertices; a buffer in any other format never takes these branches. */
@Mixin(BufferBuilder.class)
abstract class BufferBuilderBlockIds121Mixin implements BlazePackVertexTag {
    @Shadow @Final private VertexFormat format;
    @Shadow private long beginVertex() { return 0L; }
    @Shadow private long beginElement(VertexFormatElement element) { return 0L; }
    @Unique private short overhaul$id = -1, overhaul$type = BlazePackBlockFormat.BLOCK;
    @Unique private float overhaul$centreX = 0.5f, overhaul$centreY = 0.5f, overhaul$centreZ = 0.5f;
    @Unique private byte overhaul$light;

    @Override public void overhaul$tag(short id, short type, int x, int y, int z, int light) {
        overhaul$id = id;
        overhaul$type = type;
        overhaul$centreX = x + 0.5f;
        overhaul$centreY = y + 0.5f;
        overhaul$centreZ = z + 0.5f;
        overhaul$light = (byte) light;
    }

    /** As Iris writes it: the offset from this vertex to its block's centre, in 64ths of a block, then the light. */
    @Unique private void overhaul$writeMid(long field, float x, float y, float z) {
        MemoryUtil.memPutByte(field, overhaul$sixtyfourths(overhaul$centreX - x));
        MemoryUtil.memPutByte(field + 1L, overhaul$sixtyfourths(overhaul$centreY - y));
        MemoryUtil.memPutByte(field + 2L, overhaul$sixtyfourths(overhaul$centreZ - z));
        MemoryUtil.memPutByte(field + 3L, overhaul$light);
    }

    @Unique private static byte overhaul$sixtyfourths(float offset) {
        return (byte) Math.max(-128, Math.min(127, Math.round(offset * 64f)));
    }

    @Unique private void overhaul$write(long field) {
        MemoryUtil.memPutShort(field, overhaul$id);
        MemoryUtil.memPutShort(field + 2L, overhaul$type);
    }

    /** Per-element writes: the id follows the position, the element every vertex starts with. */
    @Inject(method = "addVertex(FFF)Lcom/mojang/blaze3d/vertex/VertexConsumer;", at = @At("RETURN"), require = 1)
    private void overhaul$tagVertex(float x, float y, float z, CallbackInfoReturnable<VertexConsumer> callback) {
        if (format != BlazePackBlockFormat.FORMAT) return;
        long field = beginElement(BlazePackBlockFormat.ELEMENT);
        if (field != -1L) overhaul$write(field);
        long mid = beginElement(BlazePackBlockFormat.MID_ELEMENT);
        if (mid != -1L) overhaul$writeMid(mid, x, y, z);
    }

    /** The block format's one-call vertex, which the game takes only for its own two formats, kept for this one too. */
    @Inject(method = "addVertex(FFFIFFIIFFF)V", at = @At("HEAD"), cancellable = true, require = 1)
    private void overhaul$fullVertex(float x, float y, float z, int color, float u, float v, int overlay, int light,
                                     float normalX, float normalY, float normalZ, CallbackInfo callback) {
        if (format != BlazePackBlockFormat.FORMAT) return;
        long vertex = beginVertex();
        MemoryUtil.memPutFloat(vertex, x);
        MemoryUtil.memPutFloat(vertex + 4L, y);
        MemoryUtil.memPutFloat(vertex + 8L, z);
        MemoryUtil.memPutInt(vertex + 12L, abgr(color));
        MemoryUtil.memPutFloat(vertex + 16L, u);
        MemoryUtil.memPutFloat(vertex + 20L, v);
        MemoryUtil.memPutShort(vertex + 24L, (short) (light & 0xFFFF));
        MemoryUtil.memPutShort(vertex + 26L, (short) (light >>> 16 & 0xFFFF));
        MemoryUtil.memPutByte(vertex + 28L, normal(normalX));
        MemoryUtil.memPutByte(vertex + 29L, normal(normalY));
        MemoryUtil.memPutByte(vertex + 30L, normal(normalZ));
        overhaul$write(vertex + BlazePackBlockFormat.ID_OFFSET);
        overhaul$writeMid(vertex + BlazePackBlockFormat.MID_OFFSET, x, y, z);
        callback.cancel();
    }

    @Unique private static int abgr(int argb) {
        return argb & 0xFF00FF00 | (argb & 0xFF) << 16 | argb >>> 16 & 0xFF;
    }

    @Unique private static byte normal(float value) {
        return (byte) ((int) (Math.min(Math.max(value, -1f), 1f) * 127f) & 0xFF);
    }
}
