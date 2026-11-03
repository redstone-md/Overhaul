package dev.overhaul.vulkan;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import static org.junit.jupiter.api.Assertions.*;

class MeshBatchTest {
    @Test void preservesInputsAndEncodesIndependentMeshAndInstanceRanges() {
        ByteBuffer vertices = vertices();
        ByteBuffer indices = ByteBuffer.allocate(16).order(ByteOrder.nativeOrder()).putInt(999).putInt(0).putInt(1).putInt(2);
        indices.flip().position(4);
        try (MeshBatch batch = MeshBatch.builder()
            .add(vertices, indices, 3, new float[] {1, 2, 3, 4, 5, 6})
            .add(vertices, indices, 3, new float[] {-1, -2, -3}).build()) {
            assertEquals(0, vertices.position());
            assertEquals(4, indices.position());
            vertices.putFloat(0, 999);
            ByteBuffer bytes = batch.bytes();
            assertEquals(-0.2f, bytes.getFloat(0));
            assertEquals(2, batch.drawCount());
            int command = batch.commandOffset();
            assertEquals(3, bytes.getInt(command));
            assertEquals(2, bytes.getInt(command + 4));
            assertEquals(0, bytes.getInt(command + 8));
            assertEquals(0, bytes.getInt(command + 12));
            assertEquals(0, bytes.getInt(command + 16));
            assertEquals(3, bytes.getInt(command + 20));
            assertEquals(1, bytes.getInt(command + 24));
            assertEquals(3, bytes.getInt(command + 28));
            assertEquals(3, bytes.getInt(command + 32));
            assertEquals(2, bytes.getInt(command + 36));
            int instance = batch.vertexBytes + batch.indexBytes;
            assertEquals(1f, bytes.getFloat(instance));
            assertEquals(6f, bytes.getFloat(instance + 16 + 8));
            assertEquals(-1f, bytes.getFloat(instance + 32));
        }
    }

    @Test void rejectsInvalidNativeReadsBeforePackingAndClosedPacketsBeforeUploading() {
        ByteBuffer invalid = ByteBuffer.allocate(4).order(ByteOrder.nativeOrder()).putInt(-1).flip();
        assertThrows(IllegalArgumentException.class, () -> MeshBatch.builder().add(vertices(), invalid, 1, new float[] {0, 0, 0}));
        assertThrows(IllegalArgumentException.class, () -> MeshBatch.builder().add(vertices(), indices(), 3, new float[] {Float.NaN, 0, 0}));
        assertThrows(IllegalArgumentException.class, () -> MeshBatch.builder().add(vertices(), indices(), Integer.MAX_VALUE, new float[] {0, 0, 0}));
        MeshBatch batch = MeshBatch.builder().add(vertices(), indices(), 3, new float[] {0, 0, 0}).build();
        batch.close(); batch.close();
        assertThrows(IllegalStateException.class, batch::bytes);
    }

    private static ByteBuffer indices() {
        return ByteBuffer.allocate(12).order(ByteOrder.nativeOrder()).putInt(0).putInt(1).putInt(2).flip();
    }

    private static ByteBuffer vertices() {
        ByteBuffer data = ByteBuffer.allocate(48).order(ByteOrder.nativeOrder());
        data.putFloat(-0.2f).putFloat(0).putFloat(0.5f).putInt(3);
        data.putFloat(0.2f).putFloat(0).putFloat(0.5f).putInt(3);
        data.putFloat(0).putFloat(0.3f).putFloat(0.5f).putInt(3);
        return data.flip();
    }
}
