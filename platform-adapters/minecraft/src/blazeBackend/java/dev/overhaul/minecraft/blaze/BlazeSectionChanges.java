package dev.overhaul.minecraft.blaze;

import java.util.concurrent.atomic.AtomicLong;

/** Counters that say when a copy taken from the game's sections went stale, whichever game version owns them. */
public final class BlazeSectionChanges {
    /** Moves when a section's contents or dirty mark change, on any thread, or when the visible list is refilled. */
    private static final AtomicLong CHANGES = new AtomicLong();
    /** Sections are reused for other places when the camera moves; render thread only. */
    private static int originMoves;

    private BlazeSectionChanges() {}

    public static void changed() {
        CHANGES.incrementAndGet();
    }

    /** Read before the contents it guards, so a change racing the read moves it again. */
    public static long changes() {
        return CHANGES.get();
    }

    public static void originMoved() {
        originMoves++;
    }

    /** Changes whenever any section takes another place, so a copy of the origins taken at another value is stale. */
    public static int originMoves() {
        return originMoves;
    }
}
