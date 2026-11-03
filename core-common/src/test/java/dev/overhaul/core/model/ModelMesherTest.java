package dev.overhaul.core.model;

import dev.overhaul.core.memory.DirectSlabPool;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class ModelMesherTest {
    @Test void retainsIrregularGeometryUvSeamsTintPerVertexLightNormalsMaterialAndLayerRanges() {
        ModelVertex first=new ModelVertex(.125f,.25f,.375f,.2f,.8f,0x7f332211,.3f,.7f,-1,.5f,0);
        ModelQuad quad=new ModelQuad(RenderLayer.CUTOUT,79,.4f,first,
                vertex(.9f,.1f,0),vertex(.7f,.85f,0),vertex(.05f,.65f,0));
        ArrayList<ModelQuad> source=new ArrayList<ModelQuad>(Arrays.asList(quad,quad(RenderLayer.OPAQUE,0),quad(RenderLayer.TRANSLUCENT,1)));
        ModelSnapshot snapshot=new ModelSnapshot(9,30_000_000.25,0,0,source); source.clear();
        try (DirectSlabPool pool=new DirectSlabPool(2048,1);ModelMesh mesh=new ModelMesher().mesh(snapshot,pool)) {
            assertEquals(3,snapshot.quads().size());
            assertEquals(1,pool.activeLeases()); assertEquals(12,mesh.vertexCount()); assertEquals(18,mesh.indexCount());
            assertEquals(0,mesh.firstIndex(RenderLayer.OPAQUE)); assertEquals(6,mesh.firstIndex(RenderLayer.CUTOUT)); assertEquals(12,mesh.firstIndex(RenderLayer.TRANSLUCENT));
            ByteBuffer data=mesh.vertexBuffer(); int offset=4*ModelMesh.VERTEX_STRIDE;
            assertEquals(.125f,data.getFloat(offset)); assertEquals(.25f,data.getFloat(offset+4));
            assertEquals(.2f,(data.getShort(offset+12)&65535)/65535f,1f/65535);
            assertEquals(.8f,(data.getShort(offset+14)&65535)/65535f,1f/65535);
            assertEquals(0x11,data.get(offset+16)&255); assertEquals(0x7f,data.get(offset+19)&255);
            assertEquals(.3f,(data.getShort(offset+20)&65535)/65535f,1f/65535);
            assertEquals(.7f,(data.getShort(offset+22)&65535)/65535f,1f/65535);
            assertEquals(-127,data.get(offset+24)); assertEquals(64,data.get(offset+25));
            assertEquals(.4f,(data.get(offset+27)&255)/255f,1f/255); assertEquals(79,data.getInt(offset+28));
            assertThrows(java.nio.ReadOnlyBufferException.class,()->data.put(0,(byte)1));
            assertEquals(30_000_000.25,mesh.originX()); assertEquals(9,mesh.revision());
        }
    }

    @Test void pooledOwnershipAndStableTranslucentOrderingSurviveCameraReversal() {
        try (DirectSlabPool pool=new DirectSlabPool(2048,1)) {
            ModelMesh mesh=new ModelMesher().mesh(new ModelSnapshot(0,0,0,0,Arrays.asList(
                    quad(RenderLayer.TRANSLUCENT,-1),quad(RenderLayer.TRANSLUCENT,1),quad(RenderLayer.TRANSLUCENT,1))),pool);
            ModelTranslucentSorter sorter=mesh.newTranslucentSorter();
            mesh.close(); assertEquals(0,pool.activeLeases());
            assertThrows(IllegalStateException.class,mesh::vertexBuffer);
            ByteBuffer sorted=ByteBuffer.allocate(72).order(ByteOrder.nativeOrder());
            sorter.writeSortedIndices(sorted,0,0,3); sorted.flip();
            assertEquals(0,sorted.getInt(0)); assertEquals(4,sorted.getInt(24)); assertEquals(8,sorted.getInt(48));
            sorted.clear(); sorter.writeSortedIndices(sorted,0,0,-3); sorted.flip();
            assertEquals(4,sorted.getInt(0)); assertEquals(8,sorted.getInt(24)); assertEquals(0,sorted.getInt(48));
            assertThrows(IllegalArgumentException.class,()->sorter.writeSortedIndices(ByteBuffer.allocate(4),0,0,0));
        }
    }

    @Test void rejectsInvalidSourceCoordinatesAndPackedLightRatherThanGuessingFormats() {
        assertThrows(IllegalArgumentException.class,()->new ModelVertex(0,0,0,1.1f,0,-1,0,0,0,1,0));
        assertThrows(IllegalArgumentException.class,()->new ModelVertex(0,0,0,0,0,-1,255,0,1,0));
        ModelVertex vertex=new ModelVertex(0,0,0,0,0,-1,0x00f00020,0,1,0);
        assertEquals(40/256f,vertex.lightU()); assertEquals(248/256f,vertex.lightV());
        ModelVertex blended=new ModelVertex(0,0,0,0,0,-1,(124<<16)|60,0,1,0);
        assertEquals(68/256f,blended.lightU()); assertEquals(132/256f,blended.lightV());
        assertEquals(0x7f332211,ModelVertex.rgbaFromArgb(0x7f112233));
    }

    private static ModelVertex vertex(float x,float y,float z){return new ModelVertex(x,y,z,0,0,-1,1f,1f,0,0,1);}
    private static ModelQuad quad(RenderLayer layer,float z){return new ModelQuad(layer,0,.1f,vertex(0,0,z),vertex(1,0,z),vertex(1,1,z),vertex(0,1,z));}
}
