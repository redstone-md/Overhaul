package dev.overhaul.minecraft.backendmixin;

import dev.overhaul.minecraft.shadow.ShadowFeatureDispatcherAccess;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(FeatureRenderDispatcher.class)
public abstract class ShadowFeatureDispatcherMixin implements ShadowFeatureDispatcherAccess {
    @Shadow @Final @Mutable private StagedVertexBuffer stagedVertexBuffer;
    @Unique private boolean overhaul$ownsShadowStaging;
    @Override public void overhaul$ownShadowStaging(StagedVertexBuffer staging){
        if(overhaul$ownsShadowStaging)throw new IllegalStateException("Shadow staging ownership is already installed");
        stagedVertexBuffer=java.util.Objects.requireNonNull(staging);overhaul$ownsShadowStaging=true;
    }
}
