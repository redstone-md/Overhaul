package dev.overhaul.minecraft.blaze.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.shaders.Shader;
import dev.overhaul.minecraft.blaze.BlazeAttachedStages;
import java.util.List;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Lodestone compiles geometry programs and attaches them with raw OpenGL calls; their source joins the native link. */
@Pseudo
@Mixin(targets = "team.lodestar.lodestone.systems.rendering.shader.LodestoneProgram", remap = false)
abstract class LodestoneProgram121Mixin {
    @Shadow private int id;
    @Shadow @Final private String name;

    @WrapOperation(method = "compileShaderInternal", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/GlStateManager;glCreateShader(I)I"), require = 0)
    private static int overhaul$created(int type, Operation<Integer> create) {
        int shader = create.call(type);
        BlazeAttachedStages.created(shader, type);
        return shader;
    }

    @WrapOperation(method = "compileShaderInternal", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/GlStateManager;glShaderSource(ILjava/util/List;)V"), require = 0)
    private static void overhaul$source(int shader, List<String> parts, Operation<Void> source) {
        BlazeAttachedStages.source(shader, String.join("", parts));
        source.call(shader, parts);
    }

    @Inject(method = "attachToShader", at = @At("HEAD"), require = 0)
    private void overhaul$attach(Shader shader, CallbackInfo callback) {
        BlazeAttachedStages.attach(shader.getId(), id, name + ".gsh");
    }

    @Inject(method = "close", at = @At("HEAD"), require = 0)
    private void overhaul$forget(CallbackInfo callback) {
        BlazeAttachedStages.forget(id);
    }
}
