package dev.overhaul.minecraft.blaze;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.overhaul.minecraft.blaze.BlazeGlBoundary.Answer;
import dev.overhaul.minecraft.blaze.BlazeGlBoundary.Arguments;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.lwjgl.system.MemoryUtil;

/**
 * Vertex array objects that mods build with raw OpenGL calls, as Veil's lights, particles and skins do. The pointers
 * they record become a native vertex input, and their draws go to {@link BlazeRawDraws} instead of being dropped.
 */
final class BlazeGlVertexArrays {
    static final int ARRAY_BUFFER = 0x8892, ELEMENT_ARRAY_BUFFER = 0x8893;
    static final int SLOTS = 16;
    private static final Pattern LOCATION = Pattern.compile("\\blayout\\s*\\(\\s*location\\s*=\\s*(\\d+)\\s*\\)\\s*in\\s+(?:(?:highp|mediump|lowp)\\s+)?(\\w+)\\s+(\\w+)\\s*;");
    /** The GL location a program's source gave each input, by program name: GL addresses inputs by it, the native program by name. */
    private static final Map<Integer, Map<Integer, Slot>> DECLARED = new ConcurrentHashMap<>();

    /** One input location: a matrix takes a location per column. */
    record Slot(String name, int column) {}

    /** An enabled pointer as the draw sees it. */
    record Pointer(int location, int buffer, int size, int type, boolean normalized, boolean integer, int stride, long offset, int divisor) {}

    /** How an attribute reads its binding, as glVertexAttribFormat states it. */
    private record Format(int size, int type, boolean normalized, boolean integer, int relative) {}

    /** A buffer bound to a binding point, as glBindVertexBuffer states it. */
    private record Binding(int buffer, long offset, int stride) {}

    /** GL 4.3 splits a pointer into a format and a binding; the older calls set both with the binding at the location. */
    private static final class Array {
        final Format[] formats = new Format[SLOTS];
        final int[] attributeBindings = new int[SLOTS];
        final Binding[] bindings = new Binding[SLOTS];
        final int[] divisors = new int[SLOTS];
        final boolean[] enabled = new boolean[SLOTS];
        int elements;
        Array() { for (int slot = 0; slot < SLOTS; slot++) attributeBindings[slot] = slot; }
    }

    private final BlazeGlBuffers buffers;
    private final Int2ObjectOpenHashMap<Array> arrays = new Int2ObjectOpenHashMap<>();
    private int bound;

    BlazeGlVertexArrays(BlazeGlBuffers buffers) { this.buffers = buffers; }

    /** Records which location each {@code layout(location = N) in} of a vertex source names. */
    static void declare(int program, String vertexSource) {
        declare(program, vertexSource, Map.of());
    }

    /** @param bound the locations glBindAttribLocation gave inputs by name; a layout location in the source wins */
    static void declare(int program, String vertexSource, Map<String, Integer> bound) {
        Map<Integer, Slot> slots = new HashMap<>();
        for (var entry : bound.entrySet()) slots.put(entry.getValue(), new Slot(entry.getKey(), 0));
        var match = LOCATION.matcher(vertexSource);
        while (match.find()) {
            int location = Integer.parseInt(match.group(1)), columns = columns(match.group(2));
            for (int column = 0; column < columns; column++) slots.put(location + column, new Slot(match.group(3), column));
        }
        if (slots.isEmpty()) DECLARED.remove(program);
        else DECLARED.put(program, Map.copyOf(slots));
    }

    static Map<Integer, Slot> declared(int program) { return DECLARED.get(program); }

    static void forget(int program) { DECLARED.remove(program); }

    static int columns(String type) {
        if (!type.startsWith("mat") && !type.startsWith("dmat")) return 1;
        return type.charAt(type.indexOf("mat") + 3) - '0';
    }

