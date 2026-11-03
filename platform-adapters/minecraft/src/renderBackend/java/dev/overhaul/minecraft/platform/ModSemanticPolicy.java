package dev.overhaul.minecraft.platform;

import java.util.Set;

/** One authoritative semantic boundary for shared shader-dependent optimizations. */
public final class ModSemanticPolicy {
    private static final Set<String> OWNED_AND_GAME_MODS=Set.of("overhaul","overhaul_render_runtime","minecraft","java");
    private final ModEnvironment environment=new LoaderModEnvironment();

    public void requireKnownMods(){
        var loaded=environment.loadedModIds();
        if(loaded.isEmpty())throw new IllegalStateException(environment.loaderName()+" mod inventory is not available");
        var infrastructure=environment.infrastructureModIds();
        for(String id:loaded)if(!OWNED_AND_GAME_MODS.contains(id)&&!infrastructure.contains(id))
            throw new IllegalStateException("Unknown shader-mod semantics on "+environment.loaderName()+": "+id);
    }
}
