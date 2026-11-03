package dev.overhaul.minecraft.platform;

import net.minecraftforge.fml.ModList;
import java.util.HashSet;
import java.util.Set;

/** Forge 26.3 exposes its inventory through the static ModList API. */
final class LoaderModEnvironment implements ModEnvironment {
    @Override public String loaderName(){return "Forge";}
    @Override public Set<String> infrastructureModIds(){return Set.of("forge","mixinextras");}
    @Override public Set<String> loadedModIds(){
        var ids=new HashSet<String>();
        for(var mod:ModList.getMods())ids.add(mod.getModId());
        return Set.copyOf(ids);
    }
}
