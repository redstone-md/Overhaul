package dev.overhaul.minecraft.blaze;

import net.minecraft.client.renderer.LevelRenderer;

/** What the performance overlay reads from the game where 1.20 and 1.21 name it differently. */
final class BlazeOverlayTier {
    private BlazeOverlayTier() {}

    static int renderedSections(LevelRenderer level) {
        return level.countRenderedSections();
    }
}
