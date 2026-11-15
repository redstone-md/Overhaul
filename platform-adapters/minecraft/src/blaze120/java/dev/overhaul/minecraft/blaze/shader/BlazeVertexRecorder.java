package dev.overhaul.minecraft.blaze.shader;

import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix3fc;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

/**
 * Passes each vertex on to the game's buffer and keeps a copy in a {@link BlazeVertexLog}; also replays a log. Replays
 * write each vertex's parts in the vanilla element order, which the game's own formats share.
 */
final class BlazeVertexRecorder implements VertexConsumer {
    private final VertexConsumer target;
    private final BlazeVertexLog log;
    /** Applied to what is passed on, never to what is kept; null passes vertices on as given. */
    private final Matrix4fc transform;
    private final Matrix3fc normals;
    private final Vector3f moved = new Vector3f();
    private int slot = -1;
    private boolean defaulted;
    private int defaultColor;

    BlazeVertexRecorder(VertexConsumer target, BlazeVertexLog log) {
        this(target, log, null, null);
    }

    /** Keeps vertices as given and passes them on moved by {@code transform}, normals turned by {@code normals}. */
    BlazeVertexRecorder(VertexConsumer target, BlazeVertexLog log, Matrix4fc transform, Matrix3fc normals) {
        this.target = target;
        this.log = log;
        this.transform = transform;
        this.normals = normals;
    }

    @Override
    public VertexConsumer vertex(double x, double y, double z) {
        slot = log.begin((float) x, (float) y, (float) z);
        if (transform == null) target.vertex(x, y, z);
        else {
            transform.transformPosition((float) x, (float) y, (float) z, moved);
            target.vertex(moved.x, moved.y, moved.z);
        }
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
        log.normal(slot, x, y, z);
        if (normals == null) target.normal(x, y, z);
        else {
            normals.transform(x, y, z, moved);
            target.normal(moved.x, moved.y, moved.z);
        }
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
        replay(out, log, null, null, dx, dy, dz);
    }

    /** Writes the log's vertices to {@code out}, moved by {@code transform} with normals turned by {@code normals}. */
    static void replay(VertexConsumer out, BlazeVertexLog log, Matrix4fc transform, Matrix3fc normals) {
        replay(out, log, transform, normals, 0, 0, 0);
    }

    private static void replay(VertexConsumer out, BlazeVertexLog log, Matrix4fc transform, Matrix3fc normals, float dx, float dy, float dz) {
        Vector3f moved = new Vector3f();
        for (int vertex = 0, count = log.vertices(); vertex < count; vertex++) {
            int slot = vertex * BlazeVertexLog.STRIDE;
            float x = log.get(slot, BlazeVertexLog.X), y = log.get(slot, BlazeVertexLog.Y), z = log.get(slot, BlazeVertexLog.Z);
            if (transform == null) out.vertex(x + dx, y + dy, z + dz);
            else {
                transform.transformPosition(x, y, z, moved);
                out.vertex(moved.x, moved.y, moved.z);
            }
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
            if (log.has(slot, BlazeVertexLog.NORMAL)) {
                float nx = log.get(slot, BlazeVertexLog.NX), ny = log.get(slot, BlazeVertexLog.NY), nz = log.get(slot, BlazeVertexLog.NZ);
                if (normals == null) out.normal(nx, ny, nz);
                else {
                    normals.transform(nx, ny, nz, moved);
                    out.normal(moved.x, moved.y, moved.z);
                }
            }
            out.endVertex();
        }
    }
}
