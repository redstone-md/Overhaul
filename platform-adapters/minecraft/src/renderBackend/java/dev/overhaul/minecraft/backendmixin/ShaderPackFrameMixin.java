package dev.overhaul.minecraft.backendmixin;

import dev.overhaul.minecraft.shader.ShaderPackFrameRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The ordinary world frame owns capture, execution, reload invalidation and resource closure. */
@Mixin(GameRenderer.class)
public abstract class ShaderPackFrameMixin {
    @Shadow @Final private Minecraft minecraft;
    @Unique private ShaderPackFrameRenderer overhaul$shaderPack;
    @ModifyArg(method="renderLevel",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"),index=0)
    private Matrix4f overhaul$capturePackProjection(Matrix4f matrix){
        if(!System.getProperty("overhaul.shaderPack","").isBlank()){
            if(overhaul$shaderPack==null)overhaul$shaderPack=new ShaderPackFrameRenderer(minecraft);overhaul$shaderPack.projection(matrix);
        }return matrix;
    }
    @Inject(method="renderLevel",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/GameRenderer;render3dHud(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/OptionsRenderState;Z)V"))
    private void overhaul$renderPackBeforeHand(CallbackInfo callback){
        if(overhaul$shaderPack!=null)overhaul$shaderPack.render(minecraft.gameRenderer.mainRenderTarget(),minecraft.gameRenderer.gameRenderState().levelRenderState);
    }
    @Inject(method="onResourceManagerReload",at=@At("HEAD"))
    private void overhaul$reloadPack(CallbackInfo callback){if(overhaul$shaderPack!=null)overhaul$shaderPack.invalidate();}
    @Inject(method="close",at=@At("HEAD"))
    private void overhaul$closePack(CallbackInfo callback){if(overhaul$shaderPack!=null){overhaul$shaderPack.close();overhaul$shaderPack=null;}}
}
