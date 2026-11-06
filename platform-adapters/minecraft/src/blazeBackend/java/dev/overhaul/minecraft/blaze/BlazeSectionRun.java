package dev.overhaul.minecraft.blaze;

import dev.overhaul.vulkan.GpuBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * The buffers one indirect multi-draw binds. Meshes intern theirs when they upload, so a batch finds a section's run
 * by the stamp on the shared key instead of hashing every section of every frame. Equality is by buffer identity.
 */
final class BlazeSectionRun {
    /** Keys of buffers long gone are dropped wholesale; a mesh still holding one only costs a run of its own. */
    private static final int LIMIT = 8192;
    private static final Map<BlazeSectionRun, BlazeSectionRun> INTERNED = new HashMap<>();

    final GpuBuffer vertices, indices;
    final int remainder, indexBytes;
    /** Which batch last placed this run, and where in that batch's list. Owner-thread only. */
    long stamp;
    int index;

    private BlazeSectionRun(GpuBuffer vertices, int remainder, GpuBuffer indices, int indexBytes) {
        this.vertices = vertices;
        this.remainder = remainder;
        this.indices = indices;
        this.indexBytes = indexBytes;
    }

    /** A fresh key when {@code shared} is false, which batches then tell apart only by their buffers. */
    static BlazeSectionRun of(GpuBuffer vertices, int remainder, GpuBuffer indices, int indexBytes, boolean shared) {
        BlazeSectionRun run = new BlazeSectionRun(vertices, remainder, indices, indexBytes);
        if (!shared) return run;
        synchronized (INTERNED) {
            BlazeSectionRun known = INTERNED.get(run);
            if (known != null) return known;
            if (INTERNED.size() >= LIMIT) INTERNED.clear();
            INTERNED.put(run, run);
            return run;
        }
    }

    boolean holds(BlazeSectionRun other) {
        return other == this || vertices == other.vertices && remainder == other.remainder && indices == other.indices && indexBytes == other.indexBytes;
    }

    @Override public boolean equals(Object other) {
        return other instanceof BlazeSectionRun run && holds(run);
    }

    @Override public int hashCode() {
        int hash = System.identityHashCode(vertices) * 0x9E3779B9 ^ remainder * 0x85EBCA6B ^ System.identityHashCode(indices) * 0xC2B2AE35 ^ indexBytes;
        return hash ^ hash >>> 16;
    }
}
