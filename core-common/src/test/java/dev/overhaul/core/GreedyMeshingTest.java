package dev.overhaul.core;

import dev.overhaul.core.memory.DirectSlabPool;
import dev.overhaul.core.mesh.ChunkMesh;
import dev.overhaul.core.mesh.ChunkMesher;
import dev.overhaul.core.world.BlockLighting;
import dev.overhaul.core.world.ChunkSnapshot;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class GreedyMeshingTest {
    @Test void solidSectionCollapsesToSixQuads() {
        ChunkSnapshot.Builder builder=ChunkSnapshot.builder(16);
        for (int y=0;y<16;y++) for (int z=0;z<16;z++) for (int x=0;x<16;x++) builder.set(x,y,z,1);
        try (DirectSlabPool pool=new DirectSlabPool(1024,1);
             ChunkMesh mesh=new ChunkMesher().mesh(builder.build(),pool)) {
            assertEquals(24,mesh.vertexCount());
            assertEquals(36,mesh.indexCount());
            assertEquals(528,mesh.vertexBuffer().remaining()+mesh.indexBuffer().remaining());
        }
    }

    @Test void emittedRectanglesCoverExactlyTheVoxelSurfaceWithCorrectMaterialsAndWinding() {
        int edge=4;
        Random random=new Random(1033);
        int[][] directions={{-1,0,0},{1,0,0},{0,-1,0},{0,1,0},{0,0,-1},{0,0,1}};
        ChunkMesher mesher=new ChunkMesher();
        try (DirectSlabPool pool=new DirectSlabPool(128*1024,1)) {
            for (int trial=0;trial<32;trial++) {
                ChunkSnapshot.Builder builder=ChunkSnapshot.builder(edge);
                for (int y=-1;y<=edge;y++) for (int z=-1;z<=edge;z++) for (int x=-1;x<=edge;x++) {
                    builder.set(x,y,z,random.nextInt(4));
                }
                builder.emission(1,1,1,15);
                ChunkSnapshot snapshot=builder.build();
                BlockLighting.LightVolume light=new BlockLighting().calculate(snapshot);
                boolean[][] expected=new boolean[6][edge*edge*edge];
                for (int face=0;face<6;face++) for (int y=0;y<edge;y++) for (int z=0;z<edge;z++) for (int x=0;x<edge;x++) {
                    int[] d=directions[face];
                    expected[face][(y*edge+z)*edge+x]=snapshot.opaque(x,y,z)&&!snapshot.opaque(x+d[0],y+d[1],z+d[2]);
                }
                try (ChunkMesh mesh=mesher.mesh(snapshot,light,pool)) {
                    ByteBuffer vertices=mesh.vertexBuffer();
                    for (int quad=0;quad<mesh.vertexCount()/4;quad++) {
                        int base=quad*4*ChunkMesh.VERTEX_STRIDE;
                        int attributes=vertices.getInt(base+12), face=(attributes>>>24)&7, normal=face/2;
                        int[] min={edge+1,edge+1,edge+1},max={-1,-1,-1};
                        float[][] positions=new float[4][3];
                        for (int v=0;v<4;v++) {
                            assertEquals(attributes,vertices.getInt(base+v*16+12));
                            for (int axis=0;axis<3;axis++) {
                                float value=vertices.getFloat(base+v*16+axis*4); positions[v][axis]=value;
                                assertEquals((int)value,value);
                                min[axis]=Math.min(min[axis],(int)value); max[axis]=Math.max(max[axis],(int)value);
                            }
                        }
                        assertEquals(min[normal],max[normal]);
                        assertOutward(positions,directions[face]);
                        int slice=min[normal]-(face%2);
                        min[normal]=slice; max[normal]=slice+1;
                        for (int y=min[1];y<max[1];y++) for (int z=min[2];z<max[2];z++) for (int x=min[0];x<max[0];x++) {
                            int index=(y*edge+z)*edge+x;
                            assertTrue(expected[face][index],"unexpected or duplicated surface");
                            expected[face][index]=false;
                            assertEquals(snapshot.state(x,y,z),attributes&0xFFFFFF);
                            int[] d=directions[face];
                            assertEquals(Math.max(light.at(x+d[0],y+d[1],z+d[2]),snapshot.emission(x,y,z)),attributes>>>28);
                        }
                    }
                    for (boolean[] face:expected) for (boolean missing:face) assertFalse(missing,"missing surface");
                }
            }
        }
    }

    private static void assertOutward(float[][] p,int[] normal) {
        float ax=p[1][0]-p[0][0],ay=p[1][1]-p[0][1],az=p[1][2]-p[0][2];
        float bx=p[2][0]-p[0][0],by=p[2][1]-p[0][1],bz=p[2][2]-p[0][2];
        assertTrue((ay*bz-az*by)*normal[0]+(az*bx-ax*bz)*normal[1]+(ax*by-ay*bx)*normal[2]>0);
    }
}
