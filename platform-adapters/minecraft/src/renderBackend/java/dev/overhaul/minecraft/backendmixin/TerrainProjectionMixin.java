package dev.overhaul.minecraft.backendmixin;

import dev.overhaul.minecraft.terrain.TerrainCullingAccess;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(GameRenderer.class)
public abstract class TerrainProjectionMixin {
    @Shadow @Final private Minecraft minecraft;
    @ModifyArg(method="renderLevel",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"),index=0)
    private Matrix4f overhaul$projection(Matrix4f projection){
        ((TerrainCullingAccess)minecraft.levelRenderer).overhaul$culling().projection(projection);return projection;
    }
}
