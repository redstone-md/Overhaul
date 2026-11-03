package dev.overhaul.minecraft.backendmixin;

import dev.overhaul.minecraft.model.ModelSubmitDomainAccess;
import dev.overhaul.minecraft.shader.SceneDrawDomain;
import java.util.Objects;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(ModelFeatureRenderer.Submit.class)
public abstract class ModelSubmitDomainMixin implements ModelSubmitDomainAccess {
    @Unique private SceneDrawDomain overhaul$domain = SceneDrawDomain.UNKNOWN;
    @Override public SceneDrawDomain overhaul$sceneDomain() { return overhaul$domain; }
    @Override public void overhaul$sceneDomain(SceneDrawDomain domain) {
        Objects.requireNonNull(domain, "domain");
        if (overhaul$domain != SceneDrawDomain.UNKNOWN && overhaul$domain != domain)
            throw new IllegalStateException("A deferred model submit cannot change scene origin");
        overhaul$domain = domain;
    }
}
