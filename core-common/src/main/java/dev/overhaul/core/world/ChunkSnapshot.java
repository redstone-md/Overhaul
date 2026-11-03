package dev.overhaul.core.world;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.BufferUnderflowException;

/** Immutable palette-packed section with a one-voxel neighbor halo. Zero is air. */
public final class ChunkSnapshot {
    private static final int FORMAT_MAGIC = 0x4F564843;
    private static final int FORMAT_VERSION = 1;
    private static final int HEADER_BYTES = 36;
    private final int edge;
    private final int side;
    private final int bits;
    private final int[] palette;
    private final long[] states;
    private final byte[] emissions;
    private final long revision;

    private ChunkSnapshot(Builder builder) {
        edge = builder.edge;
        side = edge + 2;
        revision = builder.revision;
        emissions = builder.emissions.clone();
        Map<Integer, Integer> indices = new LinkedHashMap<Integer, Integer>();
        for (int value : builder.states) {
            if (!indices.containsKey(value)) indices.put(value, indices.size());
        }
        palette = new int[indices.size()];
        for (Map.Entry<Integer, Integer> entry : indices.entrySet()) {
            palette[entry.getValue()] = entry.getKey();
        }
        bits = Math.max(1, 32 - Integer.numberOfLeadingZeros(palette.length - 1));
        states = new long[(int) (((long) builder.states.length * bits + 63) / 64)];
        for (int i = 0; i < builder.states.length; i++) {
            long value = indices.get(builder.states[i]);
            long bit = (long) i * bits;
            int word = (int) (bit >>> 6);
            int shift = (int) (bit & 63);
            states[word] |= value << shift;
            if (shift + bits > 64) states[word + 1] |= value >>> (64 - shift);
        }
    }

    private ChunkSnapshot(int edge, int bits, int[] palette, long[] states, byte[] emissions, long revision) {
        this.edge = edge;
        this.side = edge + 2;
        this.bits = bits;
        this.palette = palette;
        this.states = states;
        this.emissions = emissions;
        this.revision = revision;
    }

    public static Builder builder(int edge) { return new Builder(edge); }
    public int edge() { return edge; }
    public long revision() { return revision; }
    public int paletteSize() { return palette.length; }
    public int packedStateBytes() { return states.length * Long.BYTES; }
    public int encodedBytes() { return HEADER_BYTES + palette.length * 4 + states.length * 8 + emissions.length; }

    /** Writes a stable big-endian transport format without changing the caller's buffer position. */
    public void encode(ByteBuffer target) {
        if (target.remaining() < encodedBytes()) throw new IllegalArgumentException("packet buffer is too small");
        ByteBuffer data = target.duplicate().order(ByteOrder.BIG_ENDIAN);
        data.putInt(FORMAT_MAGIC).putInt(FORMAT_VERSION).putInt(edge).putInt(bits)
                .putInt(palette.length).putInt(states.length).putInt(emissions.length).putLong(revision);
        for (int value : palette) data.putInt(value);
        for (long value : states) data.putLong(value);
        data.put(emissions);
    }

    /** Bounded parser; validates counts and palette references before publishing an immutable snapshot. */
    public static ChunkSnapshot decode(ByteBuffer source) {
        ByteBuffer data = source.duplicate().order(ByteOrder.BIG_ENDIAN);
        try {
            if (data.getInt() != FORMAT_MAGIC || data.getInt() != FORMAT_VERSION) {
                throw new IllegalArgumentException("unsupported chunk packet");
            }
            int edge = data.getInt(), bits = data.getInt(), paletteCount = data.getInt();
            int words = data.getInt(), lights = data.getInt();
            long revision = data.getLong();
            if (edge < 1 || edge > 64) throw new IllegalArgumentException("invalid edge");
            int volume = (edge + 2) * (edge + 2) * (edge + 2);
            if (paletteCount < 1 || paletteCount > volume || lights != volume
                    || bits != Math.max(1, 32 - Integer.numberOfLeadingZeros(paletteCount - 1))
                    || words != ((long) volume * bits + 63) / 64) {
                throw new IllegalArgumentException("invalid chunk packet counts");
            }
            long payload = (long) paletteCount * 4 + (long) words * 8 + lights;
            if (payload != data.remaining()) throw new IllegalArgumentException("invalid chunk packet length");
            int[] palette = new int[paletteCount];
            long[] states = new long[words];
            byte[] emissions = new byte[lights];
            for (int i = 0; i < palette.length; i++) {
                palette[i] = data.getInt();
                if (palette[i] < 0 || palette[i] > 0xFFFFFF) throw new IllegalArgumentException("invalid state");
            }
            for (int i = 0; i < states.length; i++) states[i] = data.getLong();
            data.get(emissions);
            for (byte emission : emissions) {
                if ((emission & 0xFF) > 15) throw new IllegalArgumentException("invalid light level");
            }
            ChunkSnapshot result = new ChunkSnapshot(edge, bits, palette, states, emissions, revision);
            for (int y = -1; y <= edge; y++) for (int z = -1; z <= edge; z++) for (int x = -1; x <= edge; x++) {
                result.state(x, y, z);
            }
            return result;
        } catch (BufferUnderflowException | ArrayIndexOutOfBoundsException invalid) {
            throw new IllegalArgumentException("malformed chunk packet", invalid);
        }
    }

    public int state(int x, int y, int z) {
        int index = index(x, y, z);
        long bit = (long) index * bits;
        int word = (int) (bit >>> 6);
        int shift = (int) (bit & 63);
        long value = states[word] >>> shift;
        if (shift + bits > 64) value |= states[word + 1] << (64 - shift);
        return palette[(int) (value & ((1L << bits) - 1))];
    }

    public int emission(int x, int y, int z) { return emissions[index(x, y, z)] & 15; }
    public boolean opaque(int x, int y, int z) { return state(x, y, z) != 0; }

    private int index(int x, int y, int z) {
        return index(edge, side, x, y, z);
    }

    private static int index(int edge, int side, int x, int y, int z) {
        if (x < -1 || x > edge || y < -1 || y > edge || z < -1 || z > edge) {
            throw new IndexOutOfBoundsException("position outside snapshot halo");
        }
        return ((y + 1) * side + z + 1) * side + x + 1;
    }

    public static final class Builder {
        private final int edge;
        private final int side;
        private final int[] states;
        private final byte[] emissions;
        private long revision;

        private Builder(int edge) {
            if (edge < 1 || edge > 64) throw new IllegalArgumentException("edge must be 1..64");
            this.edge = edge;
            side = edge + 2;
            states = new int[side * side * side];
            emissions = new byte[states.length];
        }

        public Builder revision(long value) { revision = value; return this; }

        public Builder set(int x, int y, int z, int state) {
            if (state < 0 || state > 0xFFFFFF) throw new IllegalArgumentException("24-bit state required");
            states[index(x, y, z)] = state;
            return this;
        }

        public Builder emission(int x, int y, int z, int value) {
            if (value < 0 || value > 15) throw new IllegalArgumentException("light must be 0..15");
            emissions[index(x, y, z)] = (byte) value;
            return this;
        }

        public Builder fill(int state) {
            if (state < 0 || state > 0xFFFFFF) throw new IllegalArgumentException("24-bit state required");
            Arrays.fill(states, state);
            return this;
        }

        public ChunkSnapshot build() { return new ChunkSnapshot(this); }

        private int index(int x, int y, int z) {
            return ChunkSnapshot.index(edge, side, x, y, z);
        }
    }
}
