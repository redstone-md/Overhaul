package dev.overhaul.minecraft.platform;

import java.util.Set;

/** Exactly one loader source root implements this; shared rendering never links another loader API. */
interface ModEnvironment {
    String loaderName();
    Set<String> loadedModIds();
    Set<String> infrastructureModIds();
}
