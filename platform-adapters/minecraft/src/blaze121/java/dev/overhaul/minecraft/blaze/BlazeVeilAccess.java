package dev.overhaul.minecraft.blaze;

import foundry.veil.impl.client.render.shader.program.ShaderTextureCache;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;

/** What the native path reads from Veil's objects; the mixins add these to them. */
public final class BlazeVeilAccess {
    private BlazeVeilAccess() {}

    public interface Stage {
        int overhaul$id();
    }

    public interface Program {
        Object2ObjectMap<String, ?> overhaul$uniforms();

        ShaderTextureCache overhaul$textures();
    }
}
