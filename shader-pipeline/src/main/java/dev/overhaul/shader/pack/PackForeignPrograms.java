package dev.overhaul.shader.pack;

import java.util.Optional;

/** Program name families that only another mod's renderer runs; a pack ships them alongside its own programs. */
public final class PackForeignPrograms {
    private PackForeignPrograms() {}

    /** The mod that runs this bare program name, when it is not one of this pipeline's programs. */
    public static Optional<String> owner(String name) {
        if (name.startsWith("dh_")) return Optional.of("Distant Horizons");
        if (name.startsWith("clrwl_")) return Optional.of("Colorwheel");
        return Optional.empty();
    }
}
