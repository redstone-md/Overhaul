package dev.overhaul.shader;

import dev.overhaul.shader.compile.*;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ShaderPreprocessorTest {
    @Test void missingIncludesAndFrontendSpecificMacroSemanticsAreExplicitFailures() {
        try (var compiler = new ShadercCompiler()) {
            assertThrows(ShaderCompilationException.class,() -> compiler.preprocess("missing.vsh","#version 120\n#include \"missing.glsl\"\n",ShaderStage.VERTEX,Map.of(),
                    (requested,requester,relative) -> { throw new java.io.IOException("missing fixture include"); }));
            assertThrows(ShaderCompilationException.class,() -> compiler.preprocess("profile.vsh","#version 120\n#ifdef GL_core_profile\n#endif\n",ShaderStage.VERTEX,Map.of(),null));
        }
    }
    @Test void expandsFunctionsAndSelectsOnlyActiveIncludesAndDeclarations() {
        String source = """
                #version 120
                #define USE_TEXTURE 1
                #define SCALE(x) ((x) * 2.0)
                #if defined(USE_TEXTURE) && MC_VERSION >= 11202
                #include "common.glsl"
                #else
                #include "missing.glsl"
                uniform unsupported inactive;
                #endif
                void main(){ gl_Position = vec4(SCALE(1.0)); }
                """;
        try (var compiler = new ShadercCompiler()) {
            String processed = compiler.preprocess("main.vsh",source,ShaderStage.VERTEX,Map.of("MC_VERSION","11202"),
                    (requested,requester,relative) -> {
                        assertEquals("common.glsl",requested);
                        return new ShadercCompiler.ResolvedInclude(requested,"#if __VERSION__ == 120\nuniform mat4 transform;\n#endif\n");
                    });
            assertTrue(processed.contains("uniform mat4 transform"));
            assertFalse(processed.contains("inactive"));
            assertFalse(processed.contains("SCALE"));
        }
    }
}
