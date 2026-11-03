package dev.overhaul.minecraft.backend;

/**
 * Render-thread-owned resource. Logical close stops new bindings immediately;
 * recorded commands keep native storage alive through their explicit pins.
 */
public abstract class BackendResource implements AutoCloseable {
    private final OverhaulDeviceBackend owner;
    private volatile boolean closing;
    private boolean destroyed;
    private int pins;

    protected BackendResource(OverhaulDeviceBackend owner) {
        this.owner=owner;
        owner.register(this);
    }

    public final OverhaulDeviceBackend owner() { return owner; }
    public final boolean isClosed() { return closing; }

    protected final void requireOpen() {
        owner.requireOpen();
        if (closing) throw new IllegalStateException("Resource is closed");
    }

    /** Native getters used by already pinned command records may outlive logical close. */
    protected final void requireNativeAlive() {
        owner.requireOwner();
        if (destroyed) throw new IllegalStateException("Native resource is destroyed");
    }

    public final AutoCloseable pin() {
        requireOpen();
        pins=Math.incrementExact(pins);
        return new AutoCloseable() {
            private boolean released;
            @Override public void close() {
                owner.requireOwner();
                if (released) return;
                released=true;
                pins--;
                if (closing && pins==0) destroy();
            }
        };
    }

    @Override public final void close() {
        owner.requireOwner();
        if (closing) return;
        closing=true;
        owner.resourceClosing(this);
        if (pins==0) destroy();
    }

    final int pins() { return pins; }

    private void destroy() {
        if (destroyed) return;
        destroyed=true;
        try { destroyNative(); }
        finally { owner.forget(this); }
    }

    protected abstract void destroyNative();
}
