package dev.overhaul.minecraft.hud;

import com.mojang.blaze3d.pipeline.RenderTarget;

/** Forge and NeoForge let mods ask the main target for a stencil buffer; the cache target must carry one too. */
final class HudStencil {
    private HudStencil() {
    }

    static void match(RenderTarget main, RenderTarget cache) {
        if (main.isStencilEnabled() && !cache.isStencilEnabled()) cache.enableStencil();
    }
}