    void install(Map<String, Answer> table) {
        Answer bindBuffer = table.get("glBindBuffer");
        table.put("glBindBuffer", arguments -> {
            long result = bindBuffer.apply(arguments);
            if (arguments.integer(0) == ELEMENT_ARRAY_BUFFER) synchronized (this) { array().elements = arguments.integer(1); }
            return result;
        });
        table.put("glBindVertexArray", locked(arguments -> {
            bound = arguments.integer(0);
            buffers.bind(ELEMENT_ARRAY_BUFFER, array().elements);
            return 0;
        }));
        table.put("glDeleteVertexArrays", locked(arguments -> {
            long names = arguments.pointer(1);
            for (int index = 0; index < arguments.integer(0); index++) {
                int name = MemoryUtil.memGetInt(names + index * (long) Integer.BYTES);
                arrays.remove(name);
                if (name == bound) bound = 0;
            }
            return 0;
        }));
        table.put("glEnableVertexAttribArray", locked(arguments -> enable(array(), arguments.integer(0), true)));
        table.put("glDisableVertexAttribArray", locked(arguments -> enable(array(), arguments.integer(0), false)));
        table.put("glVertexAttribPointer", locked(arguments -> point(arguments.integer(0), arguments.integer(1), arguments.integer(2),
                arguments.flag(3), false, arguments.integer(4), arguments.pointer(5))));
        table.put("glVertexAttribIPointer", locked(arguments -> point(arguments.integer(0), arguments.integer(1), arguments.integer(2),
                false, true, arguments.integer(3), arguments.pointer(4))));
        table.put("glVertexAttribDivisor", locked(arguments -> {
            int location = arguments.integer(0);
            if (!slot(location)) return 0;
            array().attributeBindings[location] = location;
            array().divisors[location] = arguments.integer(1);
            return 0;
        }));
        separate(table);
        direct(table);
        draws(table);
    }

    /** ARB_vertex_attrib_binding on the bound array. */
    private void separate(Map<String, Answer> table) {
        table.put("glBindVertexBuffer", locked(arguments -> bindBuffer(array(), arguments.integer(0), arguments.integer(1), arguments.pointer(2), arguments.integer(3))));
        table.put("glVertexBindingDivisor", locked(arguments -> divisor(array(), arguments.integer(0), arguments.integer(1))));
        table.put("glVertexAttribBinding", locked(arguments -> attributeBinding(array(), arguments.integer(0), arguments.integer(1))));
        table.put("glVertexAttribFormat", locked(arguments -> format(array(), arguments.integer(0), arguments.integer(1), arguments.integer(2), arguments.flag(3), false, arguments.integer(4))));
        table.put("glVertexAttribIFormat", locked(arguments -> format(array(), arguments.integer(0), arguments.integer(1), arguments.integer(2), false, true, arguments.integer(3))));
    }

    /**
     * Direct state access names the array instead of using the bound one. Every entry point has an address, so
     * Flywheel takes this path whenever its pointers are non-zero.
     */
    private void direct(Map<String, Answer> table) {
        table.put("glVertexArrayElementBuffer", locked(arguments -> {
            int name = arguments.integer(0), elements = arguments.integer(1);
            named(name).elements = elements;
            if (name == bound) buffers.bind(ELEMENT_ARRAY_BUFFER, elements);
            return 0;
        }));
        table.put("glVertexArrayVertexBuffer", locked(arguments -> bindBuffer(named(arguments.integer(0)), arguments.integer(1), arguments.integer(2), arguments.pointer(3), arguments.integer(4))));
        table.put("glVertexArrayBindingDivisor", locked(arguments -> divisor(named(arguments.integer(0)), arguments.integer(1), arguments.integer(2))));
        table.put("glVertexArrayAttribBinding", locked(arguments -> attributeBinding(named(arguments.integer(0)), arguments.integer(1), arguments.integer(2))));
        table.put("glEnableVertexArrayAttrib", locked(arguments -> enable(named(arguments.integer(0)), arguments.integer(1), true)));
        table.put("glDisableVertexArrayAttrib", locked(arguments -> enable(named(arguments.integer(0)), arguments.integer(1), false)));
        table.put("glVertexArrayAttribFormat", locked(arguments -> format(named(arguments.integer(0)), arguments.integer(1), arguments.integer(2), arguments.integer(3), arguments.flag(4), false, arguments.integer(5))));
        table.put("glVertexArrayAttribIFormat", locked(arguments -> format(named(arguments.integer(0)), arguments.integer(1), arguments.integer(2), arguments.integer(3), false, true, arguments.integer(4))));
    }

