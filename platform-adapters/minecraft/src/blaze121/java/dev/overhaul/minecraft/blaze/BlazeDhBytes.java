package dev.overhaul.minecraft.blaze;

import it.unimi.dsi.fastutil.bytes.ByteArrayList;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/**
 * Distant Horizons reads every stored LOD byte by byte: once to copy a database blob into a list, and again through a
 * data stream that decodes it. Each single-byte read of a {@link ByteArrayInputStream} takes the stream's monitor, and
 * these streams never leave the thread that made them. The copy here moves the blob in blocks, and the decoding
 * stream reads the same bytes without the lock.
 */
public final class BlazeDhBytes {
    private static final int STEP = 4096;

    private BlazeDhBytes() {}

    /** The whole of {@code input} in {@code reuse} (cleared first), or in a new list when there is none. */
    public static ByteArrayList readAll(InputStream input, ByteArrayList reuse) throws IOException {
        ByteArrayList bytes = reuse == null ? new ByteArrayList(64) : reuse;
        bytes.clear();
        if (input == null) return bytes;
        int size = 0;
        try {
            while (true) {
                int room = Math.max(input.available(), STEP);
                bytes.size(size + room);
                int read = input.read(bytes.elements(), size, room);
                if (read < 0) break;
                size += read;
            }
        } catch (EOFException end) {
            // the game's own copy stops quietly at the end of the stream too
        }
        bytes.size(size);
        return bytes;
    }

    /** A byte array stream for one thread, read without taking its monitor. */
    public static final class Unshared extends ByteArrayInputStream {
        public Unshared(byte[] buffer) {
            super(buffer);
        }

        public Unshared(byte[] buffer, int offset, int length) {
            super(buffer, offset, length);
        }

        @Override
        public int read() {
            return pos < count ? buf[pos++] & 0xff : -1;
        }

        @Override
        public int read(byte[] target, int offset, int length) {
            java.util.Objects.checkFromIndexSize(offset, length, target.length);
            if (pos >= count) return -1;
            int moved = Math.min(length, count - pos);
            System.arraycopy(buf, pos, target, offset, moved);
            pos += moved;
            return moved;
        }

        @Override
        public long skip(long length) {
            long moved = Math.max(0, Math.min(length, count - pos));
            pos += (int) moved;
            return moved;
        }

        @Override
        public int available() {
            return count - pos;
        }
    }
}
