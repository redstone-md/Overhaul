package dev.overhaul.minecraft.shader;

import dev.overhaul.minecraft.platform.ModSemanticPolicy;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Properties;

/** Instance-owned eligibility for an optimization; unknown shader/mod semantics retain its ordinary rendering path. */
public final class ShaderResourceGate {
    private final ModSemanticPolicy mods=new ModSemanticPolicy();
    private final Properties expected=new Properties();
    private boolean dirty=true,reloading,certified;
    private long generation;
    private String reason="unverified";

    public ShaderResourceGate(String manifestResource) {
        try(InputStream input=ShaderResourceGate.class.getResourceAsStream(manifestResource)) {
            if(input==null)throw new IllegalStateException("Missing shader digest manifest");
            expected.load(input);
        } catch(Exception failure){expected.clear();reason=failure.toString();dirty=false;}
    }
    public long reloadStarted(){generation++;reloading=true;certified=false;reason="resource reload";return generation;}
    public void reloadFinished(long token,boolean success){if(token==generation){reloading=false;dirty=success;certified=false;reason=success?"unverified":"resource reload failed";}}
    public boolean eligible(ResourceManager resources) {
        if(reloading)return false;
        if(!dirty)return certified;
        dirty=false;certified=false;
        try {
            if(expected.size()<2)throw new IllegalStateException("Incomplete shader manifest");
            mods.requireKnownMods();
            var digest=MessageDigest.getInstance("SHA-256");
            for(String path:expected.stringPropertyNames()) {
                try(InputStream input=resources.open(Identifier.parse(path))) {
                    byte[] bytes=input.readNBytes(2*1024*1024+1);
                    if(bytes.length>2*1024*1024||!HexFormat.of().formatHex(digest.digest(bytes)).equals(expected.getProperty(path)))
                        throw new IllegalStateException("Modified shader resource: "+path);
                }
            }
            certified=true;reason="pinned shader resources and known mods";
        }catch(Exception|LinkageError failure){reason=failure.toString();}
        return certified;
    }
    public String reason(){return reason;}
    public long generation(){return generation;}
}
