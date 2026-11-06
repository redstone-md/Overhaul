package dev.overhaul.minecraft.blaze;

import dev.overhaul.minecraft.blaze.BlazeGlBoundary.Answer;
import dev.overhaul.minecraft.blaze.BlazeGlBoundary.Arguments;
import dev.overhaul.vulkan.GpuBuffer;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
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
        GpuBuffer device;
        /** Where this buffer's bytes start in {@link #device}: zero unless it shares a page with others. */
        long base;
        BlazeBufferPool.Slice slice;
        Placed placed;
        BlazeRenderer owner;
    }

    /** A buffer held on the device: the buffer to bind and the byte its contents start at. */
    record Placed(GpuBuffer buffer, long base) {}

    private final Int2ObjectOpenHashMap<Store> stores = new Int2ObjectOpenHashMap<>();
    private final Int2IntOpenHashMap bindings = new Int2IntOpenHashMap();
    /** Bumped whenever a buffer's device storage may be replaced, so callers can cache a {@link #device} result. */
    private volatile int generation;

    int generation() { return generation; }

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
            if (inside(store, offset, size) && arguments.pointer(3) != 0) {
                if (store.device != null) store.owner.writeGlDeviceBuffer(store.device, store.base + offset, arguments.pointer(3), size);
                else MemoryUtil.memCopy(arguments.pointer(3), store.address + offset, size);
            }
            return 0;
        }));
        table.put("glGet" + buffer + "SubData", locked(arguments -> {
            Store store = store(selector.name(arguments));
            long offset = arguments.pointer(1), size = arguments.pointer(2);
            if (store.address != 0 && inside(store, offset, size)) MemoryUtil.memCopy(store.address + offset, arguments.pointer(3), size);
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

    /** The buffer name bound to a target, as a vertex array reads it when a pointer is recorded. */
    synchronized int bound(int target) { return bindings.get(target); }

    /** Rebinds a target without a call from the mod, as binding a vertex array restores its element buffer. */
    synchronized void bind(int target, int name) { bindings.put(target, name); }

    private Store store(int name) { return stores.computeIfAbsent(name, (int ignored) -> new Store()); }

    /**
     * Moves a buffer drawn by a native renderer path to device memory and releases its host copy, so geometry is held once.
     * Later writes are staged in order with draws. Null when the name has no storage.
     */
    synchronized GpuBuffer device(BlazeRenderer renderer, int name) {
        Placed placed = placed(renderer, name, false);
        return placed == null ? null : placed.buffer();
    }

    /**
     * Like {@link #device}, but a buffer moved to the device now may share a page with others when {@code shared}, so
     * draws from many such buffers bind one buffer. Callers of a shared buffer must start at its {@link Placed#base}.
     */
    synchronized Placed placed(BlazeRenderer renderer, int name, boolean shared) {
        Store store = stores.get(name);
        if (store == null || (store.address == 0 && store.device == null)) return null;
        if (store.device == null) adopt(renderer, store, shared);
        return store.placed;
    }

    private static void adopt(BlazeRenderer renderer, Store store, boolean shared) {
        if (shared) {
            store.slice = renderer.newGlSharedBuffer(store.size);
            store.device = store.slice.buffer();
            store.base = store.slice.offset();
        } else {
            store.device = renderer.newGlDeviceBuffer(store.size);
            store.base = 0;
        }
        store.placed = new Placed(store.device, store.base);
        store.owner = renderer;
        if (store.size > 0) renderer.writeGlDeviceBuffer(store.device, store.base, store.address, store.size);
        MemoryUtil.nmemFree(store.address);
        store.address = 0;
    }

    private void releaseDevice(Store store) {
        if (store.device == null) return;
        generation++;
        if (store.slice != null) store.owner.retireGlSharedBuffer(store.slice);
        else store.owner.retireGlDeviceBuffer(store.device);
        store.device = null;
        store.slice = null;
        store.placed = null;
        store.base = 0;
        store.owner = null;
    }

    private long define(int name, long size, long source) {
        Store store = store(name);
        releaseDevice(store);
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
        if (!inside(source, fromOffset, size) || !inside(target, toOffset, size)) return 0;
        if (source.device != null) {
            // A device-held source can only be copied on the device, which pulls the destination there too.
            if (target.device == null) adopt(source.owner, target, false);
            source.owner.copyGlDeviceBuffer(source.device, source.base + fromOffset, target.device, target.base + toOffset, size);
        } else if (target.device != null) {
            target.owner.writeGlDeviceBuffer(target.device, target.base + toOffset, source.address + fromOffset, size);
        } else {
            MemoryUtil.memCopy(source.address + fromOffset, target.address + toOffset, size);
        }
        return 0;
    }

    private static boolean inside(Store store, long offset, long size) {
        return (store.address != 0 || store.device != null) && offset >= 0 && size >= 0 && offset <= store.size - size;
    }

    private void delete(int name) {
        Store store = stores.remove(name);
        if (store == null) return;
        releaseDevice(store);
        if (store.address != 0) MemoryUtil.nmemFree(store.address);
    }
}
