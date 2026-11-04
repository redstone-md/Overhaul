package dev.overhaul.minecraft.backendmixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import dev.overhaul.minecraft.backend.OverhaulGpuDevice;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import java.util.Optional;
import java.util.OptionalDouble;

/** Deferred reads opaque MRTs only after their native pass ends, then transparency opens the current ping-pong targets. */
@Mixin(LevelRenderer.class)
public abstract class PackWorldPassMixin {
    @Shadow @Final private GameRenderer gameRenderer;
    @Unique private RenderPass overhaul$finishedOpaque;

    @WrapOperation(method="lambda$addMainPass$0",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/LevelRenderer;executeClassicTransparency(Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;Lcom/mojang/renderpearl/api/commands/RenderPass;)V"),require=1,allow=1)
    private void overhaul$deferredBoundary(LevelRenderer renderer,ChunkSectionsToRender sections,
            FeatureRenderDispatcher.PreparedFrame features,RenderPass opaque,Operation<Void> original){
        if(!(RenderSystem.getDevice() instanceof OverhaulGpuDevice device)||!device.nativeFrames().shaderPacks().ownsWorldPasses()){
            original.call(renderer,sections,features,opaque);return;
        }
        if(overhaul$finishedOpaque!=null)throw new IllegalStateException("Nested shader-pack world passes");
        // The original try-with-resources owns this exact pass. Its later normal/exceptional close is suppressed once.
        overhaul$finishedOpaque=opaque;opaque.close();device.nativeFrames().shaderPacks().afterOpaque();
        RenderTarget target=gameRenderer.mainRenderTarget();
        try(RenderPass translucent=RenderSystem.getDevice().createCommandEncoder().createRenderPass(()->"Overhaul translucent world",
                target.getColorTextureView(),Optional.empty(),target.getDepthTextureView(),OptionalDouble.empty())){
            RenderSystem.bindDefaultUniforms(translucent);original.call(renderer,sections,features,translucent);
        }
    }

    @WrapOperation(method="lambda$addMainPass$0",at=@At(value="INVOKE",target="Lcom/mojang/renderpearl/api/commands/RenderPass;close()V"),require=2,allow=2)
    private void overhaul$closeOnce(RenderPass pass,Operation<Void> original){
        if(pass==overhaul$finishedOpaque){overhaul$finishedOpaque=null;return;}
        original.call(pass);
    }
}
