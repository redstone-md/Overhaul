package dev.overhaul.adapter;

/** Per-Minecraft-instance diagnostic runtime. No global mutable engine state. */
public final class ClientRuntime implements AutoCloseable {
    private final Thread owner=Thread.currentThread();
    private final boolean enabled;
    private ChunkBridge world;
    private Object worldIdentity;
    private SectionKey sampledSection;
    private long ticks, accepted;
    private Throwable failure;
    private long capturedRevision, acceptedRevision;
    private int acceptedIndices, retiredLeases=-1;
    private boolean closed;

    public ClientRuntime(boolean enabled) { this.enabled=enabled; }

    /** Loader tick hook; no work is submitted without an explicit capture call. */
    public void tick() {
        requireOwner(); ticks++;
        if (world!=null) accepted+=world.drain((key,mesh)-> {
            acceptedRevision=mesh.revision(); acceptedIndices=mesh.indexCount();
        });
        if (world!=null && sampledSection!=null) {
            Throwable error=world.takeFailure(sampledSection);
            if (error!=null) failure=error;
        }
    }

    /** Opt-in diagnostic only. This never substitutes a renderer or mutates a live world. */
    public void capture(Object identity,SectionKey key,SectionSource source) {
        requireOwner();
        if (!enabled) return;
        if (identity==null) { leaveWorld(); return; }
        if (worldIdentity!=identity) {
            leaveWorld();
            world=new ChunkBridge(2,8,4*1024*1024,4,8);
            worldIdentity=identity;
        }
        if (sampledSection!=null && !sampledSection.equals(key)) world.unload(sampledSection);
        try { capturedRevision=world.capture(key,source); sampledSection=key; }
        catch (java.util.concurrent.RejectedExecutionException rejected) { failure=rejected; }
    }

    public void leaveWorld() {
        requireOwner();
        if (world!=null) { world.close(); retiredLeases=world.retiredLeases(); }
        world=null; worldIdentity=null; sampledSection=null;
    }

    public boolean enabled() { return enabled; }
    public long tickCount() { requireOwner(); return ticks; }
    public long acceptedMeshes() { requireOwner(); return accepted; }
    public Throwable takeFailure() { requireOwner(); Throwable result=failure; failure=null; return result; }
    public long capturedRevision() { requireOwner(); return capturedRevision; }
    public long acceptedRevision() { requireOwner(); return acceptedRevision; }
    public int acceptedIndices() { requireOwner(); return acceptedIndices; }
    public int retiredLeases() { requireOwner(); return retiredLeases; }

    private void requireOwner() {
        if (Thread.currentThread()!=owner) throw new IllegalStateException("runtime belongs to the client thread");
        if (closed) throw new IllegalStateException("runtime is closed");
    }

    @Override public void close() {
        if (closed) return;
        requireOwner(); leaveWorld(); closed=true;
    }
}
