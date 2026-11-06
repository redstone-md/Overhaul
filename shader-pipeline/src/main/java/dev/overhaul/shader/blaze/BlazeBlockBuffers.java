package dev.overhaul.shader.blaze;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Uniform blocks that hold arrays or structs become storage buffers, numbered here once per block name for the whole
 * process. The number is the buffer index the storage buffer rewrite reads, so a draw can map its descriptor back to
 * the block a mod bound by name.
 */
public final class BlazeBlockBuffers {
    /** Far above the indices a shader pack's {@code bufferObject} buffers use. */
    private static final int FIRST = 4096;
    /** The descriptor name the storage buffer rewrite gives each buffer, followed by its index. */
    private static final String DESCRIPTOR = "overhaulBuffer";
    private static final Map<String, Integer> INDICES = new ConcurrentHashMap<>();
    private static final Map<Integer, String> NAMES = new ConcurrentHashMap<>();

    private BlazeBlockBuffers() {}

    /** The storage buffer index of a block, the same for every program that declares it. */
    public static int index(String block) {
        return INDICES.computeIfAbsent(block, name -> {
            int index = FIRST + NAMES.size();
            NAMES.put(index, name);
            return index;
        });
    }

    /** Whether a block was turned into a storage buffer. */
    public static boolean known(String block) {
        return INDICES.containsKey(block);
    }

    /** The block a storage buffer index stands for, or null for a shader pack's own buffer. */
    public static String block(int index) {
        return NAMES.get(index);
    }

    /** The block behind a storage buffer's descriptor name, {@code overhaulBuffer<index>}, or null for any other descriptor. */
    public static String blockOf(String descriptor) {
        if (!descriptor.startsWith(DESCRIPTOR)) return null;
        try {
            return block(Integer.parseInt(descriptor.substring(DESCRIPTOR.length())));
        } catch (NumberFormatException notIndexed) {
            return null;
        }
    }
}