    private void draws(Map<String, Answer> table) {
        Answer arrays = BlazeGlBoundary.untranslated("glDrawArrays"), arraysInstanced = BlazeGlBoundary.untranslated("glDrawArraysInstanced");
        Answer elements = BlazeGlBoundary.untranslated("glDrawElements"), elementsInstanced = BlazeGlBoundary.untranslated("glDrawElementsInstanced");
        Answer based = BlazeGlBoundary.untranslated("glDrawElementsBaseVertex"), basedInstanced = BlazeGlBoundary.untranslated("glDrawElementsInstancedBaseVertex");
        table.put("glDrawArrays", arguments -> draw(arguments, arrays, arguments.integer(0), arguments.integer(1), arguments.integer(2), 1, 0, 0, 0));
        table.put("glDrawArraysInstanced", arguments -> draw(arguments, arraysInstanced, arguments.integer(0), arguments.integer(1), arguments.integer(2), arguments.integer(3), 0, 0, 0));
        table.put("glDrawElements", arguments -> draw(arguments, elements, arguments.integer(0), 0, arguments.integer(1), 1, arguments.integer(2), arguments.pointer(3), 0));
        table.put("glDrawElementsInstanced", arguments -> draw(arguments, elementsInstanced, arguments.integer(0), 0, arguments.integer(1), arguments.integer(4), arguments.integer(2), arguments.pointer(3), 0));
        // Flywheel draws each mesh of its shared pool from the pool's vertex buffer at the mesh's base vertex.
        table.put("glDrawElementsBaseVertex", arguments -> draw(arguments, based, arguments.integer(0), 0, arguments.integer(1), 1, arguments.integer(2), arguments.pointer(3), arguments.integer(4)));
        table.put("glDrawElementsInstancedBaseVertex", arguments -> draw(arguments, basedInstanced, arguments.integer(0), 0, arguments.integer(1), arguments.integer(4), arguments.integer(2), arguments.pointer(3), arguments.integer(5)));
    }

    /** @param indexType zero for a non-indexed draw */
    private long draw(Arguments arguments, Answer dropped, int mode, int first, int count, int instances, int indexType, long indexOffset, int baseVertex) {
        if (count <= 0 || instances <= 0) return 0;
        BlazeRenderer renderer = RenderSystem.isOnRenderThread() ? BlazeFrontend.current() : null;
        BlazeRawDraws.Request request;
        synchronized (this) {
            if (renderer == null || bound == 0) return dropped.apply(arguments);
            Array array = array();
            request = new BlazeRawDraws.Request(pointers(array), mode, first, count, instances, indexType, indexOffset, indexType == 0 ? 0 : array.elements, baseVertex);
        }
        String failure = renderer.rawDraws().draw(request);
        if (failure != null) BlazeGlBoundary.reportOnce("raw draw " + failure, "A mod's raw OpenGL draw was dropped: " + failure);
        return 0;
    }

    /** Each enabled attribute with a format and a filled binding, joined into the pointer the draw reads. */
    private static List<Pointer> pointers(Array array) {
        List<Pointer> result = new ArrayList<>(SLOTS);
        for (int location = 0; location < SLOTS; location++) {
            Format format = array.formats[location];
            int point = array.attributeBindings[location];
            Binding binding = array.bindings[point];
            if (!array.enabled[location] || format == null || binding == null) continue;
            result.add(new Pointer(location, binding.buffer(), format.size(), format.type(), format.normalized(), format.integer(),
                    binding.stride(), binding.offset() + format.relative(), array.divisors[point]));
        }
        return result;
    }

    private static boolean slot(int index) { return index >= 0 && index < SLOTS; }

    private static long enable(Array array, int location, boolean on) {
        if (slot(location)) array.enabled[location] = on;
        return 0;
    }

    /** The older call is a format at offset zero, the binding at its own location, and the bound buffer there. */
    private long point(int location, int size, int type, boolean normalized, boolean integer, int stride, long offset) {
        if (!slot(location)) return 0;
        Array array = array();
        array.formats[location] = new Format(size, type, normalized, integer, 0);
        array.attributeBindings[location] = location;
        array.bindings[location] = new Binding(buffers.bound(ARRAY_BUFFER), offset, stride);
        return 0;
    }

    private static long bindBuffer(Array array, int point, int buffer, long offset, int stride) {
        if (slot(point)) array.bindings[point] = buffer == 0 ? null : new Binding(buffer, offset, stride);
        return 0;
    }

    private static long divisor(Array array, int point, int divisor) {
        if (slot(point)) array.divisors[point] = divisor;
        return 0;
    }

    private static long attributeBinding(Array array, int location, int point) {
        if (slot(location) && slot(point)) array.attributeBindings[location] = point;
        return 0;
    }

    private static long format(Array array, int location, int size, int type, boolean normalized, boolean integer, int relative) {
        if (slot(location)) array.formats[location] = new Format(size, type, normalized, integer, relative);
        return 0;
    }

    private Array array() { return named(bound); }

    private Array named(int name) { return arrays.computeIfAbsent(name, (int ignored) -> new Array()); }

    private Answer locked(Answer answer) {
        return arguments -> {
            synchronized (this) {
                return answer.apply(arguments);
            }
        };
    }
}
