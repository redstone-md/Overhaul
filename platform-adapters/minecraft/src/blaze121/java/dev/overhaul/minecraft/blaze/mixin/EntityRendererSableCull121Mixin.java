package dev.overhaul.minecraft.blaze.mixin;

import net.minecraft.client.renderer.entity.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Carries no code: its higher priority makes it apply after Sable's mixin, and the plugin's post-apply step then
 * guards Sable's cull handler in the merged class (see {@code SableCullPatch}).
 */
@Mixin(value = EntityRenderer.class, priority = 1500)
abstract class EntityRendererSableCull121Mixin {
}
