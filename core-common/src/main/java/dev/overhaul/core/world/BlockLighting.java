package dev.overhaul.core.world;

/** Six-neighbor emissive block lighting; does not mutate vanilla world light engines. */
public final class BlockLighting {
    private static final int[] DX = {-1, 1, 0, 0, 0, 0};
    private static final int[] DY = {0, 0, -1, 1, 0, 0};
    private static final int[] DZ = {0, 0, 0, 0, -1, 1};
    private final ThreadLocal<int[]> scratch = new ThreadLocal<int[]>() {
        @Override protected int[] initialValue() { return new int[0]; }
    };

    public LightVolume calculate(ChunkSnapshot snapshot) {
        int side = snapshot.edge() + 2;
        int volume = side * side * side;
        byte[] levels = new byte[volume];
        int[] queue = scratch.get();
        if (queue.length < volume * 16) { queue = new int[volume * 16]; scratch.set(queue); }
        int read = 0;
        int write = 0;
        for (int y = 0; y < side; y++) {
            for (int z = 0; z < side; z++) {
                for (int x = 0; x < side; x++) {
                    int index = (y * side + z) * side + x;
                    int emission = snapshot.emission(x - 1, y - 1, z - 1);
                    levels[index] = (byte) emission;
                    if (emission > 1) queue[write++] = index;
                }
            }
        }
        while (read < write) {
            int index = queue[read++];
            int x = index % side;
            int z = (index / side) % side;
            int y = index / (side * side);
            int next = levels[index] - 1;
            if (next < 1) continue;
            for (int face = 0; face < 6; face++) {
                int nx = x + DX[face], ny = y + DY[face], nz = z + DZ[face];
                if (nx < 0 || nx >= side || ny < 0 || ny >= side || nz < 0 || nz >= side) continue;
                int target = (ny * side + nz) * side + nx;
                if (levels[target] >= next || snapshot.opaque(nx - 1, ny - 1, nz - 1)) continue;
                levels[target] = (byte) next;
                queue[write++] = target;
            }
        }
        return new LightVolume(snapshot.edge(), levels);
    }

    public static final class LightVolume {
        private final int side;
        private final byte[] levels;
        private LightVolume(int edge, byte[] levels) { this.side = edge + 2; this.levels = levels; }

        public int at(int x, int y, int z) {
            if (x < -1 || x >= side - 1 || y < -1 || y >= side - 1 || z < -1 || z >= side - 1) {
                throw new IndexOutOfBoundsException("position outside light volume");
            }
            return levels[((y + 1) * side + z + 1) * side + x + 1] & 15;
        }
    }
}
