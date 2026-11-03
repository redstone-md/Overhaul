package dev.overhaul.adapter;

import dev.overhaul.core.memory.DirectSlabPool;
import dev.overhaul.core.mesh.ChunkMesh;
import dev.overhaul.core.world.ChunkPipeline;
import dev.overhaul.core.world.ChunkSnapshot;
import dev.overhaul.core.world.MeshMailbox;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BiConsumer;

/**
 * A world-scoped adapter boundary. Live-world capture and upload callbacks belong
 * to the constructing thread. Workers only see immutable snapshots. Each section
 * has a revision gate, and unload/close owns all abandoned native payloads.
 */
public final class ChunkBridge implements AutoCloseable {
    private final Thread owner=Thread.currentThread();
    private final Map<SectionKey, Slot> sections=new HashMap<SectionKey, Slot>();
    private final DirectSlabPool pool;
    private final ChunkPipeline pipeline;
    private final int maximumSections;
    private long revision;
    private boolean closed;

    public ChunkBridge(int workers, int queueCapacity, int slabBytes, int slabCount, int maximumSections) {
        if (maximumSections<1) throw new IllegalArgumentException("maximumSections must be positive");
        pool=new DirectSlabPool(slabBytes,slabCount);
        try { pipeline=new ChunkPipeline(workers,queueCapacity,pool); }
        catch (RuntimeException | Error failure) { pool.close(); throw failure; }
        this.maximumSections=maximumSections;
    }

    /** Capture includes neighbor halo; caller must ensure all queried chunks are already loaded. */
    public long capture(SectionKey key, SectionSource source) {
        requireOwner();
        Objects.requireNonNull(key,"key"); Objects.requireNonNull(source,"source");
        long nextRevision=Math.incrementExact(revision);
        ChunkSnapshot.Builder builder=ChunkSnapshot.builder(16).revision(nextRevision);
        int originX=Math.multiplyExact(key.x(),16), originY=Math.multiplyExact(key.y(),16);
        int originZ=Math.multiplyExact(key.z(),16);
        // Capture finishes before superseding a valid result if the source fails.
        for (int y=-1;y<=16;y++) for (int z=-1;z<=16;z++) for (int x=-1;x<=16;x++) {
            int bx=Math.addExact(originX,x), by=Math.addExact(originY,y), bz=Math.addExact(originZ,z);
            builder.set(x,y,z,source.material(bx,by,bz)).emission(x,y,z,source.emission(bx,by,bz));
        }
        ChunkSnapshot snapshot=builder.build();
        Slot slot=sections.get(key);
        if (slot==null) {
            if (sections.size()==maximumSections) throw new RejectedExecutionException("tracked section budget exhausted");
            slot=new Slot(); sections.put(key,slot);
        }
        revision=nextRevision;
        slot.replace(snapshot,pipeline);
        return revision;
    }

    /** Callback borrows mesh until return; it must synchronously copy/upload before return. */
    public int drain(BiConsumer<SectionKey,ChunkMesh> upload) {
        requireOwner(); Objects.requireNonNull(upload,"upload");
        int accepted=0;
        for (Map.Entry<SectionKey,Slot> entry:sections.entrySet()) {
            try (ChunkMesh mesh=entry.getValue().mailbox.poll()) {
                if (mesh!=null) { upload.accept(entry.getKey(),mesh); accepted++; }
            }
        }
        return accepted;
    }

    public void unload(SectionKey key) {
        requireOwner();
        Slot slot=sections.remove(key);
        if (slot!=null) slot.close();
    }

    public Throwable takeFailure(SectionKey key) {
        requireOwner(); Slot slot=sections.get(key);
        return slot==null ? null : slot.takeFailure();
    }

    public int pendingTasks() { requireOwner(); return pipeline.pendingChunks(); }
    public int trackedSections() { requireOwner(); return sections.size(); }
    public int activeLeases() { requireOwner(); return pool.activeLeases(); }

    private void requireOwner() {
        if (Thread.currentThread()!=owner) throw new IllegalStateException("adapter belongs to the world thread");
        if (closed) throw new IllegalStateException("adapter is closed");
    }

    @Override public void close() {
        if (closed) return;
        requireOwner(); closed=true;
        for (Slot slot:sections.values()) slot.close();
        sections.clear();
        pipeline.close();
        pool.close();
    }

    private static final class Slot implements AutoCloseable {
        private final MeshMailbox mailbox=new MeshMailbox();
        private final AtomicReference<Failure> failure=new AtomicReference<Failure>(new Failure(Long.MIN_VALUE,null));
        private CompletableFuture<ChunkMesh> pending;

        private void replace(ChunkSnapshot snapshot,ChunkPipeline pipeline) {
            failure.set(new Failure(snapshot.revision(),null));
            if (pending!=null) pending.cancel(false);
            mailbox.expect(snapshot.revision());
            pending=pipeline.submit(snapshot);
            pending.whenComplete((mesh,error)-> {
                if (mesh!=null) mailbox.publish(mesh);
                else if (error!=null && !(error instanceof java.util.concurrent.CancellationException)) {
                    recordFailure(snapshot.revision(),error);
                }
            });
        }

        private void recordFailure(long revision,Throwable error) {
            for (;;) {
                Failure previous=failure.get();
                if (previous.revision!=revision) return;
                if (failure.compareAndSet(previous,new Failure(revision,error))) return;
            }
        }

        private Throwable takeFailure() {
            for (;;) {
                Failure previous=failure.get();
                if (previous.error==null) return null;
                if (failure.compareAndSet(previous,new Failure(previous.revision,null))) return previous.error;
            }
        }

        @Override public void close() {
            failure.set(new Failure(Long.MIN_VALUE,null));
            mailbox.close();
            if (pending!=null) pending.cancel(false);
        }
    }

    private static final class Failure {
        private final long revision;
        private final Throwable error;
        private Failure(long revision,Throwable error) { this.revision=revision; this.error=error; }
    }
}
