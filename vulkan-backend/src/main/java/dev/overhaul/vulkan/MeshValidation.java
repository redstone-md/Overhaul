package dev.overhaul.vulkan;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** The single validation boundary for compact xyz/material vertices and uint32 indices. */
final class MeshValidation {
    private MeshValidation() { }
    static void validate(ByteBuffer vertices, ByteBuffer indices, int indexCount) {
        if (vertices.remaining() == 0 || vertices.remaining() % 16 != 0)
            throw new IllegalArgumentException("vertices must contain xyz/material records");
        if (indexCount < 1 || (long) indexCount * 4 > indices.remaining())
            throw new IllegalArgumentException("index count exceeds available uint32 indices");
        int count = vertices.remaining() / 16;
        ByteBuffer view = indices.duplicate().order(ByteOrder.nativeOrder());
        for (int i = 0; i < indexCount; i++)
            if (Integer.compareUnsigned(view.getInt(view.position() + i * 4), count) >= 0)
                throw new IllegalArgumentException("mesh index exceeds vertex count");
    }
}
