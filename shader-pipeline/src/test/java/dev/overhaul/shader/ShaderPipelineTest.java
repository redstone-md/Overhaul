package dev.overhaul.shader;

import dev.overhaul.shader.compat.*;
import dev.overhaul.shader.compile.*;
import dev.overhaul.shader.pack.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class ShaderPipelineTest {
    @TempDir Path temporary;
    private static final String VERTEX = """
            #version 120
            attribute vec3 position;
            attribute vec2 uv;
            uniform mat4 modelViewProjection;
            varying vec2 texcoord;
            void main() { texcoord = uv; gl_Position = modelViewProjection * vec4(position,1.0); }
            """;
    private static final String FRAGMENT = """
            #version 120
            /* DRAWBUFFERS:02 */
            uniform sampler2D texture;
            uniform float exposure;
            varying vec2 texcoord;
            void main() { gl_FragData[0] = texture2D(texture,texcoord)*exposure; gl_FragData[1] = vec4(0,0,1,1); }
            """;

    @Test void translatesBindingsAndCompilesBothStagesToOwnedSpirv() {
        var translated = new PackProgramTranslator().translate(VERTEX,FRAGMENT);
        assertEquals(0,translated.bindings().attributes().get("position"));
        assertEquals(1,translated.bindings().attributes().get("uv"));
        assertEquals(1,translated.bindings().samplers().get("texture"));
        assertEquals(0,translated.bindings().uniforms().get("exposure").byteOffset());
        assertEquals(16,translated.bindings().uniforms().get("modelViewProjection").byteOffset());
        assertEquals(80,translated.bindings().uniformBytes());
        SpirvBinary vertex;
        try (var compiler = new ShadercCompiler()) {
            vertex = compiler.compile("gbuffers.vsh",translated.vertex(),ShaderStage.VERTEX);
            var fragment = compiler.compile("gbuffers.fsh",translated.fragment(),ShaderStage.FRAGMENT);
            assertTrue(fragment.byteSize()>20);
            assertThrows(ShaderCompilationException.class,() -> compiler.compile("bad.frag","#version 450\ninvalid",ShaderStage.FRAGMENT));
        }
        assertEquals(0x07230203,vertex.directBuffer().order(ByteOrder.nativeOrder()).getInt());
        byte[] copy = vertex.bytes(); copy[0] = 0;
        assertNotEquals(0,vertex.bytes()[0]);
        var closed = new ShadercCompiler(); closed.close(); closed.close();
        assertThrows(IllegalStateException.class,() -> closed.compile("x",VERTEX,ShaderStage.VERTEX));
    }

    @Test void rejectsUnsupportedSemanticsRatherThanInventingState() {
        var translator = new PackProgramTranslator();
        assertThrows(UnsupportedShaderException.class,() -> translator.translate("void main(){gl_Position=ftransform();}","void main(){}"));
        assertThrows(UnsupportedShaderException.class,() -> translator.translate("void main(){gl_Position=gl_Vertex;}","void main(){}"));
        assertThrows(UnsupportedShaderException.class,() -> translator.translate(VERTEX,"varying vec3 texcoord; void main(){}"));
        assertThrows(UnsupportedShaderException.class,() -> translator.translate("uniform float values[4]; void main(){}","void main(){}"));
        assertThrows(UnsupportedShaderException.class,() -> FramebufferBindings.parse("/* DRAWBUFFERS:00 */"));
        assertThrows(UnsupportedShaderException.class,() -> FramebufferBindings.parse("/* DRAWBUFFERS:bad */"));
        assertEquals(java.util.List.of(0,2),FramebufferBindings.parse(FRAGMENT).colorTargets());
        assertEquals(java.util.List.of(0,12),FramebufferBindings.parse("/* RENDERTARGETS: 0,12 */").colorTargets());
    }

    @Test void packsUniformMatricesWithStd140ColumnStrideAndSnapshotIsolation() {
        var program = new PackProgramTranslator().translate("uniform mat3 normalMatrix; uniform float exposure; void main(){}","void main(){}");
        var uniforms = new UniformBlock(program.bindings());
        uniforms.setFloat("exposure",2);
        uniforms.setMatrix("normalMatrix",3,new float[]{1,2,3,4,5,6,7,8,9});
        var bytes = uniforms.snapshot();
        int offset = program.bindings().uniforms().get("normalMatrix").byteOffset();
        assertEquals(1,bytes.getFloat(offset));
        assertEquals(0,bytes.getFloat(offset+12));
        assertEquals(4,bytes.getFloat(offset+16));
        assertEquals(9,bytes.getFloat(offset+40));
        uniforms.setFloat("exposure",3);
        assertEquals(2,bytes.getFloat(program.bindings().uniforms().get("exposure").byteOffset()));
        assertThrows(java.nio.ReadOnlyBufferException.class,() -> bytes.putFloat(0,9));
        assertThrows(IllegalArgumentException.class,() -> uniforms.setInt("exposure",1));
    }

    @Test void expandsNestedIncludesAndCompilesDirectoryPack() throws Exception {
        Path shaders = Files.createDirectories(temporary.resolve("shaders/lib"));
        Files.writeString(shaders.resolve("uniforms.glsl"),"uniform mat4 modelViewProjection;\n");
        Files.writeString(shaders.getParent().resolve("gbuffers.vsh"),VERTEX.replace("uniform mat4 modelViewProjection;","#include \"/lib/uniforms.glsl\""));
        Files.writeString(shaders.getParent().resolve("gbuffers.fsh"),FRAGMENT);
        try (var pack = ShaderPackSource.open(temporary); var compiler = new ShadercCompiler()) {
            var expanded = new IncludePreprocessor(pack).expand("gbuffers.vsh");
            assertEquals(2,expanded.sourceIds().size());
            assertFalse(expanded.text().contains("#include"));
            var program = ShaderPackProgram.compile(pack,"gbuffers",compiler);
            assertTrue(program.vertex().byteSize()>0);
            assertEquals(java.util.List.of(0,2),program.framebuffer().colorTargets());
        }
    }

    @Test void zipReadsAreBoundedAndIncludeCyclesAndTraversalFail() throws Exception {
        Path archive = temporary.resolve("pack.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            add(zip,"shaders/main.vsh","#version 120\n#include \"/lib/a.glsl\"\n");
            add(zip,"shaders/lib/a.glsl","#include \"/main.vsh\"\n");
            add(zip,"shaders/huge.glsl"," ".repeat(1_048_577));
        }
        try (var pack = ShaderPackSource.open(archive)) {
            assertThrows(IOException.class,() -> new IncludePreprocessor(pack).expand("main.vsh"));
            assertThrows(IOException.class,() -> pack.read("../../outside"));
            assertThrows(IOException.class,() -> pack.read("huge.glsl"));
        }
        assertThrows(IOException.class,() -> ShaderPackSource.normalize("C:/private"));
        assertThrows(IOException.class,() -> ShaderPackSource.normalize("..\\private"));
    }

    private static void add(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name)); zip.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)); zip.closeEntry();
    }
}
