package dev.overhaul.minecraft.blaze;

import dev.overhaul.minecraft.blaze.BlazeGlBoundary.Answer;
import dev.overhaul.minecraft.blaze.BlazeGlBoundary.Arguments;
import java.util.HashMap;
import java.util.Map;
import org.lwjgl.system.MemoryUtil;

/**
 * Host memory behind the buffer objects that mods create directly, so mapping and readback keep their contract.
 * Contents reach the GPU only through a native renderer path; nothing here issues a draw.
 */
final class BlazeGlBuffers {
    private static final int GL_TRUE = 1;
    private static final int BUFFER_SIZE = 0x8764;
    private static final int BUFFER_MAPPED = 0x88BC;

    private static final class Store {
        long address;
        long size;
        boolean mapped;
    }

    private final Map<Integer, Store> stores = new HashMap<>();
    private final Map<Integer, Integer> bindings = new HashMap<>();

    /** A name argument that selects the buffer: either the target's current binding or the object itself. */
    private interface Selector { int name(Arguments arguments); }

    void install(Map<String, Answer> table) {
        table.put("glBindBuffer", locked(arguments -> {
            bindings.put(arguments.integer(0), arguments.integer(1));
            return 0;
        }));
        table.put("glDeleteBuffers", locked(arguments -> {
            long names = arguments.pointer(1);
            for (int index = 0; index < arguments.integer(0); index++) delete(MemoryUtil.memGetInt(names + index * (long) Integer.BYTES));
            return 0;
        }));
        table.put("glCopyBufferSubData", locked(arguments -> copy(bound(arguments.integer(0)), bound(arguments.integer(1)),
                arguments.pointer(2), arguments.pointer(3), arguments.pointer(4))));
        table.put("glCopyNamedBufferSubData", locked(arguments -> copy(arguments.integer(0), arguments.integer(1),
                arguments.pointer(2), arguments.pointer(3), arguments.pointer(4))));
        family(table, "", arguments -> bound(arguments.integer(0)));
        family(table, "Named", arguments -> arguments.integer(0));
    }

    private void family(Map<String, Answer> table, String named, Selector selector) {
        String buffer = named + "Buffer";
        table.put("gl" + buffer + "Data", locked(arguments -> define(selector.name(arguments), arguments.pointer(1), arguments.pointer(2))));
        table.put("gl" + buffer + "Storage", locked(arguments -> define(selector.name(arguments), arguments.pointer(1), arguments.pointer(2))));
        table.put("gl" + buffer + "SubData", locked(arguments -> {
            Store store = store(selector.name(arguments));
            long offset = arguments.pointer(1), size = arguments.pointer(2);
            if (inside(store, offset, size) && arguments.pointer(3) != 0) MemoryUtil.memCopy(arguments.pointer(3), store.address + offset, size);
            return 0;
        }));
        table.put("glGet" + buffer + "SubData", locked(arguments -> {
            Store store = store(selector.name(arguments));
            long offset = arguments.pointer(1), size = arguments.pointer(2);
            if (inside(store, offset, size)) MemoryUtil.memCopy(store.address + offset, arguments.pointer(3), size);
            return 0;
        }));
        table.put("glMap" + buffer, locked(arguments -> map(store(selector.name(arguments)), 0)));
        table.put("glMap" + buffer + "Range", locked(arguments -> map(store(selector.name(arguments)), arguments.pointer(1))));
        table.put("glUnmap" + buffer, locked(arguments -> {
            store(selector.name(arguments)).mapped = false;
            return GL_TRUE;
        }));
        table.put("glGet" + buffer + "Parameteriv", locked(arguments -> {
            Store store = store(selector.name(arguments));
            int value = switch (arguments.integer(1)) {
                case BUFFER_SIZE -> (int) Math.min(store.size, Integer.MAX_VALUE);
                case BUFFER_MAPPED -> store.mapped ? GL_TRUE : 0;
                default -> 0;
            };
            MemoryUtil.memPutInt(arguments.pointer(2), value);
            return 0;
        }));
    }

    /**
     * Resolves a pixel pointer: with a pixel buffer bound to {@code target} it is an offset into that buffer's host
     * memory, otherwise it already is an address.
     */
    synchronized long pixels(int target, long pointer) {
        int name = bound(target);
        if (name == 0) return pointer;
        Store store = stores.get(name);
        return store == null || store.address == 0 || pointer < 0 || pointer > store.size ? 0 : store.address + pointer;
    }

    private Answer locked(Answer answer) {
        return arguments -> {
            synchronized (this) {
                return answer.apply(arguments);
            }
        };
    }

    private int bound(int target) { return bindings.getOrDefault(target, 0); }

    private Store store(int name) { return stores.computeIfAbsent(name, ignored -> new Store()); }

    private long define(int name, long size, long source) {
        Store store = store(name);
        long bytes = Math.max(size, 1);
        long address = MemoryUtil.nmemCalloc(1, bytes);
        if (address == 0) throw new OutOfMemoryError("Buffer host storage of " + bytes + " bytes");
        if (store.address != 0) MemoryUtil.nmemFree(store.address);
        store.address = address;
        store.size = size;
        store.mapped = false;
        if (source != 0 && size > 0) MemoryUtil.memCopy(source, address, size);
        return 0;
    }

    private long map(Store store, long offset) {
        if (store.address == 0 || offset < 0 || offset > store.size) return 0;
        store.mapped = true;
        return store.address + offset;
    }

    private long copy(int from, int to, long fromOffset, long toOffset, long size) {
        Store source = store(from), target = store(to);
        if (inside(source, fromOffset, size) && inside(target, toOffset, size)) MemoryUtil.memCopy(source.address + fromOffset, target.address + toOffset, size);
        return 0;
    }

    private static boolean inside(Store store, long offset, long size) {
        return store.address != 0 && offset >= 0 && size >= 0 && offset <= store.size - size;
    }

    private void delete(int name) {
        Store store = stores.remove(name);
        if (store != null && store.address != 0) MemoryUtil.nmemFree(store.address);
    }
}
