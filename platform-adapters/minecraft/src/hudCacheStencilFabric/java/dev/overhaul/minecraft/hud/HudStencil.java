package dev.overhaul.minecraft.hud;

import com.mojang.blaze3d.pipeline.RenderTarget;

/** Vanilla render targets on Fabric have no stencil buffer, so the cache target has nothing to match. */
final class HudStencil {
    private HudStencil() {
    }

    static void match(RenderTarget main, RenderTarget cache) {
    }
}
