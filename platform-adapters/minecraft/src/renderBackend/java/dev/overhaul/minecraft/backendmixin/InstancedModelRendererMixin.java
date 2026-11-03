package dev.overhaul.minecraft.backendmixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.overhaul.minecraft.model.ModelRendererAccess;
import dev.overhaul.minecraft.model.OverhaulModelRenderer;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.feature.FeatureRenderer;
import net.minecraft.client.renderer.feature.FeatureRendererMap;
import net.minecraft.client.renderer.feature.FeatureRendererType;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** The ordinary dispatcher owns phase dispatch and closure of the registered model renderer. */
@Mixin(FeatureRenderDispatcher.class)
public abstract class InstancedModelRendererMixin implements ModelRendererAccess {
    @Shadow @Final private FeatureRendererMap featureRenderers;

    @Override public OverhaulModelRenderer overhaul$models(){
        var renderer=featureRenderers.get(ModelFeatureRenderer.TYPE);
        return renderer instanceof OverhaulModelRenderer owned?owned:null;
    }
    @WrapOperation(method="<init>",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/feature/FeatureRendererMap;put(Lnet/minecraft/client/renderer/feature/FeatureRendererType;Lnet/minecraft/client/renderer/feature/FeatureRenderer;)V"))
    private void overhaul$registerModels(FeatureRendererMap registry,FeatureRendererType<?> type,FeatureRenderer<?> renderer,Operation<Void> original){
        if(type==ModelFeatureRenderer.TYPE){
            if(!(renderer instanceof ModelFeatureRenderer fallback))throw new IllegalStateException("Expected the pinned model feature renderer during registration");
            renderer=new OverhaulModelRenderer(fallback);
        }
        original.call(registry,type,renderer);
    }
}
