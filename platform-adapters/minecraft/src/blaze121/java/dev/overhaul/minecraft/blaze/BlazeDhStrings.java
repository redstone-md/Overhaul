package dev.overhaul.minecraft.blaze;

import it.unimi.dsi.fastutil.chars.CharArrayList;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The pooled strings Distant Horizons last handed out, per thread. Its pool walks a trie one character at a time, a
 * concurrent map lookup per character, for each block and biome name of every stored LOD; the names repeat endlessly.
 * A string kept here is the very instance the pool returned, and clearing the pool moves the generation on so that
 * nothing kept from before is handed out again.
 */
public final class BlazeDhStrings {
    private static final AtomicInteger GENERATION = new AtomicInteger();
    private static final int SLOTS = 4096, MASK = SLOTS - 1;
    private static final ThreadLocal<BlazeDhStrings> THREADS = ThreadLocal.withInitial(BlazeDhStrings::new);
    private final String[] strings = new String[SLOTS];
    private final int[] stamps = new int[SLOTS];

    private BlazeDhStrings() {
        Arrays.fill(stamps, -1);
    }

    public static BlazeDhStrings current() {
        return THREADS.get();
    }

    public static int generation() {
        return GENERATION.get();
    }

    /** Called when the pool is cleared. */
    public static void invalidate() {
        GENERATION.incrementAndGet();
    }

    /** The pooled string spelled by these characters in this generation, or null. */
    public String find(CharArrayList chars, int offset, int length, int generation) {
        int slot = slot(chars, offset, length);
        String kept = strings[slot];
        if (kept == null || stamps[slot] != generation || kept.length() != length) return null;
        char[] source = chars.elements();
        for (int i = 0; i < length; i++) {
            if (kept.charAt(i) != source[offset + i]) return null;
        }
        return kept;
    }

    public void keep(CharArrayList chars, int offset, int length, int generation, String pooled) {
        if (pooled == null || pooled.length() != length) return;
        int slot = slot(chars, offset, length);
        strings[slot] = pooled;
        stamps[slot] = generation;
    }

    private static int slot(CharArrayList chars, int offset, int length) {
        char[] source = chars.elements();
        int hash = length;
        for (int i = 0; i < length; i++) hash = 31 * hash + source[offset + i];
        return (hash ^ hash >>> 16) * 0x9E3779B9 >>> 20 & MASK;
    }
}
