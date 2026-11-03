package dev.overhaul.minecraft.backendmixin;

import dev.overhaul.minecraft.model.DomainRenderTypeAccess;
import dev.overhaul.minecraft.shader.InstancedPipelineVariants;
import dev.overhaul.minecraft.shader.SceneDrawDomain;
import java.util.EnumMap;
import java.util.Objects;
import net.minecraft.client.renderer.oit.OitPipelineSet;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;

/** Domain variants reuse the exact original RenderSetup; only prepared pipeline metadata changes. */
@Mixin(RenderType.class)
public abstract class RenderTypeDomainMixin implements DomainRenderTypeAccess {
    @Shadow @Final private RenderSetup state;
    @Shadow @Final protected String name;
    @Unique private RenderType overhaul$original;
    @Unique private SceneDrawDomain overhaul$domain = SceneDrawDomain.UNKNOWN;
    @Unique private EnumMap<SceneDrawDomain, RenderType> overhaul$variants;
    @Unique private InstancedPipelineVariants.Domains overhaul$pipelines;

    @Override public RenderType overhaul$forDomain(SceneDrawDomain domain) {
        Objects.requireNonNull(domain);
        if (overhaul$domain == domain) return (RenderType) (Object) this;
        if (overhaul$original != null) return ((DomainRenderTypeAccess) overhaul$original).overhaul$forDomain(domain);
        if (overhaul$variants == null) overhaul$variants = new EnumMap<>(SceneDrawDomain.class);
        return overhaul$variants.computeIfAbsent(domain, key -> {
            RenderType variant = RenderTypeFactoryAccess.overhaul$create(name, state);
            ((DomainRenderTypeAccess) variant).overhaul$domainVariant((RenderType) (Object) this, key);
            return variant;
        });
    }

    @Override public void overhaul$domainVariant(RenderType original, SceneDrawDomain domain) {
        if (overhaul$original != null) throw new IllegalStateException("Render type domain is already assigned");
        if (domain == SceneDrawDomain.UNKNOWN) throw new IllegalArgumentException("Unknown domain needs no variant");
        overhaul$original = Objects.requireNonNull(original);
        overhaul$domain = Objects.requireNonNull(domain);
    }

    @Unique private InstancedPipelineVariants.Domains overhaul$pipelines() {
        if (overhaul$pipelines == null) overhaul$pipelines = new InstancedPipelineVariants.Domains();
        return overhaul$pipelines;
    }

    @Inject(method = "pipeline", at = @At("RETURN"), cancellable = true, require = 1)
    private void overhaul$tagPipeline(CallbackInfoReturnable<RenderPipeline> callback) {
        if (overhaul$domain != SceneDrawDomain.UNKNOWN)
            callback.setReturnValue(overhaul$pipelines().tag(callback.getReturnValue(), overhaul$domain));
    }

    @Inject(method = "prepare", at = @At("RETURN"), cancellable = true, require = 1)
    private void overhaul$tagPrepared(CallbackInfoReturnable<PreparedRenderType> callback) {
        if (overhaul$domain == SceneDrawDomain.UNKNOWN) return;
        var prepared = callback.getReturnValue();
        var pipelines = overhaul$pipelines();
        OitPipelineSet family = prepared.oitPipelineSet();
        if (family != null) family = new OitPipelineSet(pipelines.tag(family.depthBoundsPipeline(), overhaul$domain),
                pipelines.tag(family.transmittancePipeline(), overhaul$domain), pipelines.tag(family.accumulatePipeline(), overhaul$domain));
        callback.setReturnValue(new PreparedRenderType(prepared.name(), pipelines.tag(prepared.pipeline(), overhaul$domain),
                family, prepared.dynamicTransforms(), prepared.scissorState(), prepared.textures()));
    }
}
