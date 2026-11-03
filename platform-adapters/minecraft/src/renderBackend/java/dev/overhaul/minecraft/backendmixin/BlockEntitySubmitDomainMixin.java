package dev.overhaul.minecraft.backendmixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.overhaul.minecraft.model.SubmitDomainSource;
import dev.overhaul.minecraft.shader.SceneDrawDomain;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntitySubmitDomainMixin {
    @WrapOperation(method = "submit", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderer;submit(Lnet/minecraft/client/renderer/blockentity/state/BlockEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V"), require = 1)
    private void overhaul$blockEntityScope(BlockEntityRenderer<?, ?> renderer, BlockEntityRenderState state,
            PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera, Operation<Void> original) {
        if (!(collector instanceof SubmitDomainSource source)) { original.call(renderer, state, pose, collector, camera); return; }
        var domains = source.overhaul$submitDomains();
        var previous = domains.enter(SceneDrawDomain.BLOCK_ENTITY);
        try { original.call(renderer, state, pose, collector, camera); }
        finally { domains.restore(previous); }
    }
}
