package dev.overhaul.minecraft.blaze;

import dev.overhaul.shader.blaze.BlazeProgramCompiler;

/** Implemented by the actual resource-selected ShaderInstance. */
public interface BlazeShaderAccess {
    BlazeShaderSnapshot overhaul$shaderSnapshot(BlazeProgramCompiler compiler);
    /** The same shader with its section offset read per instance; null when it has no such offset. */
    BlazeProgramCompiler.Program overhaul$sectionProgram();
    BlazeShaderSnapshot overhaul$sectionSnapshot();
}
