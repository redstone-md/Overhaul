package dev.overhaul.minecraft.backendmixin;

import dev.overhaul.minecraft.shader.ShaderPackFrameRenderer;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import org.joml.Vector4f;
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
    @WrapOperation(method="renderLevel",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/LevelRenderer;render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lorg/joml/Vector4f;ZZ)V"))
    private void overhaul$worldPackScope(LevelRenderer renderer,GraphicsResourceAllocator allocator,boolean outline,CameraRenderState camera,
                                        GpuBufferSlice fog,Vector4f color,boolean worldFog,boolean postProcessing,Operation<Void> original){
        try{
            if(overhaul$shaderPack!=null)overhaul$shaderPack.beginWorld();
            original.call(renderer,allocator,outline,camera,fog,color,worldFog,postProcessing);
            if(overhaul$shaderPack!=null)overhaul$shaderPack.render(minecraft.gameRenderer.mainRenderTarget(),minecraft.gameRenderer.gameRenderState().levelRenderState);
        }
        catch(RuntimeException|Error failure){
            try{if(overhaul$shaderPack!=null)overhaul$shaderPack.abort();}catch(RuntimeException cleanup){failure.addSuppressed(cleanup);}throw failure;
        }
    }
    @Inject(method={"onResourceManagerReload","setLevel","resetData"},at=@At("HEAD"))
    private void overhaul$reloadPack(CallbackInfo callback){if(overhaul$shaderPack!=null)overhaul$shaderPack.invalidate();}
    @Inject(method="close",at=@At("HEAD"))
    private void overhaul$closePack(CallbackInfo callback){if(overhaul$shaderPack!=null){overhaul$shaderPack.close();overhaul$shaderPack=null;}}
}
