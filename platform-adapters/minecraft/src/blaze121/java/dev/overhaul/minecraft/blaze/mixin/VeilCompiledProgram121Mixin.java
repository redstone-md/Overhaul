package dev.overhaul.minecraft.blaze.mixin;

import dev.overhaul.minecraft.blaze.BlazePrograms;
import dev.overhaul.minecraft.blaze.BlazeVeilAccess;
import dev.overhaul.minecraft.blaze.BlazeVeilPrograms;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.opengl.GL20C;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A Veil program's name is a native program identity, and linking it compiles its stages to SPIR-V. */
@Pseudo
@Mixin(targets = "foundry.veil.impl.client.render.shader.program.ShaderProgramImpl$CompiledProgram", remap = false)
abstract class VeilCompiledProgram121Mixin {
    @Shadow(remap = false) @Final private int program;
    @Shadow(remap = false) @Final private Int2ObjectMap<?> shaders;

    @Redirect(method = "create", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL20C;glCreateProgram()I", remap = false), require = 0)
    private static int overhaul$identity() {
        var programs = BlazePrograms.current();
        return programs == null ? GL20C.glCreateProgram() : programs.create();
    }

    @Inject(method = "link", at = @At("HEAD"), require = 0)
    private void overhaul$link(CallbackInfo callback) {
        List<Integer> stages = new ArrayList<>(shaders.size());
        for (Object shader : shaders.values()) stages.add(((BlazeVeilAccess.Stage) shader).overhaul$id());
        BlazeVeilPrograms.link(program, stages);
    }

    @Inject(method = "free", at = @At("HEAD"), require = 0)
    private void overhaul$free(CallbackInfo callback) {
        var programs = BlazePrograms.current();
        if (programs != null && programs.known(program)) programs.release(program);
    }
}
