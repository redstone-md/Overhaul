package dev.overhaul.minecraft.blaze;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.APIUtil;
import org.lwjgl.system.CallbackI;
import org.lwjgl.system.FunctionProvider;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.libffi.FFICIF;
import org.lwjgl.system.libffi.LibFFI;
import org.slf4j.Logger;

/**
 * Answers the OpenGL entry points that mods call directly, without creating an OpenGL context.
 * State, queries and texture uploads reach the owned Vulkan frontend. Draws are not translated:
 * they do nothing and are logged once, so a mod renderer without a native path is visible in the log.
 */
public final class BlazeGlBoundary implements FunctionProvider {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final FFICIF SIGNATURE = APIUtil.apiCreateCIF(LibFFI.FFI_DEFAULT_ABI, LibFFI.ffi_type_pointer,
            LibFFI.ffi_type_pointer, LibFFI.ffi_type_pointer, LibFFI.ffi_type_pointer,
            LibFFI.ffi_type_pointer, LibFFI.ffi_type_pointer, LibFFI.ffi_type_pointer,
            LibFFI.ffi_type_pointer, LibFFI.ffi_type_pointer, LibFFI.ffi_type_pointer, LibFFI.ffi_type_pointer);
    private static final int GL_TRUE = 1;
    private static final int GL_FRAMEBUFFER_COMPLETE = 0x8CD5;
    private static final int GL_ALREADY_SIGNALED = 0x911A;
    private static final Set<String> UNTRANSLATED_PREFIXES = Set.of("glDraw", "glMultiDraw", "glDispatch", "glBlitFramebuffer", "glBlitNamedFramebuffer");

    interface Answer { long apply(Arguments arguments); }

    /** Reads integer and pointer arguments from a native call frame; every slot is one machine word. */
    record Arguments(long address) {
        long pointer(int index) { return MemoryUtil.memGetAddress(MemoryUtil.memGetAddress(address + (long) index * Long.BYTES)); }
        int integer(int index) { return (int) pointer(index); }
        boolean flag(int index) { return (integer(index) & 0xFF) != 0; }
    }

    private static final class Entry implements CallbackI {
        private final String name;
        private final Answer answer;
        private Entry(String name, Answer answer) { this.name = name; this.answer = answer; }
        @Override public FFICIF getCallInterface() { return SIGNATURE; }
        @Override public void callback(long result, long arguments) {
            long value = 0;
            try {
                value = answer.apply(new Arguments(arguments));
            } catch (Throwable failure) {
                if (REPORTED.add(name)) LOGGER.warn("OpenGL entry {} has no usable answer on Vulkan", name, failure);
            }
            MemoryUtil.memPutAddress(result, value);
        }
    }

    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();
    private final AtomicInteger objectNames = new AtomicInteger(1);
    private final Map<Integer, Long> strings = new ConcurrentHashMap<>();
    private final Map<String, Answer> answers = new HashMap<>();
    private final Map<String, Long> addresses = new ConcurrentHashMap<>();
    private final long inert = new Entry("inert", arguments -> 0).address();

    private BlazeGlBoundary() {
        queries();
        names();
        BlazeGlRoutes.install(answers);
        new BlazeGlBuffers().install(answers);
    }

    /** Reports a mod request the frontend cannot honour, once per kind, without failing the mod. */
    static void reportOnce(String key, String message) {
        if (REPORTED.add(key)) LOGGER.warn(message);
    }

    /** Replaces the driver-backed function table for the process; there is no context to restore. */
    public static GLCapabilities install() {
        GL.destroy();
        GL.create(new BlazeGlBoundary());
        return GL.createCapabilities();
    }

    @Override public long getFunctionAddress(ByteBuffer functionName) {
        // LWJGL hands over a NUL-terminated buffer; the name stops at the terminator.
        String name = MemoryUtil.memASCII(MemoryUtil.memAddress(functionName));
        return addresses.computeIfAbsent(name, this::address);
    }

    private long address(String name) {
        Answer answer = answers.get(name);
        if (answer != null) return new Entry(name, answer).address();
        if (UNTRANSLATED_PREFIXES.stream().anyMatch(name::startsWith)) {
            return new Entry(name, arguments -> {
                reportOnce(name, "OpenGL draw " + name + " was called by a renderer with no native Vulkan path; it draws nothing");
                return 0;
            }).address();
        }
        return inert;
    }

    private void queries() {
        answers.put("glGetString", arguments -> strings.computeIfAbsent(arguments.integer(0),
                name -> MemoryUtil.memAddress(MemoryUtil.memUTF8(string(name)))));
        answers.put("glGetIntegerv", arguments -> {
            MemoryUtil.memPutInt(arguments.pointer(1), integer(arguments.integer(0)));
            return 0;
        });
        Answer object = arguments -> objectNames.getAndIncrement();
        answers.put("glCreateShader", object);
        answers.put("glCreateProgram", object);
        answers.put("glFenceSync", object);
        Answer status = arguments -> {
            int name = arguments.integer(1);
            MemoryUtil.memPutInt(arguments.pointer(2), name >= 0x8B81 && name <= 0x8B83 ? GL_TRUE : 0);
            return 0;
        };
        answers.put("glGetShaderiv", status);
        answers.put("glGetProgramiv", status);
        answers.put("glCheckFramebufferStatus", arguments -> GL_FRAMEBUFFER_COMPLETE);
        answers.put("glCheckNamedFramebufferStatus", arguments -> GL_FRAMEBUFFER_COMPLETE);
        answers.put("glClientWaitSync", arguments -> GL_ALREADY_SIGNALED);
    }

    private void names() {
        Answer generate = arguments -> {
            long target = arguments.pointer(1);
            for (int index = 0; index < arguments.integer(0); index++) MemoryUtil.memPutInt(target + index * (long) Integer.BYTES, objectNames.getAndIncrement());
            return 0;
        };
        for (String name : new String[]{"glGenBuffers", "glGenVertexArrays", "glGenFramebuffers", "glGenRenderbuffers",
                "glGenQueries", "glGenSamplers", "glGenTransformFeedbacks", "glGenProgramPipelines", "glCreateBuffers",
                "glCreateVertexArrays", "glCreateFramebuffers", "glCreateRenderbuffers", "glCreateSamplers"}) {
            answers.put(name, generate);
        }
    }

    private static String string(int name) {
        return switch (name) {
            case 0x1F00 -> "Overhaul";
            case 0x1F01 -> BlazeFrontend.require().context().deviceName();
            case 0x1F02 -> "3.3.0 Overhaul Vulkan";
            case 0x8B8C -> "3.30";
            default -> "";
        };
    }

    private static int integer(int name) {
        switch (name) {
            case 0x821B: case 0x821C: return 3;
            case 0x9126: return 1;
            case 0x821D: case 0x821E: return 0;
            default: break;
        }
        var renderer = BlazeFrontend.current();
        if (renderer == null || !RenderSystem.isOnRenderThreadOrInit()) return 0;
        Integer value = renderer.textures().integer(name);
        if (value == null) value = renderer.drawState().integer(name);
        if (value != null) return value;
        try {
            return BlazeCapabilities.integer(renderer.context(), name, renderer.textures().textureUnits());
        } catch (UnsupportedOperationException unsupported) {
            return 0;
        }
    }
}
