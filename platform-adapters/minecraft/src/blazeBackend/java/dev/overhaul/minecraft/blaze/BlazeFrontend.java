package dev.overhaul.minecraft.blaze;

import net.minecraft.client.Minecraft;

/** Resolves the actual selected client; no static renderer or world registry exists. */
public final class BlazeFrontend {
    private BlazeFrontend() {}
    public static boolean requested() { return Boolean.getBoolean("overhaul.blaze.enabled"); }
    public static BlazeRenderer current() {
        Minecraft client = Minecraft.getInstance();
        MinecraftBlazeAccess owner=client==null?null:(MinecraftBlazeAccess)client;
        BlazeRenderer renderer=owner==null?null:owner.overhaul$blazeRenderer();
        if(renderer==null&&(requested()||owner!=null&&owner.overhaul$blazeWasSelected())){
            throw new IllegalStateException("The selected Blaze3D device is unavailable or closed; OpenGL fallback is forbidden");
        }
        return renderer;
    }
    public static BlazeRenderer require() {
        BlazeRenderer renderer=current();
        if(renderer==null)throw new IllegalStateException("No owned Blaze3D device is selected");
        return renderer;
    }
}
