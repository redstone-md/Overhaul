package dev.overhaul.minecraft.smoke;

import dev.overhaul.adapter.SectionKey;
import net.minecraft.client.Minecraft;
import java.util.concurrent.CompletableFuture;

/** Capture runs on the actual world owner; returned work must use immutable owned data only. */
@FunctionalInterface
public interface WorldSmokeProbe extends AutoCloseable {
    CompletableFuture<String> capture(Minecraft minecraft,SectionKey section);
    @Override default void close() { }
}
