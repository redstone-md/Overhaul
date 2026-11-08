package dev.overhaul.minecraft.blaze.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.overhaul.minecraft.blaze.shader.BlazePackHand;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Skips the game's own hand pass when a shader pack already drew the hand through gbuffers_hand this frame. */
@Mixin(GameRenderer.class)
abstract class GameRendererPackHand120Mixin {
    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void overhaul$handFrame(CallbackInfo info) {
        BlazePackHand.frameStarts();
    }

    @Redirect(method = "renderItemInHand", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderHandsWithItems(FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lnet/minecraft/client/player/LocalPlayer;I)V"))
    private void overhaul$packHand(ItemInHandRenderer hands, float partial, PoseStack pose, MultiBufferSource.BufferSource buffers, LocalPlayer player, int light) {
        if (!BlazePackHand.takeDrawn()) hands.renderHandsWithItems(partial, pose, buffers, player, light);
    }
}
