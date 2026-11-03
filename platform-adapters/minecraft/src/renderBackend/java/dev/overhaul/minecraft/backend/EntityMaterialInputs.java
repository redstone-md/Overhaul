package dev.overhaul.minecraft.backend;

import dev.overhaul.shader.compat.UnsupportedShaderException;
import dev.overhaul.shader.compat.ShaderMainWrapper;
import java.util.regex.Pattern;

/** Overlay is per vertex/instance; turning entityColor into a varying keeps batches with different hurt states exact. */
final class EntityMaterialInputs {
    static final String OVERLAY_SAMPLER="irisCompatOverlaySampler",OVERLAY_COLOR="irisCompatEntityOverlay";
    static final String DISSOLVE_SAMPLER="irisCompatDissolveSampler",DISSOLVE_UV="irisCompatDissolveUv",SOURCE_ALPHA="irisCompatSourceAlpha";
    private static final Pattern ENTITY_COLOR=Pattern.compile("\\buniform\\s+(\\w+)\\s+entityColor\\s*;");
    private boolean overlay,dissolve;
    SceneVertexInputs.Sources prepare(SceneVertexInputs.Sources sources,boolean enabled,boolean dissolve){
        if(!enabled)return sources;
        this.dissolve=dissolve;
        overlay=ENTITY_COLOR.matcher(sources.vertex()).find()||ENTITY_COLOR.matcher(sources.fragment()).find();
        for(String name:new String[]{OVERLAY_COLOR,OVERLAY_SAMPLER,DISSOLVE_SAMPLER,DISSOLVE_UV,SOURCE_ALPHA})if(sources.vertex().contains(name)||sources.fragment().contains(name))throw new UnsupportedShaderException("Reserved entity material interface identifier");
        String vs=overlay?convert(sources.vertex()):sources.vertex(),fs=overlay?convert(sources.fragment()):sources.fragment();
        if(dissolve){String varyings="varying vec2 "+DISSOLVE_UV+";\nvarying float "+SOURCE_ALPHA+";\n";vs=varyings+vs;fs="uniform sampler2D "+DISSOLVE_SAMPLER+";\n"+varyings+fs;}
        return new SceneVertexInputs.Sources(vs,fs);
    }
    private static String convert(String source){
        var matcher=ENTITY_COLOR.matcher(source);while(matcher.find())if(!matcher.group(1).equals("vec4"))throw new UnsupportedShaderException("entityColor must be vec4");
        String body=ENTITY_COLOR.matcher(source).replaceAll("").replaceAll("\\bentityColor\\b",OVERLAY_COLOR);
        return "varying vec4 "+OVERLAY_COLOR+";\n"+body;
    }
    boolean overlay(){return overlay;}
    boolean dissolve(){return dissolve;}
    String fragment(String source){return !dissolve?source:ShaderMainWrapper.wrap(source,"overhaulDissolveEntry","","if ("+SOURCE_ALPHA+" < texture(overhaulSampler_"+DISSOLVE_SAMPLER+","+DISSOLVE_UV+").a) discard;");}
}
