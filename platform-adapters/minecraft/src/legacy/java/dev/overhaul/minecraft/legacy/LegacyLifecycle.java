package dev.overhaul.minecraft.legacy;

import dev.overhaul.adapter.ClientRuntime;
import dev.overhaul.adapter.ServerRuntime;

/** Stateless bridge. Runtime objects live in fields injected into the owning game instance. */
public final class LegacyLifecycle {
    private LegacyLifecycle() { }

    public static ClientRuntime clientTick(ClientRuntime runtime) {
        if (runtime==null) runtime=new ClientRuntime(Boolean.getBoolean("overhaul.diagnostics.enable"));
        runtime.tick(); return runtime;
    }

    public static ServerRuntime serverTick(ServerRuntime runtime) {
        if (runtime==null) runtime=new ServerRuntime(1024,500_000);
        runtime.tick(); return runtime;
    }

    public static void closeClient(ClientRuntime runtime) { if (runtime!=null) runtime.close(); }
    public static void closeServer(ServerRuntime runtime) { if (runtime!=null) runtime.close(); }
}
