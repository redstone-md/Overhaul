package dev.overhaul.minecraft.shader;

/** Trusted typed marker implemented only by the owned central pipeline clone. */
public interface SceneDomainPipeline {
    SceneDrawDomain sceneDomain();
}
