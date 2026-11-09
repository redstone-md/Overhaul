package dev.overhaul.minecraft.blaze;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.overhaul.minecraft.blaze.BlazeGlBoundary.Answer;
import dev.overhaul.minecraft.blaze.BlazeGlBoundary.Arguments;
import dev.overhaul.shader.blaze.BlazeBlockBuffers;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.lwjgl.system.MemoryUtil;

/**
 * Programs a mod builds with raw OpenGL calls, as Flywheel's instancing backend does. The sources it hands to
 * glShaderSource are kept per shader, and glLinkProgram compiles the attached stages to SPIR-V under the program's own
 * name, so selecting, querying and drawing with that name reach the native program.
 */
final class BlazeGlPrograms {
    private static final int VERTEX = 0x8B31, FRAGMENT = 0x8B30;
    private static final int INVALID_INDEX = 0xFFFFFFFF;
    private static final int MAX_SOURCE = 4_194_304;
    private record Shader(int type, String source) {}
    private static final class Linkage {
        final List<Integer> shaders = new CopyOnWriteArrayList<>();
        final Map<String, Integer> attributes = new ConcurrentHashMap<>();
    }

    private final Map<Integer, Shader> shaders = new ConcurrentHashMap<>();
    private final Map<Integer, Linkage> linkages = new ConcurrentHashMap<>();
    private final Set<Integer> deleted = ConcurrentHashMap.newKeySet();

    private BlazeGlPrograms() {}

    static void install(Map<String, Answer> table) {
        new BlazeGlPrograms().routes(table);
    }

    private void routes(Map<String, Answer> table) {
        Answer create = table.get("glCreateShader");
        table.put("glCreateShader", arguments -> {
            long name = create.apply(arguments);
            shaders.put((int) name, new Shader(arguments.integer(0), ""));
            return name;
        });
        table.put("glShaderSource", arguments -> {
            Shader shader = shaders.get(arguments.integer(0));
            if (shader != null) shaders.put(arguments.integer(0), new Shader(shader.type(), source(arguments)));
            return 0;
        });
        // As in OpenGL, a deleted shader that is still attached lives on until detached: Distant Horizons deletes
        // its stages between glAttachShader and glLinkProgram.
        table.put("glDeleteShader", arguments -> {
            int shader = arguments.integer(0);
            if (attached(shader)) deleted.add(shader);
            else shaders.remove(shader);
            return 0;
        });
        table.put("glAttachShader", raw(arguments -> {
            linkage(arguments.integer(0)).shaders.add(arguments.integer(1));
            return 0;
        }));
        table.put("glDetachShader", raw(arguments -> {
            linkage(arguments.integer(0)).shaders.remove((Integer) arguments.integer(1));
            releaseDeleted();
            return 0;
        }));
        table.put("glBindAttribLocation", raw(arguments -> {
            linkage(arguments.integer(0)).attributes.put(MemoryUtil.memUTF8(arguments.pointer(2)), arguments.integer(1));
            return 0;
        }));
        table.put("glLinkProgram", raw(arguments -> link(arguments.integer(0))));
        table.put("glDeleteProgram", raw(arguments -> delete(arguments.integer(0))));
        table.put("glUseProgram", raw(arguments -> {
            BlazePrograms programs = programs();
            if (programs != null) programs.useForeign(arguments.integer(0));
            return 0;
        }));
        table.put("glGetUniformLocation", raw(arguments -> {
            BlazePrograms programs = programs();
            int program = arguments.integer(0);
            return programs == null || !programs.known(program) ? -1 : programs.uniformLocation(program, MemoryUtil.memUTF8(arguments.pointer(1)));
        }));
        table.put("glGetAttribLocation", raw(arguments -> {
            Linkage linkage = linkages.get(arguments.integer(0));
            Integer location = linkage == null ? null : linkage.attributes.get(MemoryUtil.memUTF8(arguments.pointer(1)));
            return location == null ? -1 : location;
        }));
        // Block indices are the same in every program, so the binding a mod gives one reaches every program that declares it.
        table.put("glGetUniformBlockIndex", raw(arguments -> {
            String block = MemoryUtil.memUTF8(arguments.pointer(1));
            return BlazeBlockBuffers.known(block) ? BlazeBlockBuffers.index(block) : INVALID_INDEX;
        }));
    }

