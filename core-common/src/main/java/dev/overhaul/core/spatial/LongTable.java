package dev.overhaul.core.spatial;

/** Primitive-key open-addressing table. Confined to the owning simulation thread. */
final class LongTable<V> {
    private long[] keys = new long[16];
    private Object[] values = new Object[16];
    private byte[] states = new byte[16];
    private int size;
    private int used;

    @SuppressWarnings("unchecked") V get(long key) {
        int slot = find(key);
        return states[slot] == 1 ? (V) values[slot] : null;
    }

    void put(long key, V value) {
        if (used * 2 >= keys.length) resize();
        int mask = keys.length - 1;
        int slot = mix(key) & mask;
        int tombstone = -1;
        while (states[slot] != 0) {
            if (states[slot] == 1 && keys[slot] == key) { values[slot] = value; return; }
            if (states[slot] == 2 && tombstone == -1) tombstone = slot;
            slot = (slot + 1) & mask;
        }
        if (tombstone >= 0) slot = tombstone;
        else used++;
        states[slot] = 1;
        keys[slot] = key;
        values[slot] = value;
        size++;
    }

    void remove(long key) {
        int slot = find(key);
        if (states[slot] == 1) {
            states[slot] = 2;
            values[slot] = null;
            size--;
        }
    }

    int size() { return size; }

    private int find(long key) {
        int mask = keys.length - 1;
        int slot = mix(key) & mask;
        while (states[slot] != 0 && (states[slot] != 1 || keys[slot] != key)) slot = (slot + 1) & mask;
        return slot;
    }

    private void resize() {
        long[] previousKeys = keys;
        Object[] previousValues = values;
        byte[] previousStates = states;
        int capacity = size * 4 >= keys.length ? Math.multiplyExact(keys.length, 2) : keys.length;
        keys = new long[capacity]; values = new Object[capacity]; states = new byte[capacity];
        size = 0; used = 0;
        for (int i = 0; i < previousKeys.length; i++) {
            if (previousStates[i] == 1) {
                @SuppressWarnings("unchecked") V value = (V) previousValues[i];
                put(previousKeys[i], value);
            }
        }
    }

    private static int mix(long value) {
        value = (value ^ (value >>> 33)) * 0xff51afd7ed558ccdL;
        value = (value ^ (value >>> 33)) * 0xc4ceb9fe1a85ec53L;
        return (int) (value ^ (value >>> 33));
    }
}
