package dev.overhaul.minecraft.backend;

import java.util.Map;

/** Test-only access keeps native recording internals out of the production public interface. */
public final class NativeBackendStatistics {
    private NativeBackendStatistics(){}
    public static Map<String,Long> draws(OverhaulDeviceBackend backend){return backend.drawStatistics();}
}
