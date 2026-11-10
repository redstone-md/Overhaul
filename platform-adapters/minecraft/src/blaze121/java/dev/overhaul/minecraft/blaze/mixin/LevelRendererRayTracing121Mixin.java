package dev.overhaul.minecraft.blaze.mixin;

import dev.overhaul.minecraft.blaze.BlazeRayTracing;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.ViewArea;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Lights the finished world with ray queries, before the hand and the GUI draw over it. */
@Mixin(LevelRenderer.class)
abstract class LevelRendererRayTracing121Mixin {
    @Shadow
    private ViewArea viewArea;

    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void overhaul$traceRays(DeltaTracker delta, boolean outline, Camera camera, GameRenderer game, LightTexture light, Matrix4f view, Matrix4f projection,
                                    CallbackInfo callback) {
        BlazeRayTracing.get().worldDrawn(Minecraft.getInstance(), viewArea, camera, delta.getGameTimeDeltaPartialTick(false), view, projection);
    }
}
