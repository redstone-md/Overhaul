package dev.overhaul.minecraft.smoke;

import dev.overhaul.adapter.SectionKey;
import net.minecraft.client.Minecraft;
import java.util.concurrent.CompletableFuture;

/** Capture/tick run on the world owner; off-thread work must use immutable owned data only. */
@FunctionalInterface
public interface WorldSmokeProbe extends AutoCloseable {
    CompletableFuture<String> capture(Minecraft minecraft,SectionKey section);
    default void tick() { }
    default void disconnected() { }
    @Override default void close() { }
}
