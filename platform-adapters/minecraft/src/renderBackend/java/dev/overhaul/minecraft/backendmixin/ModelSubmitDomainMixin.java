package dev.overhaul.minecraft.backendmixin;

import dev.overhaul.minecraft.model.ModelSubmitDomainAccess;
import dev.overhaul.minecraft.shader.SceneDrawDomain;
import java.util.Objects;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.feature.BlockModelFeatureRenderer;
import net.minecraft.client.renderer.feature.CustomFeatureRenderer;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin({ModelFeatureRenderer.Submit.class, BlockModelFeatureRenderer.Submit.class,
        CustomFeatureRenderer.Submit.class, ItemFeatureRenderer.Submit.class})
public abstract class ModelSubmitDomainMixin implements ModelSubmitDomainAccess {
    @Unique private SceneDrawDomain overhaul$domain = SceneDrawDomain.UNKNOWN;
    @Override public SceneDrawDomain overhaul$sceneDomain() { return overhaul$domain; }
    @Override public void overhaul$sceneDomain(SceneDrawDomain domain) {
        Objects.requireNonNull(domain, "domain");
        if (overhaul$domain != SceneDrawDomain.UNKNOWN && overhaul$domain != domain)
            throw new IllegalStateException("A deferred geometry submit cannot change scene origin");
        overhaul$domain = domain;
    }
}
