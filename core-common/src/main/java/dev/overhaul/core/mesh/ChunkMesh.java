package dev.overhaul.core.mesh;

import dev.overhaul.core.memory.DirectSlabPool;
import dev.overhaul.core.world.BlockLighting.LightVolume;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Render-thread upload payload. Closing returns its native storage to the slab pool. */
public final class ChunkMesh implements AutoCloseable {
    public static final int VERTEX_STRIDE = 16;
    private final DirectSlabPool.Lease lease;
    private final int vertices;
    private final int indices;
    private final long revision;
    private final LightVolume lighting;

    ChunkMesh(DirectSlabPool.Lease lease, int vertices, int indices, long revision, LightVolume lighting) {
        this.lease = lease;
        this.vertices = vertices;
        this.indices = indices;
        this.revision = revision;
        this.lighting = lighting;
    }

    /** xyz float32 followed by uint32: state bits0..23, face24..26, light28..31. */
    public ByteBuffer vertexBuffer() { return view(0, vertices * VERTEX_STRIDE); }
    public ByteBuffer indexBuffer() { return view(vertices * VERTEX_STRIDE, indices * Integer.BYTES); }
    public int vertexCount() { return vertices; }
    public int indexCount() { return indices; }
    public long revision() { return revision; }
    public LightVolume lighting() { return lighting; }

    private ByteBuffer view(int offset, int bytes) {
        ByteBuffer data = lease.buffer();
        data.position(offset).limit(offset + bytes);
        return data.slice().asReadOnlyBuffer().order(ByteOrder.nativeOrder());
    }

    @Override public void close() { lease.close(); }
}
