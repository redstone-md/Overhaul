package dev.overhaul.minecraft.blaze;

import java.util.Arrays;

/** A sorted set of longs in one array: a binary search finds a key with no allocation and no pointer to chase. */
final class BlazeSortedLongs {
    private long[] keys = new long[16];
    private int count;

    int size() {
        return count;
    }

    long get(int index) {
        return keys[index];
    }

    /** First index whose key is at least the one given; {@link #size()} when every key is smaller. */
    int slot(long key) {
        int low = 0, high = count;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (keys[mid] < key) low = mid + 1;
            else high = mid;
        }
        return low;
    }

    void add(long key) {
        int at = slot(key);
        if (count == keys.length) keys = Arrays.copyOf(keys, count * 2);
        System.arraycopy(keys, at, keys, at + 1, count - at);
        keys[at] = key;
        count++;
    }

    /** The key must be present. */
    void remove(long key) {
        removeAt(slot(key));
    }

    void removeAt(int at) {
        System.arraycopy(keys, at + 1, keys, at, count - at - 1);
        count--;
    }
}
