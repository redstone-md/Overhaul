package dev.overhaul.minecraft.model;

import dev.overhaul.minecraft.shader.SceneDrawDomain;
import java.util.Objects;

/** Collector-owned nested scope; dispatch wrappers restore the previous origin in finally. */
public final class SubmitDomainState {
    private final Thread owner = Thread.currentThread();
    private SceneDrawDomain current = SceneDrawDomain.UNKNOWN;

    public SceneDrawDomain current() { requireOwner(); return current; }
    public SceneDrawDomain enter(SceneDrawDomain domain) {
        requireOwner();
        SceneDrawDomain previous = current;
        current = Objects.requireNonNull(domain, "domain");
        return previous;
    }
    public void restore(SceneDrawDomain previous) { requireOwner(); current = Objects.requireNonNull(previous, "previous"); }
    private void requireOwner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Submit domain belongs to its collector owner");
    }
}
