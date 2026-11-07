package dev.overhaul.shader.pack;

import dev.overhaul.shader.compat.FramebufferBindings;
import dev.overhaul.shader.compat.PackProgramTranslator;
import dev.overhaul.shader.compat.ProgramBindings;
import dev.overhaul.shader.compat.CoordinateConvention;
import dev.overhaul.shader.compat.EntityColorEmulation;
import dev.overhaul.shader.compat.FragmentAlphaTest;
import dev.overhaul.shader.compat.PackGeometryInterface;
import dev.overhaul.shader.compat.UnsupportedShaderException;
import dev.overhaul.shader.compile.ShaderStage;
import dev.overhaul.shader.compile.ShadercCompiler;
import dev.overhaul.shader.compile.SpirvBinary;
import dev.overhaul.shader.compile.SpirvInterface;
import java.io.IOException;

/**
 * Loads and compiles one explicit program; does not claim complete pack semantics. {@code geometry} and {@code primitive}
 * are null unless the pack gives the program a geometry stage.
 */
public record ShaderPackProgram(SpirvBinary vertex, SpirvBinary geometry, SpirvBinary fragment,
                                ProgramBindings descriptors, FramebufferBindings framebuffer,
                                CoordinateConvention coordinates, ShaderPackProperties.AlphaTest alphaTest,
                                PackGeometryInterface.Primitive primitive) {
    public ShaderPackProgram(SpirvBinary vertex, SpirvBinary fragment, ProgramBindings descriptors,
                             FramebufferBindings framebuffer, CoordinateConvention coordinates) {
        this(vertex,fragment,descriptors,framebuffer,coordinates,null);
    }
    public ShaderPackProgram(SpirvBinary vertex, SpirvBinary fragment, ProgramBindings descriptors,
                             FramebufferBindings framebuffer, CoordinateConvention coordinates, ShaderPackProperties.AlphaTest alphaTest) {
        this(vertex,null,fragment,descriptors,framebuffer,coordinates,alphaTest,null);
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
        return compile(name,vertex,fragment,compiler,coordinates,alphaTest,dynamicCutoff,java.util.Map.of());
    }

    /** {@code imageFormats} gives the format of each {@code image.*} a storage declaration may name without one. */
    public static ShaderPackProgram compile(String name,String vertex,String fragment,ShadercCompiler compiler,CoordinateConvention coordinates,
                                             ShaderPackProperties.AlphaTest alphaTest,boolean dynamicCutoff,java.util.Map<String,String> imageFormats) {
        return compile(name,vertex,null,fragment,compiler,coordinates,alphaTest,dynamicCutoff,imageFormats);
    }

    /** {@code geometry} is the preprocessed geometry program, or null when the program has none. */
    public static ShaderPackProgram compile(String name,String vertex,String geometry,String fragment,ShadercCompiler compiler,CoordinateConvention coordinates,
                                             ShaderPackProperties.AlphaTest alphaTest,boolean dynamicCutoff,java.util.Map<String,String> imageFormats) {
        var framebuffer = FramebufferBindings.parse(fragment);
        String source=PackShadowSettings.stripDirectives(fragment);
        if(dynamicCutoff)source=FragmentAlphaTest.declareCutoff(source);
        String vertexSource=PackShadowSettings.stripDirectives(vertex);
        String geometrySource=geometry==null?null:PackShadowSettings.stripDirectives(geometry);
        if(EntityColorEmulation.needed(source)){
            source=EntityColorEmulation.fragment(source);
            if(geometrySource==null)vertexSource=EntityColorEmulation.vertex(vertexSource);
            else{vertexSource=EntityColorEmulation.vertexBeforeGeometry(vertexSource);geometrySource=EntityColorEmulation.geometry(geometrySource);}
        }
        var translated = new PackProgramTranslator(imageFormats).translate(vertexSource,geometrySource,source,coordinates);
        String lowered=FragmentAlphaTest.lower(translated.fragment(),alphaTest);
        if(dynamicCutoff)lowered=FragmentAlphaTest.lowerCutoff(lowered);
        SpirvBinary compiledVertex=compiler.compile(name+".vsh",translated.vertex(),ShaderStage.VERTEX);
        SpirvBinary compiledGeometry=translated.geometry()==null?null:compiler.compile(name+".gsh",translated.geometry(),ShaderStage.GEOMETRY);
        SpirvBinary compiledFragment=compiler.compile(name+".fsh",lowered,ShaderStage.FRAGMENT);
        ProgramBindings active=SpirvInterface.active(translated.bindings(),compiledVertex,compiledGeometry,compiledFragment,java.util.Set.of());
        return new ShaderPackProgram(compiledVertex,compiledGeometry,compiledFragment,active,framebuffer,coordinates,alphaTest,translated.primitive());
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
