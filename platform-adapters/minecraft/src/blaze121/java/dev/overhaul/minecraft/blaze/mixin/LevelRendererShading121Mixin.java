package dev.overhaul.minecraft.blaze.mixin;

import dev.overhaul.minecraft.blaze.BlazeShading;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.world.phys.Vec3;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Lets Overhaul's own shading collect solid and cutout terrain's surfaces, then lights them before anything else draws. */
@Mixin(LevelRenderer.class)
abstract class LevelRendererShading121Mixin {
    @Shadow
    private ViewArea viewArea;

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void overhaul$startShading(DeltaTracker delta, boolean outline, Camera camera, GameRenderer game, LightTexture light, Matrix4f view, Matrix4f projection,
                                       CallbackInfo callback) {
        BlazeShading.get().frameStarted(Minecraft.getInstance(), camera, delta.getGameTimeDeltaPartialTick(false));
    }

    // The third terrain layer the frame draws is cutout, the last of the opaque ones.
    @Inject(method = "renderLevel", at = @At(value = "INVOKE", ordinal = 2, shift = At.Shift.AFTER,
            target = "Lnet/minecraft/client/renderer/LevelRenderer;renderSectionLayer(Lnet/minecraft/client/renderer/RenderType;DDDLorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V"))
    private void overhaul$shadeTerrain(DeltaTracker delta, boolean outline, Camera camera, GameRenderer game, LightTexture light, Matrix4f view, Matrix4f projection,
                                       CallbackInfo callback) {
        BlazeShading.get().terrainDrawn(Minecraft.getInstance(), viewArea, camera, delta.getGameTimeDeltaPartialTick(false), view, projection);
    }

    // The sky lit here already carries clouds of its own.
    @Inject(method = "renderClouds", at = @At("HEAD"), cancellable = true)
    private void overhaul$ownClouds(CallbackInfo callback) {
        if (BlazeShading.get().cloudsDrawn()) callback.cancel();
    }

    // Clouds draw after the terrain is lit, so they can take the same low sun.
    @ModifyExpressionValue(method = "renderClouds", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;getCloudColor(F)Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 overhaul$lightClouds(Vec3 color, @Local(argsOnly = true) float partialTick) {
        return BlazeShading.get().clouds(Minecraft.getInstance().level, color, partialTick);
    }
}