    /** Only names from glCreateProgram take this path; Blaze's own programs keep the answers they had. */
    private static Answer raw(Answer answer) {
        return arguments -> arguments.integer(0) >= BlazeGlBoundary.RAW_PROGRAMS ? answer.apply(arguments) : 0;
    }

    private Linkage linkage(int program) {
        return linkages.computeIfAbsent(program, ignored -> new Linkage());
    }

    /** glShaderSource joins its strings; a missing length array or a negative length means a NUL-terminated string. */
    private static String source(Arguments arguments) {
        int count = arguments.integer(1);
        long strings = arguments.pointer(2), lengths = arguments.pointer(3);
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < count; index++) {
            long string = MemoryUtil.memGetAddress(strings + index * (long) Long.BYTES);
            int length = lengths == 0 ? -1 : MemoryUtil.memGetInt(lengths + index * (long) Integer.BYTES);
            text.append(length < 0 ? MemoryUtil.memUTF8(string) : MemoryUtil.memUTF8(string, length));
            if (text.length() > MAX_SOURCE) throw new IllegalArgumentException("Shader source exceeds budget");
        }
        return text.toString();
    }

    private long link(int program) {
        BlazePrograms programs = programs();
        Linkage linkage = linkages.get(program);
        String name = BlazeGlBoundary.caller() + " program " + Integer.toHexString(program);
        if (programs == null) {
            BlazeGlBoundary.reportOnce("raw program thread", "A mod linked a raw OpenGL program away from the render thread; it draws nothing");
            return 0;
        }
        if (programs.known(program)) programs.release(program);
        programs.createAt(program);
        Shader vertex = null, fragment = null;
        for (int shader : linkage == null ? List.<Integer>of() : linkage.shaders) {
            Shader stage = shaders.get(shader);
            if (stage == null) return fail(programs, program, name, "a stage has no source");
            if (stage.type() == VERTEX) vertex = stage;
            else if (stage.type() == FRAGMENT) fragment = stage;
            else return fail(programs, program, name, "stage type 0x" + Integer.toHexString(stage.type()) + " has no native path");
        }
        if (vertex == null || fragment == null) return fail(programs, program, name, "it lacks a vertex or fragment stage");
        try {
            programs.linkForeign(program, name, programs.compiler().compileModern(name + ".vsh", name + ".fsh", vertex.source(), fragment.source(), true));
            BlazeGlVertexArrays.declare(program, vertex.source(), Map.copyOf(linkage.attributes));
            programs.registerSnapshot(program, () -> BlazeRawUniforms.snapshot(programs, program));
        } catch (RuntimeException failure) {
            return fail(programs, program, name, failure.getMessage());
        }
        return 0;
    }

    private static long fail(BlazePrograms programs, int program, String name, String why) {
        programs.nameForeign(program, name);
        BlazeGlBoundary.reportOnce("raw program " + name, "Raw OpenGL " + name + " has no native path, so it draws nothing: " + why);
        return 0;
    }

    private boolean attached(int shader) {
        for (Linkage linkage : linkages.values()) if (linkage.shaders.contains(shader)) return true;
        return false;
    }

    /** Frees the sources of deleted shaders that no program holds any more. */
    private void releaseDeleted() {
        deleted.removeIf(shader -> {
            if (attached(shader)) return false;
            shaders.remove(shader);
            return true;
        });
    }

    private long delete(int program) {
        linkages.remove(program);
        releaseDeleted();
        BlazeGlVertexArrays.forget(program);
        BlazePrograms programs = programs();
        if (programs != null && programs.known(program)) programs.release(program);
        return 0;
    }

    /** Programs belong to the render thread's device; other threads see none. */
    private static BlazePrograms programs() {
        return RenderSystem.isOnRenderThread() ? BlazePrograms.current() : null;
    }
}
