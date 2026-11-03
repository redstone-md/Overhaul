package dev.overhaul.minecraft.backendmixin;

import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads immutable setup metadata without preparing textures or dynamic transforms. */
@Mixin(RenderType.class)
public interface RenderTypeSetupAccess {
    @Accessor("state") RenderSetup overhaul$setup();
}
