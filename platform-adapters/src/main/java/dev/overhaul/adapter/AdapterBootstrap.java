package dev.overhaul.adapter;

/** Loader-neutral, server-safe startup evidence; owns no global engine instance. */
public final class AdapterBootstrap {
    private AdapterBootstrap() { }
    public static void initialize(String loader) {
        java.util.logging.Logger.getLogger("overhaul").info("Overhaul "+loader
                +" adapter loaded; lifecycle hooks registered, renderer replacement under development");
    }
}
