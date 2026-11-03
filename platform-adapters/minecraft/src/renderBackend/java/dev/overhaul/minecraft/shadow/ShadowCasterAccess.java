package dev.overhaul.minecraft.shadow;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;

/** World-renderer-owned extraction and temporary terrain selection. */
public interface ShadowCasterAccess {
    ShadowCasterFrame overhaul$shadowFrame();
    ObjectArrayList<SectionRenderDispatcher.RenderSection> overhaul$shadowSections();
    void overhaul$releaseShadows();
}
