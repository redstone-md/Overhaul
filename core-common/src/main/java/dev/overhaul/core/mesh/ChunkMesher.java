package dev.overhaul.core.mesh;

import dev.overhaul.core.memory.DirectSlabPool;
import dev.overhaul.core.world.BlockLighting;
import dev.overhaul.core.world.BlockLighting.LightVolume;
import dev.overhaul.core.world.ChunkSnapshot;
import java.nio.ByteBuffer;

/** Greedy opaque cube mesher; only coplanar faces with identical material/light merge. */
public final class ChunkMesher {
    private final ThreadLocal<int[]> masks = new ThreadLocal<int[]>();
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
        int faces = scan(snapshot, lighting, null, 0);
        int vertexBytes = Math.multiplyExact(faces, 4 * ChunkMesh.VERTEX_STRIDE);
        int indexBytes = Math.multiplyExact(faces, 6 * Integer.BYTES);
        DirectSlabPool.Lease lease = pool.acquire(Math.addExact(vertexBytes, indexBytes));
        try {
            scan(snapshot, lighting, lease.buffer(), vertexBytes);
            return new ChunkMesh(lease, faces * 4, faces * 6, snapshot.revision(), lighting);
        } catch (Throwable failure) {
            lease.close();
            throw failure;
        }
    }

    /** Counting and emission share the same merge algorithm, so sizing cannot diverge. */
    private int scan(ChunkSnapshot snapshot, LightVolume lighting, ByteBuffer output, int indexOffset) {
        int edge = snapshot.edge();
        int[] mask = masks.get();
        if (mask == null || mask.length < edge * edge) {
            mask = new int[edge * edge]; masks.set(mask);
        }
        int rectangles = 0;
        int[] position = new int[3];
        for (int face = 0; face < 6; face++) {
            int axis = face / 2, uAxis = (axis + 1) % 3, vAxis = (axis + 2) % 3;
            int[] direction = DIRECTIONS[face];
            for (int slice = 0; slice < edge; slice++) {
                for (int v = 0; v < edge; v++) for (int u = 0; u < edge; u++) {
                    position[axis] = slice; position[uAxis] = u; position[vAxis] = v;
                    int x = position[0], y = position[1], z = position[2];
                    int nx = x + direction[0], ny = y + direction[1], nz = z + direction[2];
                    int state = snapshot.state(x,y,z);
                    int attributes = 0;
                    if (state != 0 && !snapshot.opaque(nx,ny,nz)) {
                        int light = Math.max(lighting.at(nx,ny,nz),snapshot.emission(x,y,z));
                        attributes = state | (face << 24) | (light << 28);
                    }
                    mask[v * edge + u] = attributes;
                }
                for (int v = 0; v < edge; v++) for (int u = 0; u < edge; u++) {
                    int attributes = mask[v * edge + u];
                    if (attributes == 0) continue;
                    int width = 1, height = 1;
                    while (u + width < edge && mask[v * edge + u + width] == attributes) width++;
                    while (v + height < edge && sameRow(mask,edge,u,v+height,width,attributes)) height++;
                    for (int row = 0; row < height; row++) {
                        java.util.Arrays.fill(mask,(v+row)*edge+u,(v+row)*edge+u+width,0);
                    }
                    if (output != null) {
                        position[axis] = slice; position[uAxis] = u; position[vAxis] = v;
                        write(output,indexOffset,rectangles,face,position,uAxis,vAxis,width,height,attributes);
                    }
                    rectangles++;
                }
            }
        }
        return rectangles;
    }

    private boolean sameRow(int[] mask, int edge, int u, int v, int width, int value) {
        for (int offset = 0; offset < width; offset++) if (mask[v*edge+u+offset] != value) return false;
        return true;
    }

    private void write(ByteBuffer data, int indexOffset, int quad, int face, int[] position,
                       int uAxis, int vAxis, int width, int height, int attributes) {
        for (int vertex = 0; vertex < 4; vertex++) {
            int offset = (quad * 4 + vertex) * ChunkMesh.VERTEX_STRIDE;
            for (int axis = 0; axis < 3; axis++) {
                int extent = axis == uAxis ? width : axis == vAxis ? height : 1;
                data.putFloat(offset+axis*4, position[axis] + CORNERS[face][vertex][axis] * extent);
            }
            data.putInt(offset+12, attributes);
        }
        for (int index = 0; index < 6; index++) {
            data.putInt(indexOffset+(quad*6+index)*4,quad*4+TRIANGLES[index]);
        }
    }
}
