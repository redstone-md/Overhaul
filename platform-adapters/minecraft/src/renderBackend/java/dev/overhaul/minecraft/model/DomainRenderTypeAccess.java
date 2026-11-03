package dev.overhaul.minecraft.model;

import dev.overhaul.minecraft.shader.SceneDrawDomain;
import net.minecraft.client.renderer.rendertype.RenderType;

/** Instance-owned fallback variants retain the same authoritative RenderSetup. */
public interface DomainRenderTypeAccess {
    RenderType overhaul$forDomain(SceneDrawDomain domain);
    void overhaul$domainVariant(RenderType original, SceneDrawDomain domain);
}
