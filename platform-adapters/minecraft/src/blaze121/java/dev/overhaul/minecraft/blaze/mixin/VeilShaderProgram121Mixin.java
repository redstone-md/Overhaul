package dev.overhaul.minecraft.blaze.mixin;

import dev.overhaul.minecraft.blaze.BlazeVeilAccess;
import dev.overhaul.minecraft.blaze.BlazeVeilPrograms;
import foundry.veil.impl.client.render.shader.program.ShaderTextureCache;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Each program a Veil shader switches to takes its draw values from that shader's own caches. */
@Pseudo
@Mixin(targets = "foundry.veil.impl.client.render.shader.program.ShaderProgramImpl", remap = false)
abstract class VeilShaderProgram121Mixin implements BlazeVeilAccess.Program {
    @Shadow(remap = false) @Final private ShaderTextureCache textures;
    @Shadow(remap = false) @Final private Object2ObjectMap<String, ?> uniforms;

    @Shadow(remap = false)
    public abstract int getProgram();

    @Override
    public Object2ObjectMap<String, ?> overhaul$uniforms() {
        return uniforms;
    }

    @Override
    public ShaderTextureCache overhaul$textures() {
        return textures;
    }

    @Inject(method = "applyProgram", at = @At("TAIL"), require = 0)
    private void overhaul$register(CallbackInfo callback) {
        BlazeVeilPrograms.register(getProgram(), this);
    }
}
