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
    /** Floating point arguments travel in other registers, so these entries need their true signature. */
    private static final Map<String, FFICIF> REAL_SIGNATURES = Map.of(
            "glClearColor", APIUtil.apiCreateCIF(LibFFI.FFI_DEFAULT_ABI, LibFFI.ffi_type_pointer, LibFFI.ffi_type_float, LibFFI.ffi_type_float, LibFFI.ffi_type_float, LibFFI.ffi_type_float),
            "glClearDepth", APIUtil.apiCreateCIF(LibFFI.FFI_DEFAULT_ABI, LibFFI.ffi_type_pointer, LibFFI.ffi_type_double),
            "glUniform1f", floats(1), "glUniform2f", floats(2), "glUniform3f", floats(3), "glUniform4f", floats(4),
            "glTexParameterf", parameterFloat(), "glSamplerParameterf", parameterFloat(),
            "glPolygonOffset", APIUtil.apiCreateCIF(LibFFI.FFI_DEFAULT_ABI, LibFFI.ffi_type_pointer, LibFFI.ffi_type_float, LibFFI.ffi_type_float));
    /** A location followed by {@code count} floats. */
    private static FFICIF floats(int count) {
        var types = new org.lwjgl.system.libffi.FFIType[count + 1];
        types[0] = LibFFI.ffi_type_sint32;
        java.util.Arrays.fill(types, 1, types.length, LibFFI.ffi_type_float);
        return APIUtil.apiCreateCIF(LibFFI.FFI_DEFAULT_ABI, LibFFI.ffi_type_pointer, types);
    }
    /** An object or target, a parameter name, then one float value. */
    private static FFICIF parameterFloat() {
        return APIUtil.apiCreateCIF(LibFFI.FFI_DEFAULT_ABI, LibFFI.ffi_type_pointer, LibFFI.ffi_type_sint32, LibFFI.ffi_type_sint32, LibFFI.ffi_type_float);
    }
    private static final int GL_TRUE = 1, GL_DEPTH_CLEAR_VALUE = 0x0B73, GL_COLOR_CLEAR_VALUE = 0x0C22;
    private static final int GL_FRAMEBUFFER_COMPLETE = 0x8CD5;
    private static final int GL_ALREADY_SIGNALED = 0x911A;
    private static final int CALLER_SAMPLES = 4096;
    private static final Set<String> UNTRANSLATED_PREFIXES = Set.of("glDraw", "glMultiDraw", "glDispatch", "glBlitFramebuffer", "glBlitNamedFramebuffer");

    interface Answer { long apply(Arguments arguments); }

    /** Reads integer and pointer arguments from a native call frame; every slot is one machine word. */
    record Arguments(long address) {
        long pointer(int index) { return MemoryUtil.memGetAddress(MemoryUtil.memGetAddress(address + (long) index * Long.BYTES)); }
        int integer(int index) { return (int) pointer(index); }
        boolean flag(int index) { return (integer(index) & 0xFF) != 0; }
        float real(int index) { return MemoryUtil.memGetFloat(MemoryUtil.memGetAddress(address + (long) index * Long.BYTES)); }
        double wide(int index) { return MemoryUtil.memGetDouble(MemoryUtil.memGetAddress(address + (long) index * Long.BYTES)); }
    }

    private static final class Entry implements CallbackI {
        private final String name;
        private final Answer answer;
        private Entry(String name, Answer answer) { this.name = name; this.answer = answer; }
        @Override public FFICIF getCallInterface() { return REAL_SIGNATURES.getOrDefault(name, SIGNATURE); }
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
    private static volatile BlazeGlBuffers active;
    private final AtomicInteger objectNames = new AtomicInteger(1);
    /** The first name glCreateProgram hands out, far above the ids {@link BlazePrograms} creates, so the two never meet. */
    static final int RAW_PROGRAMS = 0x40000000;
    private final AtomicInteger rawProgramNames = new AtomicInteger(RAW_PROGRAMS);
    private final AtomicInteger framebufferNames = new AtomicInteger(BlazeRenderer.FOREIGN_FRAMEBUFFERS);
    private final Map<Integer, Long> strings = new ConcurrentHashMap<>();
    private final Map<String, Answer> answers = new HashMap<>();
    private final Map<String, Long> addresses = new ConcurrentHashMap<>();
    private final long inert = new Entry("inert", arguments -> 0).address();

    private BlazeGlBoundary() {
        queries();
        names();
        BlazeGlBuffers buffers = new BlazeGlBuffers();
        BlazeGlRoutes.install(answers, buffers);
        buffers.install(answers);
        BlazeGlBlocks.install(answers, buffers);
        BlazeGlUniforms.install(answers);
        if (BlazeOptimization.RAW_DRAWS.on()) {
            new BlazeGlVertexArrays(buffers).install(answers);
            if (BlazeOptimization.RAW_PROGRAMS.on()) BlazeGlPrograms.install(answers);
        }
        active = buffers;
    }

    /** The buffer objects mods created through the installed table; native renderer paths read them by name. */
    static BlazeGlBuffers buffers() { return active; }

    /** Reports a mod request the frontend cannot honour, once per kind, without failing the mod. */
    public static void reportOnce(String key, String message) {
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
        if (UNTRANSLATED_PREFIXES.stream().anyMatch(name::startsWith)) return new Entry(name, untranslated(name)).address();
        return TRACE_INERT ? new Entry(name, unanswered(name)).address() : inert;
    }

    /** Off by default: one closure per GL entry point costs memory, and only an audit for missing answers needs it. */
    private static final boolean TRACE_INERT = Boolean.getBoolean("overhaul.blaze.traceInert");

    /** Names an entry point that reached the table with no answer, so an audit can tell silence from coverage. */
    private static Answer unanswered(String name) {
        return arguments -> {
            if (REPORTED.add("inert " + name)) LOGGER.warn("OpenGL entry {} from {} has no answer and does nothing", name, caller());
            return 0;
        };
    }

    /** Draws nothing and names the calling renderer once, so a mod renderer without a native path is visible in the log. */
    static Answer untranslated(String name) {
        AtomicInteger calls = new AtomicInteger();
        return arguments -> {
            // Walking the stack on every draw would be costly, and the renderers involved show up within the first calls.
            if (calls.getAndIncrement() < CALLER_SAMPLES) {
                String caller = caller();
                reportOnce(name + caller, "OpenGL draw " + name + " from " + caller + " has no native Vulkan path; it draws nothing");
            }
            return 0;
        };
    }

    static String caller() {
        return StackWalker.getInstance().walk(frames -> frames.map(StackWalker.StackFrame::getClassName)
                .filter(type -> !type.startsWith("org.lwjgl") && !type.startsWith("dev.overhaul") && !type.startsWith("java.") && !type.startsWith("jdk."))
                .findFirst().orElse("an unknown class"));
    }

    /** Veil saves the clear values this way and restores them afterwards, so no answer would restore stack garbage. */
    private double[] reals(int name) {
        var renderer = RenderSystem.isOnRenderThreadOrInit() ? BlazeFrontend.current() : null;
        if (renderer != null && name == GL_DEPTH_CLEAR_VALUE) return new double[]{renderer.clearValues().depth()};
        if (renderer != null && name == GL_COLOR_CLEAR_VALUE) {
            var clear = renderer.clearValues();
            return new double[]{clear.r(), clear.g(), clear.b(), clear.a()};
        }
        int[] several = integers(name);
        if (several == null) return new double[]{integer(name)};
        double[] values = new double[several.length];
        for (int index = 0; index < several.length; index++) values[index] = several[index];
        return values;
    }

    private void queries() {
        answers.put("glGetString", arguments -> strings.computeIfAbsent(arguments.integer(0),
                name -> MemoryUtil.memAddress(MemoryUtil.memUTF8(string(name)))));
        answers.put("glGetIntegerv", arguments -> {
            long target = arguments.pointer(1);
            int[] several = integers(arguments.integer(0));
            if (several == null) {
                MemoryUtil.memPutInt(target, integer(arguments.integer(0)));
            } else {
                // Callers size the buffer for the whole answer; writing less leaves their stack garbage in it.
                for (int index = 0; index < several.length; index++) MemoryUtil.memPutInt(target + index * (long) Integer.BYTES, several[index]);
            }
            return 0;
        });
        answers.put("glGetFloatv", arguments -> {
            long target = arguments.pointer(1);
            double[] values = reals(arguments.integer(0));
            for (int index = 0; index < values.length; index++) MemoryUtil.memPutFloat(target + index * (long) Float.BYTES, (float) values[index]);
            return 0;
        });
        answers.put("glGetDoublev", arguments -> {
            long target = arguments.pointer(1);
            double[] values = reals(arguments.integer(0));
            for (int index = 0; index < values.length; index++) MemoryUtil.memPutDouble(target + index * (long) Double.BYTES, values[index]);
            return 0;
        });
        Answer object = arguments -> objectNames.getAndIncrement();
        answers.put("glCreateShader", object);
        answers.put("glCreateProgram", arguments -> rawProgramNames.getAndIncrement());
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
        // Mods restore saved bindings only for names GL still recognises (Distant Horizons), so every issued name stays valid.
        Answer known = arguments -> arguments.integer(0) != 0 ? GL_TRUE : 0;
        for (String name : new String[]{"glIsFramebuffer", "glIsTexture", "glIsBuffer", "glIsProgram", "glIsShader",
                "glIsVertexArray", "glIsRenderbuffer", "glIsSampler", "glIsQuery"}) {
            answers.put(name, known);
        }
        answers.put("glGetFramebufferAttachmentParameteriv", arguments -> {
            MemoryUtil.memPutInt(arguments.pointer(3), 0);
            return 0;
        });
        answers.put("glGetError", arguments -> 0);
        // Compiling waits for glLinkProgram and Vulkan submits in order, so these have nothing left to do.
        Answer done = arguments -> 0;
        for (String name : new String[]{"glCompileShader", "glValidateProgram", "glFinish", "glFlush", "glHint"}) answers.put(name, done);
        // Every program and shader reports success, so its info log is empty.
        Answer emptyLog = arguments -> {
            if (arguments.pointer(2) != 0) MemoryUtil.memPutInt(arguments.pointer(2), 0);
            if (arguments.integer(1) > 0 && arguments.pointer(3) != 0) MemoryUtil.memPutByte(arguments.pointer(3), (byte) 0);
            return 0;
        };
        answers.put("glGetShaderInfoLog", emptyLog);
        answers.put("glGetProgramInfoLog", emptyLog);
        answers.put("glGetIntegeri_v", arguments -> {
            MemoryUtil.memPutInt(arguments.pointer(2), indexed(arguments.integer(0), arguments.integer(1)));
            return 0;
        });
    }

    /** Compute limits at the minimum every Vulkan device guarantees; other indexed queries answer 0. */
    private static int indexed(int name, int index) {
        if (index < 0 || index > 2) return 0;
        return switch (name) {
            case 0x91BE -> 65_535;
            case 0x91BF -> index == 2 ? 64 : 128;
            default -> 0;
        };
    }

    private void names() {
        Answer generate = generator(objectNames);
        for (String name : new String[]{"glGenBuffers", "glGenVertexArrays", "glGenRenderbuffers",
                "glGenQueries", "glGenSamplers", "glGenTransformFeedbacks", "glGenProgramPipelines", "glCreateBuffers",
                "glCreateVertexArrays", "glCreateRenderbuffers", "glCreateSamplers"}) {
            answers.put(name, generate);
        }
        // Framebuffer names live in their own range, so they can never alias a real target's handle.
        Answer framebuffers = generator(framebufferNames);
        answers.put("glGenFramebuffers", framebuffers);
        answers.put("glCreateFramebuffers", framebuffers);
    }

    private static Answer generator(AtomicInteger names) {
        return arguments -> {
            long target = arguments.pointer(1);
            for (int index = 0; index < arguments.integer(0); index++) MemoryUtil.memPutInt(target + index * (long) Integer.BYTES, names.getAndIncrement());
            return 0;
        };
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

    private static int[] integers(int name) {
        var renderer = BlazeFrontend.current();
        return renderer == null || !RenderSystem.isOnRenderThreadOrInit() ? null : renderer.drawState().integers(name);
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
