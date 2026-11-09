package dev.overhaul.minecraft.blaze.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.overhaul.minecraft.blaze.BlazeUpscale;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draws the world and hand into FSR2's smaller target under a jittered projection, then upscales into the real one. */
@Mixin(GameRenderer.class)
abstract class GameRendererUpscale121Mixin {
    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void overhaul$scaleDown(DeltaTracker delta, CallbackInfo info) {
        Minecraft client = Minecraft.getInstance();
        overhaul$swap(client, BlazeUpscale.get().begin(client));
    }

    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V"))
    private void overhaul$world(LevelRenderer level, DeltaTracker delta, boolean outline, Camera camera, GameRenderer game, LightTexture light,
                                Matrix4f view, Matrix4f projection, Operation<Void> original) {
        Vec3 position = camera.getPosition();
        BlazeUpscale.get().capture(view, projection, position.x, position.y, position.z);
        original.call(level, delta, outline, camera, game, light, view, projection);
        BlazeUpscale.get().worldDrawn();
    }

    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void overhaul$scaleUp(DeltaTracker delta, CallbackInfo info) {
        overhaul$swap(Minecraft.getInstance(), BlazeUpscale.get().end());
    }

    @ModifyReturnValue(method = "getProjectionMatrix", at = @At("RETURN"))
    private Matrix4f overhaul$jitter(Matrix4f projection) {
        return BlazeUpscale.get().jitter(projection);
    }

    @Unique
    private static void overhaul$swap(Minecraft client, RenderTarget target) {
        if (target == null) return;
        ((MinecraftMainTarget121Access) client).overhaul$mainTarget(target);
        target.bindWrite(true);
    }
}
