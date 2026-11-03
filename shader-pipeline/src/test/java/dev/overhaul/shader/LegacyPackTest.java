package dev.overhaul.shader;

import dev.overhaul.shader.compat.*;
import dev.overhaul.shader.compile.*;
import dev.overhaul.shader.pack.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class LegacyPackTest {
    @TempDir Path temporary;

    @Test void discoversAndCompilesRepresentativeLegacyPackWithExplicitAbi() throws Exception {
        Path fixture = Path.of(getClass().getResource("/packs/legacy").toURI());
        try (var pack = ShaderPackSource.open(fixture); var compiler = new ShadercCompiler()) {
            var catalog = ShaderPackCatalog.discover(pack,null,Map.of("LEGACY_LIGHTING",true,"DISABLE_TERRAIN",false));
            assertEquals(List.of("gbuffers_terrain"),catalog.programs().stream().map(ShaderPackCatalog.Program::name).toList());
            assertTrue(catalog.diagnostics().isEmpty());
            var state = catalog.programs().get(0).state();
            assertTrue(state.enabled());
            assertFalse(state.blends().get("*").enabled());
            assertEquals("SRC_ALPHA",state.blends().get("colortex2").sourceRgb());
            assertEquals(.1f,state.alphaTest().orElseThrow().reference());
            assertFalse(state.flips().get("colortex2"));
            var program = ShaderPackProgram.compile(pack,"gbuffers_terrain",compiler);
            assertEquals(CoordinateConvention.OPENGL_LEGACY,program.coordinates());
            assertEquals(List.of(0,2),program.framebuffer().colorTargets());
            assertTrue(program.descriptors().attributes().keySet().containsAll(LegacyShaderContract.ATTRIBUTE_TYPES.keySet()));
            assertEquals("mat4",program.descriptors().uniforms().get("overhaulModelViewProjection").type());
            assertEquals("mat3",program.descriptors().uniforms().get("overhaulNormalMatrix").type());
            assertTrue(program.vertex().byteSize()>0 && program.fragment().byteSize()>0);
        }
    }

    @Test void compilesUnmodifiedPinnedIrisExampleProgramsIncludingLegacyFog() throws Exception {
        Path fixture = Path.of(getClass().getResource("/packs/iris-example").toURI());
        try (var pack = ShaderPackSource.open(fixture); var compiler = new ShadercCompiler()) {
            var catalog = ShaderPackCatalog.discover(pack,null,Map.of());
            assertTrue(catalog.diagnostics().isEmpty());
            assertEquals(List.of("gbuffers_basic","gbuffers_textured"),catalog.programs().stream().map(ShaderPackCatalog.Program::name).toList());
            for (var program : catalog.programs()) {
                var compiled = ShaderPackProgram.compile(pack,program.name(),compiler);
                assertTrue(compiled.vertex().byteSize()>0 && compiled.fragment().byteSize()>0);
                assertTrue(compiled.descriptors().uniforms().containsKey("overhaulFogDensity"));
                assertTrue(compiled.descriptors().uniforms().containsKey("overhaulFogColor"));
                assertTrue(compiled.descriptors().varyings().containsKey("overhaulFogFragCoord"));
            }
        }
    }

    @Test void conversionIsOptInAndDefinesDepthAndFramebufferCoordinates() {
        String vertex = "void main(){gl_Position=gl_ModelViewMatrix*gl_Vertex;}";
        String fragment = "void main(){gl_FragColor=vec4(gl_FragCoord.xy,0.0,1.0);}";
        var translator = new PackProgramTranslator();
        assertThrows(UnsupportedShaderException.class,() -> translator.translate(vertex,fragment));
        var translated = translator.translate(vertex,fragment,CoordinateConvention.OPENGL_LEGACY);
        assertTrue(translated.vertex().contains("gl_Position.y = -gl_Position.y"));
        assertTrue(translated.vertex().contains("0.5*(gl_Position.z+gl_Position.w)"));
        assertTrue(translated.fragment().contains("overhaulViewportHeight-gl_FragCoord.y"));
        assertTrue(translated.bindings().uniforms().containsKey("overhaulViewportHeight"));
        try (var compiler = new ShadercCompiler()) {
            assertTrue(compiler.compile("legacy.vert",translated.vertex(),ShaderStage.VERTEX).byteSize()>0);
            assertTrue(compiler.compile("legacy.frag",translated.fragment(),ShaderStage.FRAGMENT).byteSize()>0);
        }
    }

    @Test void rawConditionalsUnknownBuiltinsAndDynamicTextureMatrixFailClearly() {
        var translator = new PackProgramTranslator();
        assertThrows(UnsupportedShaderException.class,() -> translator.translate("#if 0\nuniform bad x;\n#endif\nvoid main(){}","void main(){}"));
        assertThrows(UnsupportedShaderException.class,() -> translator.translate("void main(){gl_Position=gl_TextureMatrix[index]*gl_Vertex;}","void main(){}",CoordinateConvention.OPENGL_LEGACY));
        assertThrows(UnsupportedShaderException.class,() -> translator.translate("void main(){gl_Position=gl_Vertex;}","void main(){gl_FragColor=gl_Color;}",CoordinateConvention.OPENGL_LEGACY));
    }

    @Test void dimensionProgramsReplaceRootAndCatalogReportsIncompleteAndUnsupportedFeatures() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("shaders/world-1"));
        Files.writeString(root.getParent().resolve("root.vsh"),"void main(){}");
        Files.writeString(root.getParent().resolve("root.fsh"),"void main(){}");
        try (var pack = ShaderPackSource.open(temporary)) {
            assertTrue(ShaderPackCatalog.discover(pack,-1,Map.of()).programs().isEmpty());
        }
        Files.writeString(root.resolve("z.vsh"),"void main(){}");
        Files.writeString(root.resolve("z.fsh"),"void main(){}");
        Files.writeString(root.resolve("a.vsh"),"void main(){}");
        Files.writeString(root.resolve("compute.csh"),"void main(){}");
        Files.writeString(root.getParent().resolve("shaders.properties"),"program.world-1/z.enabled=false\nuniform.float.custom=frameTime\n");
        try (var pack = ShaderPackSource.open(temporary)) {
            var catalog = ShaderPackCatalog.discover(pack,-1,Map.of());
            assertEquals(List.of("world-1/z"),catalog.programs().stream().map(ShaderPackCatalog.Program::name).toList());
            assertFalse(catalog.programs().get(0).state().enabled());
            assertEquals(3,catalog.diagnostics().size());
            assertTrue(catalog.diagnostics().stream().anyMatch(message -> message.contains("Incomplete")));
        }
    }

    @Test void booleanPropertiesRejectUndefinedOptionsAndPreserveUnspecifiedPipelineState() throws Exception {
        var empty = ShaderPackProperties.parse("").program("gbuffers",Map.of());
        assertTrue(empty.alphaTest().isEmpty());
        assertTrue(empty.blends().isEmpty());
        var properties = ShaderPackProperties.parse("program.gbuffers.enabled=(A || B) && !C\n");
        assertTrue(properties.program("gbuffers",Map.of("A",true,"B",false,"C",false)).enabled());
        assertThrows(UnsupportedShaderException.class,() -> properties.program("gbuffers",Map.of("A",true,"C",false)));
        assertThrows(UnsupportedShaderException.class,() -> ShaderPackProperties.parse("blend.x=UNKNOWN ZERO").program("x",Map.of()));
        assertThrows(UnsupportedShaderException.class,() -> ShaderPackProperties.parse("program.x.enabled="+"!".repeat(70)+"true").program("x",Map.of()));
    }
}
