package dev.overhaul.minecraft.shader;

import dev.overhaul.shader.pack.ShaderPackSource;
import java.io.IOException;
import java.util.List;

/** Shipped real caster shaders; the normal world terrain shaders remain the receiver authority. */
public final class OwnedCsmPack {
    public static final String NAME="@overhaul-cascaded-shadows";
    private OwnedCsmPack(){}
    public static ShaderPackSource open()throws IOException{
        return ShaderPackSource.packaged(OwnedCsmPack.class,"/assets/overhaul/shaderpacks/csm/shaders",List.of("shadow.vsh","shadow.fsh"));
    }
}
