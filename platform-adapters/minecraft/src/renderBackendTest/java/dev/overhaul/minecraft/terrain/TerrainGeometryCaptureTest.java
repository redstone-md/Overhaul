package dev.overhaul.minecraft.terrain;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import dev.overhaul.core.culling.TerrainGeometryBuilder;
import dev.overhaul.core.memory.DirectSlabPool;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@Tag("backend-native")
class TerrainGeometryCaptureTest {
    @Test void fastAndChainedVerticesCaptureActualBoundsAndOnlySolidFaces() {
        var geometry=new TerrainGeometryBuilder();
        try(var pool=new DirectSlabPool(128,2);
            var solid=new TerrainVertexConsumer(pool,DefaultVertexFormat.BLOCK,bytes->{},geometry,true);
            var translucent=new TerrainVertexConsumer(pool,DefaultVertexFormat.BLOCK,bytes->{},geometry,false)) {
            float[][] face={{0,0,1},{1,0,1},{1,1,1},{0,1,1}};
            for(var p:face)solid.addVertex(p[0],p[1],p[2],-1,0,0,0,0,0,0,1);
            for(var p:face)translucent.addVertex(p[0]-2.5f,p[1],p[2]+.75f).setColor(-1).setUv(0,0).setLight(0);
            solid.finish();translucent.finish();
            assertEquals(0,pool.activeLeases());
        }
        var result=geometry.build();assertEquals(1,result.faceCount());
        assertEquals(2,result.faceAxis(0));assertEquals(1,result.faceSign(0));
        assertEquals(-2.5,result.min(0));assertEquals(1.75,result.max(2));
    }
}
