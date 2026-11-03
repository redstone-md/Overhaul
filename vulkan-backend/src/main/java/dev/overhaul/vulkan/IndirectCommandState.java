package dev.overhaul.vulkan;

import dev.overhaul.core.culling.Frustum;
import dev.overhaul.core.culling.SoftwareDepthBuffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import static org.lwjgl.system.MemoryUtil.*;

/** Reversible visibility state; firstIndex/vertexOffset/firstInstance never change during culling. */
final class IndirectCommandState implements AutoCloseable {
    private final int[] originals;
    private final int[] current;
    private final int[] changedDraws;
    private ByteBuffer changes;
    private int changedCount;
    private int visibleCount;

    IndirectCommandState(MeshBatch packet) {
        originals = new int[packet.commandCount];
        current = new int[packet.commandCount];
        changedDraws = new int[packet.commandCount];
        ByteBuffer commands = packet.bytes();
        for (int i = 0; i < originals.length; i++) originals[i] = current[i] = commands.getInt(packet.commandOffset() + i * 20);
        changes = memAlloc(Math.multiplyExact(originals.length, 4)).order(ByteOrder.nativeOrder());
    }

    void prepare(SceneVisibility scene, Frustum frustum, SoftwareDepthBuffer depth) {
        if (changes == null) throw new IllegalStateException("indirect command state closed");
        if (scene.drawCount() != originals.length) throw new IllegalArgumentException("visibility draw count differs from GPU batch");
        changedCount = visibleCount = 0;
        changes.clear();
        for (int i = 0; i < originals.length; i++) {
            boolean visible = scene.visible(i, frustum, depth);
            if (visible) visibleCount++;
            int count = visible ? originals[i] : 0;
            if (count != current[i]) {
                changedDraws[changedCount++] = i;
                changes.putInt(count);
            }
        }
        changes.flip();
    }

    int changedCount() { return changedCount; }
    int visibleCount() { return visibleCount; }
    int changedDraw(int index) { return changedDraws[index]; }
    ByteBuffer bytes() { return changes; }

    void commit() {
        for (int i = 0; i < changedCount; i++) current[changedDraws[i]] = changes.getInt(i * 4);
    }

    @Override public void close() { if (changes != null) { memFree(changes); changes = null; } }
}
