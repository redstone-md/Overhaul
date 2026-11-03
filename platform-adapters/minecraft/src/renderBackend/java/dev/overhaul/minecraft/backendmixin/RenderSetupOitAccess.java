package dev.overhaul.minecraft.backendmixin;

import net.minecraft.client.renderer.oit.OitPipelineSet;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes only the immutable pipeline family required before model animation. */
@Mixin(RenderSetup.class)
public interface RenderSetupOitAccess {
    @Accessor("oitPipelineSet") OitPipelineSet overhaul$oitPipelines();
}
