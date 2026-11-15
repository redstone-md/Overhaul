package dev.overhaul.minecraft.blaze.shader;

import com.mojang.blaze3d.vertex.VertexConsumer;

/** Passes each vertex on to the game's buffer and keeps a copy in a {@link BlazeVertexLog}; also replays a log. */
final class BlazeVertexRecorder implements VertexConsumer {
    private final VertexConsumer target;
    private final BlazeVertexLog log;
    private int slot = -1;

    BlazeVertexRecorder(VertexConsumer target, BlazeVertexLog log) {
        this.target = target;
        this.log = log;
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        target.addVertex(x, y, z);
        slot = log.begin(x, y, z);
        return this;
    }

    @Override
    public VertexConsumer setColor(int r, int g, int b, int a) {
        target.setColor(r, g, b, a);
        log.putInt(slot, BlazeVertexLog.RGBA, BlazeVertexLog.rgba(r, g, b, a), BlazeVertexLog.COLOR);
        return this;
    }

    @Override
    public VertexConsumer setUv(float u, float v) {
        target.setUv(u, v);
        log.put(slot, BlazeVertexLog.U, u, BlazeVertexLog.UV);
        log.put(slot, BlazeVertexLog.V, v, BlazeVertexLog.UV);
        return this;
    }

    @Override
    public VertexConsumer setUv1(int u, int v) {
        target.setUv1(u, v);
        log.putInt(slot, BlazeVertexLog.OVERLAY_UV, BlazeVertexLog.pack(u, v), BlazeVertexLog.OVERLAY);
        return this;
    }

    @Override
    public VertexConsumer setUv2(int u, int v) {
        target.setUv2(u, v);
        log.putInt(slot, BlazeVertexLog.LIGHT_UV, BlazeVertexLog.pack(u, v), BlazeVertexLog.LIGHT);
        return this;
    }

    @Override
    public VertexConsumer setNormal(float x, float y, float z) {
        target.setNormal(x, y, z);
        log.normal(slot, x, y, z);
        return this;
    }

    /** Writes the log's vertices to {@code out}, moved by the given offset. */
    static void replay(VertexConsumer out, BlazeVertexLog log, float dx, float dy, float dz) {
        for (int vertex = 0, count = log.vertices(); vertex < count; vertex++) {
            int slot = vertex * BlazeVertexLog.STRIDE;
            out.addVertex(log.get(slot, BlazeVertexLog.X) + dx, log.get(slot, BlazeVertexLog.Y) + dy, log.get(slot, BlazeVertexLog.Z) + dz);
            if (log.has(slot, BlazeVertexLog.COLOR)) {
                int c = log.getInt(slot, BlazeVertexLog.RGBA);
                out.setColor(c >>> 24, c >>> 16 & 0xFF, c >>> 8 & 0xFF, c & 0xFF);
            }
            if (log.has(slot, BlazeVertexLog.UV)) out.setUv(log.get(slot, BlazeVertexLog.U), log.get(slot, BlazeVertexLog.V));
            if (log.has(slot, BlazeVertexLog.OVERLAY)) {
                int o = log.getInt(slot, BlazeVertexLog.OVERLAY_UV);
                out.setUv1(BlazeVertexLog.low(o), BlazeVertexLog.high(o));
            }
            if (log.has(slot, BlazeVertexLog.LIGHT)) {
                int l = log.getInt(slot, BlazeVertexLog.LIGHT_UV);
                out.setUv2(BlazeVertexLog.low(l), BlazeVertexLog.high(l));
            }
            if (log.has(slot, BlazeVertexLog.NORMAL))
                out.setNormal(log.get(slot, BlazeVertexLog.NX), log.get(slot, BlazeVertexLog.NY), log.get(slot, BlazeVertexLog.NZ));
        }
    }
}
