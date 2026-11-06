package dev.overhaul.shader.pack;

import dev.overhaul.shader.compile.ShadercCompiler;
import java.util.regex.Pattern;

/**
 * A scene program rebuilt so each terrain section's offset arrives as a per-instance input instead of in the model-view
 * matrix, which lets every section of a layer go out in one indirect draw. The pack's own source is untouched: its
 * vertex position simply includes the offset, so the model-view the draw supplies is the camera's alone.
 */
public final class PackSectionOffsets {
    /** Vertex input the variant reads; three floats per instance. */
    public static final String ATTRIBUTE = "overhaulSectionOffset";
    private static final Pattern FTRANSFORM = Pattern.compile("\\bftransform\\s*\\(\\s*\\)");
    private static final Pattern VERTEX = Pattern.compile("\\bgl_Vertex\\b");
    private static final Pattern MAIN = Pattern.compile("\\bvoid\\s+main\\s*\\(");

    private PackSectionOffsets() {}

    /** Null when the program has no position to move, such as a pack that builds positions some other way. */
    public static ShaderPackProgram compile(CompiledShaderPack pack, CompiledShaderPack.Program program, ShadercCompiler compiler) {
        if (!program.scene()) return null;
        CompiledShaderPack.Sources sources = pack.sources(program);
        String vertex = lower(sources.vertex());
        if (vertex == null) return null;
        var alphaTest = program.state().alphaTest();
        return ShaderPackProgram.compile(program.name(), vertex, sources.fragment(), compiler, program.code().coordinates(),
                alphaTest.orElse(null), program.stage() == CompiledShaderPack.Stage.GBUFFER && alphaTest.isEmpty());
    }

    static String lower(String vertex) {
        var main = MAIN.matcher(vertex);
        if (!main.find()) return null;
        String moved = FTRANSFORM.matcher(vertex).replaceAll("(gl_ModelViewProjectionMatrix * gl_Vertex)");
        if (!VERTEX.matcher(moved).find()) return null;
        moved = VERTEX.matcher(moved).replaceAll("(gl_Vertex + vec4(" + ATTRIBUTE + ", 0.0))");
        var at = MAIN.matcher(moved);
        at.find();
        return moved.substring(0, at.start()) + "attribute vec3 " + ATTRIBUTE + ";\n" + moved.substring(at.start());
    }
}
