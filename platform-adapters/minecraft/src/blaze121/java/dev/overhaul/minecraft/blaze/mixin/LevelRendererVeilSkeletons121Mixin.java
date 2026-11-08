package dev.overhaul.minecraft.blaze.mixin;

import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Carries no code: its higher priority makes it apply after Veil's mixin, and the plugin's post-apply step then
 * swaps the entity list Veil's skeleton tick walks in the merged class (see {@code VeilSkeletonPatch}).
 */
@Mixin(value = LevelRenderer.class, priority = 1500)
abstract class LevelRendererVeilSkeletons121Mixin {
}
