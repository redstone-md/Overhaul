package dev.overhaul.shader.pack;

import dev.overhaul.shader.compat.FramebufferBindings;
import dev.overhaul.shader.compat.PackProgramTranslator;
import dev.overhaul.shader.compat.ProgramBindings;
import dev.overhaul.shader.compile.ShaderStage;
import dev.overhaul.shader.compile.ShadercCompiler;
import dev.overhaul.shader.compile.SpirvBinary;
import java.io.IOException;

/** Loads and compiles one explicit program; does not claim complete pack semantics. */
public record ShaderPackProgram(SpirvBinary vertex, SpirvBinary fragment,
                                ProgramBindings descriptors, FramebufferBindings framebuffer) {
    public static ShaderPackProgram compile(ShaderPackSource pack, String programName,
                                            ShadercCompiler compiler) throws IOException {
        String name = ShaderPackSource.normalize(programName);
        var preprocessor = new IncludePreprocessor(pack);
        String vertex = preprocessor.expand(name + ".vsh").text();
        String fragment = preprocessor.expand(name + ".fsh").text();
        var framebuffer = FramebufferBindings.parse(fragment);
        var translated = new PackProgramTranslator().translate(vertex, fragment);
        return new ShaderPackProgram(compiler.compile(name+".vsh",translated.vertex(),ShaderStage.VERTEX),
                compiler.compile(name+".fsh",translated.fragment(),ShaderStage.FRAGMENT),
                translated.bindings(),framebuffer);
    }
}
