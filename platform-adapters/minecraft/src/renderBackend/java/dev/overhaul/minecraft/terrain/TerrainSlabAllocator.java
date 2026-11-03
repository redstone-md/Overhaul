package dev.overhaul.minecraft.terrain;

import dev.overhaul.core.memory.DirectBufferAllocator;
import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryUtil;

/** Terrain scratch memory is freed on compiler retirement rather than a later GC. */
final class TerrainSlabAllocator implements DirectBufferAllocator {
    @Override public ByteBuffer allocate(int bytes) { return MemoryUtil.memAlloc(bytes); }
    @Override public void release(ByteBuffer allocation) { MemoryUtil.memFree(allocation); }
}
