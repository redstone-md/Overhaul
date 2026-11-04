package dev.overhaul.minecraft.shader;

import java.util.Objects;
import dev.overhaul.shader.pack.CascadeShadowSettings;
import net.minecraft.client.Minecraft;

/** One immutable request key shared by extraction and installation; world equality is deliberately identity. */
public record ShaderPackConfiguration(Object world,Object dimension,String requested,String options,String defines,CascadeShadowSettings cascades) {
    public ShaderPackConfiguration(Object world,Object dimension,String requested,String options,String defines){this(world,dimension,requested,options,defines,null);}
    public ShaderPackConfiguration{Objects.requireNonNull(requested);Objects.requireNonNull(options);Objects.requireNonNull(defines);}
    public static ShaderPackConfiguration capture(Minecraft minecraft){
        var world=minecraft.level;
        String requested=System.getProperty("overhaul.shaderPack","").trim();CascadeShadowSettings cascades=requested.isEmpty()?CascadeShadowSettings.configured():null;
        return new ShaderPackConfiguration(world,world==null?null:world.dimension(),
                cascades==null?requested:OwnedCsmPack.NAME,System.getProperty("overhaul.shaderPackOptions",""),
                System.getProperty("overhaul.shaderPackDefines",""),cascades);
    }
    public boolean configured(){return !requested.isEmpty();}
    @Override public boolean equals(Object other){
        return other instanceof ShaderPackConfiguration key&&world==key.world&&Objects.equals(dimension,key.dimension)
                &&requested.equals(key.requested)&&options.equals(key.options)&&defines.equals(key.defines)&&Objects.equals(cascades,key.cascades);
    }
    @Override public int hashCode(){return 31*System.identityHashCode(world)+Objects.hash(dimension,requested,options,defines,cascades);}
}
