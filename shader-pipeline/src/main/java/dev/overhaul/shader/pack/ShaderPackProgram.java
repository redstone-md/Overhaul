package dev.overhaul.shader.pack;

import dev.overhaul.shader.compat.FramebufferBindings;
import dev.overhaul.shader.compat.PackProgramTranslator;
import dev.overhaul.shader.compat.ProgramBindings;
import dev.overhaul.shader.compat.CoordinateConvention;
import dev.overhaul.shader.compat.FragmentAlphaTest;
import dev.overhaul.shader.compat.UnsupportedShaderException;
import dev.overhaul.shader.compile.ShaderStage;
import dev.overhaul.shader.compile.ShadercCompiler;
import dev.overhaul.shader.compile.SpirvBinary;
import dev.overhaul.shader.compile.SpirvInterface;
import java.io.IOException;

/** Loads and compiles one explicit program; does not claim complete pack semantics. */
public record ShaderPackProgram(SpirvBinary vertex, SpirvBinary fragment,
                                ProgramBindings descriptors, FramebufferBindings framebuffer,
                                CoordinateConvention coordinates, ShaderPackProperties.AlphaTest alphaTest) {
    public ShaderPackProgram(SpirvBinary vertex, SpirvBinary fragment, ProgramBindings descriptors,
                             FramebufferBindings framebuffer, CoordinateConvention coordinates) {
        this(vertex,fragment,descriptors,framebuffer,coordinates,null);
    }
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
        return compile(name,vertex,fragment,compiler,coordinates);
    }

    /** Compiles already-preprocessed sources so pack directives and executable code share one active branch set. */
    public static ShaderPackProgram compile(String name,String vertex,String fragment,ShadercCompiler compiler,
                                             CoordinateConvention coordinates) {
        return compile(name,vertex,fragment,compiler,coordinates,null);
    }

    public static ShaderPackProgram compile(String name,String vertex,String fragment,ShadercCompiler compiler,
                                             CoordinateConvention coordinates,ShaderPackProperties.AlphaTest alphaTest) {
        return compile(name,vertex,fragment,compiler,coordinates,alphaTest,false);
    }

    /** With a dynamic cutoff the fragment also discards below {@link FragmentAlphaTest#CUTOFF}, set by each draw. */
    public static ShaderPackProgram compile(String name,String vertex,String fragment,ShadercCompiler compiler,
                                             CoordinateConvention coordinates,ShaderPackProperties.AlphaTest alphaTest,boolean dynamicCutoff) {
        var framebuffer = FramebufferBindings.parse(fragment);
        String source=PackShadowSettings.stripDirectives(fragment);
        if(dynamicCutoff)source=FragmentAlphaTest.declareCutoff(source);
        var translated = new PackProgramTranslator().translate(PackShadowSettings.stripDirectives(vertex),source, coordinates);
        String lowered=FragmentAlphaTest.lower(translated.fragment(),alphaTest);
        if(dynamicCutoff)lowered=FragmentAlphaTest.lowerCutoff(lowered);
        SpirvBinary compiledVertex=compiler.compile(name+".vsh",translated.vertex(),ShaderStage.VERTEX);
        SpirvBinary compiledFragment=compiler.compile(name+".fsh",lowered,ShaderStage.FRAGMENT);
        return new ShaderPackProgram(compiledVertex,compiledFragment,SpirvInterface.active(translated.bindings(),compiledVertex,compiledFragment),framebuffer,coordinates,alphaTest);
    }

    /** Native pipelines must not silently use a binary compiled for different fixed alpha state. */
    public void requireAlphaTest(ShaderPackProperties.AlphaTest state) {
        if (state != null && state.enabled() && !state.equals(alphaTest)) {
            throw new UnsupportedShaderException("Fragment binary was not compiled with the requested alpha test");
        }
        if (alphaTest != null && alphaTest.enabled() && !alphaTest.equals(state)) {
            throw new UnsupportedShaderException("Fragment binary contains a different alpha test");
        }
    }
}
