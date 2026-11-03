package dev.overhaul.shader.pack;

import dev.overhaul.shader.compat.FramebufferBindings;
import dev.overhaul.shader.compat.PackProgramTranslator;
import dev.overhaul.shader.compat.ProgramBindings;
import dev.overhaul.shader.compat.CoordinateConvention;
import dev.overhaul.shader.compile.ShaderStage;
import dev.overhaul.shader.compile.ShadercCompiler;
import dev.overhaul.shader.compile.SpirvBinary;
import java.io.IOException;

/** Loads and compiles one explicit program; does not claim complete pack semantics. */
public record ShaderPackProgram(SpirvBinary vertex, SpirvBinary fragment,
                                ProgramBindings descriptors, FramebufferBindings framebuffer,
                                CoordinateConvention coordinates) {
    public static ShaderPackProgram compile(ShaderPackSource pack, String programName,
                                            ShadercCompiler compiler) throws IOException {
        return compile(pack,programName,compiler,java.util.Map.of(),CoordinateConvention.OPENGL_LEGACY);
    }

    public static ShaderPackProgram compile(ShaderPackSource pack, String programName, ShadercCompiler compiler,
                                            java.util.Map<String,String> definitions, CoordinateConvention coordinates) throws IOException {
        String name = ShaderPackSource.normalize(programName);
        var preprocessor = new PackPreprocessor(pack,compiler,definitions);
        String vertex = preprocessor.process(name + ".vsh",ShaderStage.VERTEX);
        String fragment = preprocessor.process(name + ".fsh",ShaderStage.FRAGMENT);
        var framebuffer = FramebufferBindings.parse(fragment);
        var translated = new PackProgramTranslator().translate(vertex, fragment, coordinates);
        return new ShaderPackProgram(compiler.compile(name+".vsh",translated.vertex(),ShaderStage.VERTEX),
                compiler.compile(name+".fsh",translated.fragment(),ShaderStage.FRAGMENT),
                translated.bindings(),framebuffer,coordinates);
    }
}
