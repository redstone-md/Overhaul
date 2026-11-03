package dev.overhaul.minecraft.blaze;

import dev.overhaul.shader.blaze.BlazeProgramCompiler;

/** Implemented by the actual resource-selected ShaderInstance. */
public interface BlazeShaderAccess {
    BlazeShaderSnapshot overhaul$shaderSnapshot(BlazeProgramCompiler compiler);
}
