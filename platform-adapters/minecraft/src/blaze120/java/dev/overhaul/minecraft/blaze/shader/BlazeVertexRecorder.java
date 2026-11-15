package dev.overhaul.minecraft.blaze.shader;

import com.mojang.blaze3d.vertex.VertexConsumer;

/**
 * Passes each vertex on to the game's buffer and keeps a copy in a {@link BlazeVertexLog}; also replays a log. Replays
 * write each vertex's parts in the vanilla element order, which the game's own formats share.
 */
final class BlazeVertexRecorder implements VertexConsumer {
    private final VertexConsumer target;
    private final BlazeVertexLog log;
    private int slot = -1;
    private boolean defaulted;
    private int defaultColor;

    BlazeVertexRecorder(VertexConsumer target, BlazeVertexLog log) {
        this.target = target;
        this.log = log;
    }

    @Override
    public VertexConsumer vertex(double x, double y, double z) {
        target.vertex(x, y, z);
        slot = log.begin((float) x, (float) y, (float) z);
        return this;
    }

    @Override
    public VertexConsumer color(int r, int g, int b, int a) {
        target.color(r, g, b, a);
        log.putInt(slot, BlazeVertexLog.RGBA, BlazeVertexLog.rgba(r, g, b, a), BlazeVertexLog.COLOR);
        return this;
    }

    @Override
    public VertexConsumer uv(float u, float v) {
        target.uv(u, v);
        log.put(slot, BlazeVertexLog.U, u, BlazeVertexLog.UV);
        log.put(slot, BlazeVertexLog.V, v, BlazeVertexLog.UV);
        return this;
    }

    @Override
    public VertexConsumer overlayCoords(int u, int v) {
        target.overlayCoords(u, v);
        log.putInt(slot, BlazeVertexLog.OVERLAY_UV, BlazeVertexLog.pack(u, v), BlazeVertexLog.OVERLAY);
        return this;
    }

    @Override
    public VertexConsumer uv2(int u, int v) {
        target.uv2(u, v);
        log.putInt(slot, BlazeVertexLog.LIGHT_UV, BlazeVertexLog.pack(u, v), BlazeVertexLog.LIGHT);
        return this;
    }

    @Override
    public VertexConsumer normal(float x, float y, float z) {
        target.normal(x, y, z);
        log.normal(slot, x, y, z);
        return this;
    }

    @Override
    public void endVertex() {
        target.endVertex();
        if (defaulted && slot >= 0 && !log.has(slot, BlazeVertexLog.COLOR))
            log.putInt(slot, BlazeVertexLog.RGBA, defaultColor, BlazeVertexLog.COLOR);
        slot = -1;
    }

    @Override
    public void defaultColor(int r, int g, int b, int a) {
        target.defaultColor(r, g, b, a);
        defaulted = true;
        defaultColor = BlazeVertexLog.rgba(r, g, b, a);
    }

    @Override
    public void unsetDefaultColor() {
        target.unsetDefaultColor();
        defaulted = false;
    }

    /** Writes the log's vertices to {@code out}, moved by the given offset. */
    static void replay(VertexConsumer out, BlazeVertexLog log, float dx, float dy, float dz) {
        for (int vertex = 0, count = log.vertices(); vertex < count; vertex++) {
            int slot = vertex * BlazeVertexLog.STRIDE;
            out.vertex(log.get(slot, BlazeVertexLog.X) + dx, log.get(slot, BlazeVertexLog.Y) + dy, log.get(slot, BlazeVertexLog.Z) + dz);
            if (log.has(slot, BlazeVertexLog.COLOR)) {
                int c = log.getInt(slot, BlazeVertexLog.RGBA);
                out.color(c >>> 24, c >>> 16 & 0xFF, c >>> 8 & 0xFF, c & 0xFF);
            }
            if (log.has(slot, BlazeVertexLog.UV)) out.uv(log.get(slot, BlazeVertexLog.U), log.get(slot, BlazeVertexLog.V));
            if (log.has(slot, BlazeVertexLog.OVERLAY)) {
                int o = log.getInt(slot, BlazeVertexLog.OVERLAY_UV);
                out.overlayCoords(BlazeVertexLog.low(o), BlazeVertexLog.high(o));
            }
            if (log.has(slot, BlazeVertexLog.LIGHT)) {
                int l = log.getInt(slot, BlazeVertexLog.LIGHT_UV);
                out.uv2(BlazeVertexLog.low(l), BlazeVertexLog.high(l));
            }
            if (log.has(slot, BlazeVertexLog.NORMAL))
                out.normal(log.get(slot, BlazeVertexLog.NX), log.get(slot, BlazeVertexLog.NY), log.get(slot, BlazeVertexLog.NZ));
            out.endVertex();
        }
    }
}
