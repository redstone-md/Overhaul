package dev.overhaul.minecraft.blaze.mixin;

import dev.overhaul.minecraft.blaze.shader.BlazePackFrame;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.server.packs.resources.ResourceManager;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The world's frame boundaries, which are where a shader pack's fullscreen passes belong. */
@Mixin(LevelRenderer.class)
abstract class LevelRendererShaderPack121Mixin {
    @Shadow
    private ViewArea viewArea;
    @Unique
    private BlazePackFrame overhaul$pack;

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void overhaul$beginPack(DeltaTracker delta, boolean outline, Camera camera, GameRenderer game, LightTexture light, Matrix4f view, Matrix4f projection, CallbackInfo callback) {
        if (overhaul$pack == null) overhaul$pack = BlazePackFrame.open(Minecraft.getInstance());
        if (overhaul$pack != null) overhaul$pack.begin(camera, delta.getGameTimeDeltaPartialTick(false), view, projection, light, viewArea);
    }

    @Inject(method = "renderSectionLayer", at = @At("HEAD"))
    private void overhaul$opaqueDone(RenderType type, double x, double y, double z, Matrix4f view, Matrix4f projection, CallbackInfo callback) {
        if (overhaul$pack != null && type == RenderType.translucent()) overhaul$pack.afterOpaque();
    }

    @Inject(method = "renderClouds", at = @At("HEAD"), cancellable = true)
    private void overhaul$packClouds(CallbackInfo callback) {
        if (overhaul$pack != null && overhaul$pack.hidesClouds()) callback.cancel();
    }

    @Inject(method = "renderSnowAndRain", at = @At("HEAD"))
    private void overhaul$weatherBegins(CallbackInfo callback) {
        if (overhaul$pack != null) overhaul$pack.weather(true);
    }

    @Inject(method = "renderSnowAndRain", at = @At("RETURN"))
    private void overhaul$weatherEnds(CallbackInfo callback) {
        if (overhaul$pack != null) overhaul$pack.weather(false);
    }

    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void overhaul$finishPack(DeltaTracker delta, boolean outline, Camera camera, GameRenderer game, LightTexture light, Matrix4f view, Matrix4f projection, CallbackInfo callback) {
        if (overhaul$pack == null) return;
        overhaul$pack.hand(game, camera, delta.getGameTimeDeltaPartialTick(true), view);
        overhaul$pack.finish();
    }

    @Inject(method = "onResourceManagerReload", at = @At("TAIL"))
    private void overhaul$reloadPack(ResourceManager resources, CallbackInfo callback) {
        if (overhaul$pack != null) overhaul$pack.invalidate();
    }

    @Inject(method = "setLevel", at = @At("TAIL"))
    private void overhaul$levelChanged(ClientLevel level, CallbackInfo callback) {
        if (overhaul$pack != null) overhaul$pack.invalidate();
    }
}
