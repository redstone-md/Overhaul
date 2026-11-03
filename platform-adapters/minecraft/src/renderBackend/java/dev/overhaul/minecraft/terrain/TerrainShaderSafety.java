package dev.overhaul.minecraft.terrain;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Properties;
import java.util.Set;

/** Unknown shader/mod semantics disable occlusion; geometry remains fully rendered. */
final class TerrainShaderSafety {
    private static final Set<String> KNOWN_MODS=Set.of("overhaul","minecraft","java","fabricloader","mixinextras");
    private final Properties expected=new Properties();
    private boolean dirty=true,reloading,certified;
    private long generation;
    private String reason="unverified";

    TerrainShaderSafety() {
        try(InputStream input=TerrainShaderSafety.class.getResourceAsStream("/overhaul-terrain-shaders.properties")) {
            if(input==null)throw new IllegalStateException("Missing shader digest manifest");
            expected.load(input);
        } catch(Exception failure){reason=failure.toString();dirty=false;}
    }
    long reloadStarted(){generation++;reloading=true;certified=false;reason="resource reload";return generation;}
    void reloadFinished(long token,boolean success){if(token==generation){reloading=false;dirty=success;certified=false;reason=success?"unverified":"resource reload failed";}}
    boolean eligible(ResourceManager resources) {
        if(reloading)return false;
        if(!dirty)return certified;
        dirty=false;certified=false;
        try {
            if(expected.size()<2)throw new IllegalStateException("Incomplete shader manifest");
            for(var mod:FabricLoader.getInstance().getAllMods())if(!KNOWN_MODS.contains(mod.getMetadata().getId()))
                throw new IllegalStateException("Unknown shader-mod semantics: "+mod.getMetadata().getId());
            var digest=MessageDigest.getInstance("SHA-256");
            for(String path:expected.stringPropertyNames()) {
                try(InputStream input=resources.open(Identifier.withDefaultNamespace(path))) {
                    byte[] bytes=input.readNBytes(2*1024*1024+1);
                    if(bytes.length>2*1024*1024||!HexFormat.of().formatHex(digest.digest(bytes)).equals(expected.getProperty(path)))
                        throw new IllegalStateException("Modified terrain shader resource: "+path);
                }
            }
            certified=true;reason="pinned terrain shaders and known mods";
        }catch(Exception|LinkageError failure){reason=failure.toString();}
        return certified;
    }
    String reason(){return reason;}
    long generation(){return generation;}
}
