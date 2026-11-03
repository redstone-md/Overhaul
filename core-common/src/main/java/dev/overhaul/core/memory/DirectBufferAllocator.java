package dev.overhaul.core.memory;

import java.nio.ByteBuffer;

/** Allocation policy for a pool's single exclusively owned backing allocation. */
public interface DirectBufferAllocator {
    /** Returns a writable direct buffer of exactly {@code bytes} capacity. */
    ByteBuffer allocate(int bytes);

    /**
     * Retires the original allocation exactly once, after every lease has returned.
     * Implementations must support release from any thread and must not throw.
     * JVM-managed allocators may leave reclamation to the buffer's cleaner.
     */
    void release(ByteBuffer allocation);
}
