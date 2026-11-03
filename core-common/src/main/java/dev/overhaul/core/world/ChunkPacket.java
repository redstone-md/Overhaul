package dev.overhaul.core.world;

import dev.overhaul.core.memory.DirectSlabPool;
import java.nio.ByteBuffer;

/** Owned pooled off-heap packet. Transfer to workers or close after use, never both. */
public final class ChunkPacket implements AutoCloseable {
    private final DirectSlabPool.Lease lease;

    private ChunkPacket(DirectSlabPool.Lease lease) { this.lease = lease; }

    public static ChunkPacket encode(ChunkSnapshot snapshot, DirectSlabPool pool) {
        DirectSlabPool.Lease lease = pool.acquire(snapshot.encodedBytes());
        try {
            snapshot.encode(lease.buffer());
            return new ChunkPacket(lease);
        } catch (Throwable failure) { lease.close(); throw failure; }
    }

    /** Copies external packet bytes into an owned slab before the producer releases its input. */
    public static ChunkPacket copyOf(ByteBuffer source, DirectSlabPool pool) {
        DirectSlabPool.Lease lease = pool.acquire(source.remaining());
        try {
            lease.buffer().put(source.duplicate());
            return new ChunkPacket(lease);
        } catch (Throwable failure) { lease.close(); throw failure; }
    }

    public ByteBuffer bytes() { return lease.buffer().asReadOnlyBuffer(); }
    public ChunkSnapshot decode() { return ChunkSnapshot.decode(bytes()); }
    @Override public void close() { lease.close(); }
}
