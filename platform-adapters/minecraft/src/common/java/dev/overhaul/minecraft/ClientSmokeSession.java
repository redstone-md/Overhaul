package dev.overhaul.minecraft;

/** Optional development driver. The normal adapter factory returns no session. */
public interface ClientSmokeSession extends AutoCloseable {
    void tick();
    @Override default void close() { }
}
