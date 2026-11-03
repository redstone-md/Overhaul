package dev.overhaul.core.mesh;

import dev.overhaul.core.memory.DirectSlabPool;
import dev.overhaul.core.world.BlockLighting;
import dev.overhaul.core.world.BlockLighting.LightVolume;
import dev.overhaul.core.world.ChunkSnapshot;
import java.nio.ByteBuffer;

/** Opaque cube mesher with neighbor-halo culling and counterclockwise face winding. */
public final class ChunkMesher {
    private static final int[][] DIRECTIONS = {
        {-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}
    };
    private static final int[][][] CORNERS = {
        {{0,0,1},{0,1,1},{0,1,0},{0,0,0}},
        {{1,0,0},{1,1,0},{1,1,1},{1,0,1}},
        {{0,0,0},{1,0,0},{1,0,1},{0,0,1}},
        {{0,1,1},{1,1,1},{1,1,0},{0,1,0}},
        {{0,0,0},{0,1,0},{1,1,0},{1,0,0}},
        {{1,0,1},{1,1,1},{0,1,1},{0,0,1}}
    };
    private static final int[] TRIANGLES = {0, 1, 2, 0, 2, 3};

    public ChunkMesh mesh(ChunkSnapshot snapshot, DirectSlabPool pool) {
        return mesh(snapshot, new BlockLighting().calculate(snapshot), pool);
    }

    public ChunkMesh mesh(ChunkSnapshot snapshot, LightVolume lighting, DirectSlabPool pool) {
        int faces = countFaces(snapshot);
        int vertexBytes = Math.multiplyExact(faces, 4 * ChunkMesh.VERTEX_STRIDE);
        int indexBytes = Math.multiplyExact(faces, 6 * Integer.BYTES);
        DirectSlabPool.Lease lease = pool.acquire(Math.addExact(vertexBytes, indexBytes));
        try {
            ByteBuffer data = lease.buffer();
            int faceIndex = 0;
            int edge = snapshot.edge();
            for (int y = 0; y < edge; y++) {
                for (int z = 0; z < edge; z++) {
                    for (int x = 0; x < edge; x++) {
                        int state = snapshot.state(x, y, z);
                        if (state == 0) continue;
                        for (int face = 0; face < 6; face++) {
                            int[] direction = DIRECTIONS[face];
                            int nx = x + direction[0], ny = y + direction[1], nz = z + direction[2];
                            if (snapshot.opaque(nx, ny, nz)) continue;
                            int light = Math.max(lighting.at(nx, ny, nz), snapshot.emission(x, y, z));
                            int attributes = state | (face << 24) | (light << 28);
                            for (int vertex = 0; vertex < 4; vertex++) {
                                int[] corner = CORNERS[face][vertex];
                                int offset = (faceIndex * 4 + vertex) * ChunkMesh.VERTEX_STRIDE;
                                data.putFloat(offset, x + corner[0]);
                                data.putFloat(offset + 4, y + corner[1]);
                                data.putFloat(offset + 8, z + corner[2]);
                                data.putInt(offset + 12, attributes);
                            }
                            for (int index = 0; index < 6; index++) {
                                data.putInt(vertexBytes + (faceIndex * 6 + index) * 4,
                                        faceIndex * 4 + TRIANGLES[index]);
                            }
                            faceIndex++;
                        }
                    }
                }
            }
            return new ChunkMesh(lease, faces * 4, faces * 6, snapshot.revision(), lighting);
        } catch (Throwable failure) {
            lease.close();
            throw failure;
        }
    }

    private int countFaces(ChunkSnapshot snapshot) {
        int count = 0;
        int edge = snapshot.edge();
        for (int y = 0; y < edge; y++) {
            for (int z = 0; z < edge; z++) {
                for (int x = 0; x < edge; x++) {
                    if (!snapshot.opaque(x, y, z)) continue;
                    for (int[] direction : DIRECTIONS) {
                        if (!snapshot.opaque(x + direction[0], y + direction[1], z + direction[2])) count++;
                    }
                }
            }
        }
        return count;
    }
}
