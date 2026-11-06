package dev.overhaul.minecraft.blaze;

import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.overhaul.minecraft.blaze.mixin.EffectInstanceProgram121Access;
import dev.overhaul.minecraft.blaze.mixin.ShaderInstanceProgram121Access;
import dev.overhaul.minecraft.blaze.mixin.VeilTextureCache121Access;
import dev.overhaul.minecraft.blaze.mixin.VeilUniform121Access;
import dev.overhaul.shader.blaze.BlazeModernSource;
import dev.overhaul.shader.compat.ProgramBindings;
import foundry.veil.api.client.render.VeilRenderSystem;
import foundry.veil.api.client.render.shader.program.ShaderUniformCache;
import foundry.veil.impl.client.render.shader.program.ShaderTextureCache;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;

/** Runs Veil's programs natively: its finished GLSL becomes SPIR-V, and its uniform and texture caches feed each draw. */
public final class BlazeVeilPrograms {
    private static final int VERTEX = 35633, FRAGMENT = 35632;
    private static final String CAMERA = "VeilCamera_";
    private static final Pattern BLOCK = Pattern.compile("\\buniform\\s+(\\w+)\\s*\\{[^}]*\\}\\s*(\\w+)?");
    private record Source(int type, String name, String code) {}
    private static final Map<Integer, Source> SOURCES = new ConcurrentHashMap<>();

    private BlazeVeilPrograms() {}

    public static void source(int shader, int type, String name, String code) {
        if (BlazePrograms.current() != null) SOURCES.put(shader, new Source(type, name, code));
    }

    public static void forget(int shader) {
        SOURCES.remove(shader);
    }

    public static void link(int program, Iterable<Integer> shaders) {
        var programs = BlazePrograms.current();
        if (programs == null || !programs.known(program)) return;
        Source vertex = null, fragment = null;
        for (int shader : shaders) {
            Source source = SOURCES.get(shader);
            if (source == null) {
                fail(programs, program, "program " + program, "a stage has no captured source");
                return;
            }
            if (source.type() == VERTEX) vertex = source;
            else if (source.type() == FRAGMENT) fragment = source;
            else {
                fail(programs, program, source.name(), "stage type " + source.type() + " has no native path");
                return;
            }
        }
        if (vertex == null || fragment == null) {
            fail(programs, program, "program " + program, "it lacks a vertex or fragment stage");
            return;
        }
        String name = vertex.name() + " + " + fragment.name();
        try {
            programs.linkForeign(program, name, programs.compiler().compileModern(vertex.name(), fragment.name(), vertex.code(), fragment.code()));
            reportBlocks(name, vertex.code());
            reportBlocks(name, fragment.code());
        } catch (RuntimeException failure) {
            fail(programs, program, name, failure.getMessage());
        }
    }

    private static void fail(BlazePrograms programs, int program, String name, String why) {
        programs.nameForeign(program, name);
        BlazeGlBoundary.reportOnce("veil program " + name, "Veil program " + name + " has no native path, so it draws nothing: " + why);
    }

    private static void reportBlocks(String name, String source) {
        var block = BLOCK.matcher(source);
        while (block.find()) {
            String id = block.group(2) == null ? block.group(1) : block.group(2);
            if (!id.equals("VeilCamera")) BlazeGlBoundary.reportOnce("veil block " + id, "Veil program " + name + " reads uniform block " + id + ", which stays zero on this backend");
        }
    }

    public static boolean fill(int program, Map<String, ShaderUniformCache.Uniform> uniforms, Map<String, ShaderUniformCache.Uniform> samplers) {
        var programs = BlazePrograms.current();
        if (programs == null || !programs.linked(program)) return false;
        ProgramBindings bindings = programs.compiled(program).bindings();
        for (var entry : bindings.uniforms().entrySet()) {
            String name = entry.getKey(), reflected = reflectedName(name);
            uniforms.put(reflected, new ShaderUniformCache.Uniform(reflected, programs.uniformLocation(program, name), 0, uniformType(entry.getValue().type()), 1));
        }
        for (var entry : bindings.samplerTypes().entrySet()) {
            String name = entry.getKey();
            samplers.put(name, new ShaderUniformCache.Uniform(name, programs.uniformLocation(program, name), 0, samplerType(entry.getValue()), 1));
        }
        return true;
    }

    /** OpenGL reflects each array element under its own indexed name, and Veil addresses them so. */
    private static String reflectedName(String name) {
        int element = name.lastIndexOf(BlazeModernSource.ELEMENT);
        if (element <= 0) return name;
        String index = name.substring(element + BlazeModernSource.ELEMENT.length());
        return index.chars().allMatch(Character::isDigit) && !index.isEmpty() ? name.substring(0, element) + "[" + index + "]" : name;
    }

    /** Bool reads as int: Veil's setters write integers for it, and std140 stores it as one. */
    private static int uniformType(String type) {
        return switch (type) {
            case "float" -> 5126;
            case "vec2" -> 35664;
            case "vec3" -> 35665;
            case "vec4" -> 35666;
            case "int", "bool" -> 5124;
            case "ivec2" -> 35667;
            case "ivec3" -> 35668;
            case "ivec4" -> 35669;
            case "uint" -> 5125;
            case "uvec2" -> 36294;
            case "uvec3" -> 36295;
            case "uvec4" -> 36296;
            case "mat2" -> 35674;
            case "mat3" -> 35675;
            case "mat4" -> 35676;
            default -> throw new IllegalArgumentException("Unsupported uniform type: " + type);
        };
    }

