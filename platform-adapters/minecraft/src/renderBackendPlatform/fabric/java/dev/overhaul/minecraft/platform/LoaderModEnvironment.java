package dev.overhaul.minecraft.platform;

import net.fabricmc.loader.api.FabricLoader;
import java.util.HashSet;
import java.util.Set;

/** Fabric-specific metadata access is confined to this selected production source root. */
final class LoaderModEnvironment implements ModEnvironment {
    @Override public String loaderName(){return "Fabric";}
    @Override public Set<String> infrastructureModIds(){return Set.of("fabricloader","mixinextras");}
    @Override public Set<String> loadedModIds(){
        var ids=new HashSet<String>();
        for(var mod:FabricLoader.getInstance().getAllMods())ids.add(mod.getMetadata().getId());
        return Set.copyOf(ids);
    }
}
