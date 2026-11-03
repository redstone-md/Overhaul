package dev.overhaul.core.world;

import dev.overhaul.core.mesh.ChunkMesh;
import java.util.concurrent.atomic.AtomicReference;

/** One chunk's revision gate: replaces stale work without leaking native mesh leases. */
public final class MeshMailbox implements AutoCloseable {
    private final AtomicReference<State> state = new AtomicReference<State>(
            new State(Long.MIN_VALUE, null, false));

    public void expect(long nextRevision) {
        for (;;) {
            State previous = state.get();
            if (previous.closed) throw new IllegalStateException("mailbox is closed");
            if (state.compareAndSet(previous, new State(nextRevision, null, false))) {
                discard(previous.mesh);
                return;
            }
        }
    }

    /** Transfers ownership to this mailbox, including when the result has become obsolete. */
    public void publish(ChunkMesh mesh) {
        for (;;) {
            State previous = state.get();
            if (previous.closed || mesh.revision() != previous.revision) { mesh.close(); return; }
            if (state.compareAndSet(previous, new State(previous.revision, mesh, false))) {
                if (previous.mesh != mesh) discard(previous.mesh);
                return;
            }
        }
    }

    /** Transfers ownership to the render thread, or returns null when no current mesh is ready. */
    public ChunkMesh poll() {
        for (;;) {
            State previous = state.get();
            if (previous.closed || previous.mesh == null) return null;
            if (state.compareAndSet(previous, new State(previous.revision, null, false))) {
                return previous.mesh;
            }
        }
    }

    private static void discard(ChunkMesh mesh) { if (mesh != null) mesh.close(); }
    @Override public void close() {
        State previous = state.getAndSet(new State(Long.MIN_VALUE, null, true));
        discard(previous.mesh);
    }

    private static final class State {
        private final long revision;
        private final ChunkMesh mesh;
        private final boolean closed;
        private State(long revision, ChunkMesh mesh, boolean closed) {
            this.revision = revision;
            this.mesh = mesh;
            this.closed = closed;
        }
    }
}
