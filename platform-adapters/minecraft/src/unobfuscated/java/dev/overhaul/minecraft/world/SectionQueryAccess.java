package dev.overhaul.minecraft.world;

import java.util.Map;

/** Instance-owned diagnostics and a scoped original traversal oracle; neither exposes mutable storage. */
public interface SectionQueryAccess {
    Map<String,Long> overhaul$queryStatistics();
    void overhaul$withVanilla(Runnable action);
}
