package dev.overhaul.minecraft.platform;

import net.neoforged.fml.ModList;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** NeoForge inventory access remains separate from Forge's different 26.3 API. */
final class LoaderModEnvironment implements ModEnvironment {
    @Override public String loaderName(){return "NeoForge";}
    @Override public Set<String> infrastructureModIds(){return Set.of("neoforge","mixinextras");}
    @Override public Set<String> loadedModIds(){
        var mods=Objects.requireNonNull(ModList.get(),"NeoForge mod inventory is not initialized");
        var ids=new HashSet<String>();
        for(var mod:mods.getMods())ids.add(mod.getModId());
        return Set.copyOf(ids);
    }
}
