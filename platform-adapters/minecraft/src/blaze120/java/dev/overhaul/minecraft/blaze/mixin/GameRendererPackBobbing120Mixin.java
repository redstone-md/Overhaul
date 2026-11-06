package dev.overhaul.minecraft.blaze.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.overhaul.minecraft.blaze.shader.BlazePackRequest;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Moves view bobbing and hurt shake out of the projection and into the view while a shader pack runs, as Iris does.
 * Packs rebuild view positions from the projection's diagonal alone, so a bobbed projection smears every deferred
 * pass while the player walks. The clip-space result is the same either way.
 */
@Mixin(GameRenderer.class)
abstract class GameRendererPackBobbing120Mixin {
    @Unique private final PoseStack overhaul$bob = new PoseStack();
    @Unique private boolean overhaul$moved;

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void overhaul$resetBob(CallbackInfo info) {
        overhaul$moved = BlazePackRequest.running();
        overhaul$bob.setIdentity();
    }

    @Redirect(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;bobHurt(Lcom/mojang/blaze3d/vertex/PoseStack;F)V"))
    private void overhaul$bobHurt(GameRenderer game, PoseStack pose, float partial) {
        ((GameRendererHand120Access) game).overhaul$bobHurt(overhaul$moved ? overhaul$bob : pose, partial);
    }

    @Redirect(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;bobView(Lcom/mojang/blaze3d/vertex/PoseStack;F)V"))
    private void overhaul$bobView(GameRenderer game, PoseStack pose, float partial) {
        ((GameRendererHand120Access) game).overhaul$bobView(overhaul$moved ? overhaul$bob : pose, partial);
    }

    /** The world's pose gets the bob in front of the camera rotation; a copy, since the hand pass reuses the stack. */
    @ModifyArg(method = "renderLevel", index = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lcom/mojang/blaze3d/vertex/PoseStack;FJZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;)V"))
    private PoseStack overhaul$bobbedView(PoseStack pose) {
        if (!overhaul$moved) return pose;
        PoseStack bobbed = new PoseStack();
        bobbed.last().pose().set(overhaul$bob.last().pose()).mul(pose.last().pose());
        bobbed.last().normal().set(pose.last().normal());
        return bobbed;
    }
}