    /** The GL type OpenGL reflects for each sampler, which Veil checks before it binds a texture unit. */
    private static int samplerType(String type) {
        return switch (type) {
            case "sampler2DShadow" -> 35682;
            case "sampler3D" -> 35679;
            case "samplerCube" -> 35680;
            case "sampler2DArray" -> 36289;
            case "sampler2DArrayShadow" -> 36292;
            case "samplerCubeShadow" -> 36293;
            case "isampler2D" -> 36298;
            case "usampler2D" -> 36306;
            default -> 35678;
        };
    }

    public static void register(int program, BlazeVeilAccess.Program access) {
        var programs = BlazePrograms.current();
        if (programs != null && programs.known(program)) programs.registerSnapshot(program, () -> snapshot(programs, program, access));
    }

    private static BlazeShaderSnapshot snapshot(BlazePrograms programs, int id, BlazeVeilAccess.Program access) {
        var program = programs.compiled(id);
        ProgramBindings bindings = program.bindings();
        ByteBuffer values = BlazeShaderSnapshot.blank(bindings.uniformBytes());
        var uniforms = access.overhaul$uniforms();
        for (var entry : bindings.uniforms().entrySet()) {
            String name = entry.getKey();
            if (name.startsWith(CAMERA)) {
                camera(values, name.substring(CAMERA.length()), entry.getValue().byteOffset());
                continue;
            }
            Object uniform = uniforms.get(reflectedName(name));
            if (uniform != null) copy(values, entry.getValue(), ((VeilUniform121Access) uniform).overhaul$value());
        }
        return new BlazeShaderSnapshot(program, values, textures(bindings, access));
    }

    private static Map<String, Object> textures(ProgramBindings bindings, BlazeVeilAccess.Program access) {
        Map<String, Object> samplers = new HashMap<>();
        var bound = ((VeilTextureCache121Access) access.overhaul$textures()).overhaul$textures();
        for (var entry : bound.object2LongEntrySet()) {
            String name = entry.getKey().toString();
            if (bindings.samplers().containsKey(name)) samplers.put(name, ShaderTextureCache.getTextureId(entry.getLongValue()));
        }
        for (String name : bindings.samplers().keySet()) {
            Object texture = samplers.get(name);
            if (texture == null || (Integer) texture == 0) samplers.put(name, MissingTextureAtlasSprite.getTexture().getId());
        }
        return samplers;
    }

    private static void copy(ByteBuffer target, ProgramBindings.Uniform layout, ByteBuffer value) {
        if (value == null) return;
        String type = layout.type();
        int offset = layout.byteOffset();
        if (type.startsWith("mat")) {
            int size = type.charAt(3) - '0';
            if (value.capacity() < size * size * 4) return;
            for (int column = 0; column < size; column++) for (int row = 0; row < size; row++) {
                target.putInt(offset + column * 16 + row * 4, value.getInt((column * size + row) * 4));
            }
            return;
        }
        int count = Math.min(layout.byteSize(), value.capacity()) / 4;
        for (int index = 0; index < count; index++) target.putInt(offset + index * 4, value.getInt(index * 4));
    }

    private static void camera(ByteBuffer target, String field, int offset) {
        var camera = VeilRenderSystem.renderer().getCameraMatrices();
        switch (field) {
            case "ProjMat" -> camera.getProjectionMatrix().get(offset, target);
            case "IProjMat" -> camera.getInverseProjectionMatrix().get(offset, target);
            case "ViewMat" -> camera.getViewMatrix().get(offset, target);
            case "IViewMat" -> camera.getInverseViewMatrix().get(offset, target);
            case "IViewRotMat" -> camera.getInverseViewRotMatrix().get3x4(offset, target);
            case "CameraPosition" -> camera.getCameraPosition().get(offset, target);
            case "CameraBobOffset" -> camera.getCameraBobOffset().get(offset, target);
            case "NearPlane" -> target.putFloat(offset, camera.getNearPlane());
            case "FarPlane" -> target.putFloat(offset, camera.getFarPlane());
            default -> BlazeGlBoundary.reportOnce("veil camera " + field, "Veil camera field " + field + " stays zero on this backend");
        }
    }

    /** A real mesh, so the triangle passes every check of an ordinary draw; its vertices carry the index the shader counts by. */
    public static void drawScreenTriangle() {
        var builder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION);
        builder.addVertex(0, 0, 0);
        builder.addVertex(1, 0, 0);
        builder.addVertex(2, 0, 0);
        BufferUploader.draw(builder.buildOrThrow());
    }

    /** Selects a program named by a renderer that bypasses ProgramManager, keeping the vanilla shader guards truthful. */
    public static void use(int program) {
        var programs = BlazePrograms.current();
        if (programs == null) return;
        programs.useForeign(program);
        if (ShaderInstanceProgram121Access.overhaul$last() != program) ShaderInstanceProgram121Access.overhaul$last(-1);
        EffectInstanceProgram121Access.overhaul$last(-1);
    }
}
