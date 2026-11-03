package dev.overhaul.vulkan;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import static org.lwjgl.system.MemoryUtil.*;

/** Owned compact upload packet: shared vertex/index streams, instance translations, indirect commands. */
public final class MeshBatch implements AutoCloseable {
    private ByteBuffer bytes;
    final int vertexBytes;
    final int indexBytes;
    final int instanceBytes;
    final int commandCount;
    final int[] firstInstances;

    private MeshBatch(List<Entry> entries) {
        int vertices = 0, indices = 0, instances = 0;
        for (Entry entry : entries) {
            vertices = Math.addExact(vertices, entry.vertices.remaining());
            indices = Math.addExact(indices, Math.multiplyExact(entry.indexCount, 4));
            instances = Math.addExact(instances, Math.multiplyExact(entry.offsets.length / 3, 16));
        }
        vertexBytes = vertices;
        indexBytes = indices;
        instanceBytes = instances;
        commandCount = entries.size();
        firstInstances = new int[commandCount];
        int total = Math.addExact(Math.addExact(vertices, indices), Math.addExact(instances, Math.multiplyExact(commandCount, 20)));
        bytes = memAlloc(total).order(ByteOrder.nativeOrder());
        try {
            int vertexOffset = 0, indexOffset = 0, instanceOffset = 0, commandIndex = 0;
            for (Entry entry : entries) {
                bytes.position(vertexOffset).put(entry.vertices.duplicate());
                ByteBuffer indexData = entry.indices.duplicate();
                indexData.limit(indexData.position() + entry.indexCount * 4);
                bytes.position(vertexBytes + indexOffset).put(indexData);
                bytes.position(vertexBytes + indexBytes + instanceOffset);
                for (int i = 0; i < entry.offsets.length; i += 3)
                    bytes.putFloat(entry.offsets[i]).putFloat(entry.offsets[i + 1]).putFloat(entry.offsets[i + 2]).putFloat(0);
                firstInstances[commandIndex] = instanceOffset / 16;
                bytes.position(commandOffset() + commandIndex++ * 20)
                    .putInt(entry.indexCount).putInt(entry.offsets.length / 3)
                    .putInt(indexOffset / 4).putInt(vertexOffset / 16).putInt(instanceOffset / 16);
                vertexOffset += entry.vertices.remaining();
                indexOffset += entry.indexCount * 4;
                instanceOffset += entry.offsets.length / 3 * 16;
            }
            bytes.clear();
        } catch (RuntimeException | Error failure) { close(); throw failure; }
    }

    public static Builder builder() { return new Builder(); }
    public int drawCount() { requireOpen(); return commandCount; }
    public int byteSize() { requireOpen(); return bytes.capacity(); }
    int commandOffset() { return vertexBytes + indexBytes + instanceBytes; }
    ByteBuffer bytes() { requireOpen(); return bytes.asReadOnlyBuffer().order(ByteOrder.nativeOrder()); }
    private void requireOpen() { if (bytes == null) throw new IllegalStateException("mesh batch closed"); }

    @Override public void close() { if (bytes != null) { memFree(bytes); bytes = null; } }

    public static final class Builder {
        private final List<Entry> entries = new ArrayList<>();

        /** Source buffers must remain alive until build returns. Offsets are xyz triplets per instance. */
        public Builder add(ByteBuffer vertices, ByteBuffer indices, int indexCount, float[] xyzOffsets) {
            MeshValidation.validate(vertices, indices, indexCount);
            if (xyzOffsets.length == 0 || xyzOffsets.length % 3 != 0) throw new IllegalArgumentException("instance offsets require xyz triplets");
            for (float offset : xyzOffsets) if (!Float.isFinite(offset)) throw new IllegalArgumentException("nonfinite instance offset");
            entries.add(new Entry(vertices.duplicate(), indices.duplicate(), indexCount, xyzOffsets.clone()));
            return this;
        }

        public MeshBatch build() {
            if (entries.isEmpty()) throw new IllegalStateException("batch has no draws");
            return new MeshBatch(entries);
        }
    }

    private record Entry(ByteBuffer vertices, ByteBuffer indices, int indexCount, float[] offsets) { }
}
