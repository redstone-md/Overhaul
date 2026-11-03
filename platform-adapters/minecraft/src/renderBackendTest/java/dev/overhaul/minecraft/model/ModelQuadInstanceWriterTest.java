package dev.overhaul.minecraft.model;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelPart;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import static org.junit.jupiter.api.Assertions.*;

@Tag("backend-native")
class ModelQuadInstanceWriterTest {
    @Test void recordsExactRowsCornersUvsQuantizedArbitraryNormalAndPackedMaterials(){
        PoseStack.Pose pose=new PoseStack.Pose();pose.translate(.125f,-.25f,3f);pose.scale(-2f,.5f,1.5f);
        float[] uv={.125137f,.75f,.9f,.8125f,.3f,-.25f,1.125f,.5f};
        ByteBuffer bytes=ByteBuffer.allocateDirect(124).order(ByteOrder.nativeOrder());ModelQuadGeometry quad=quad();
        ModelQuadInstanceWriter writer=new ModelQuadInstanceWriter(bytes,1);writer.write(pose,quad,uv,new Vector3f(-.5f,.3333f,2),0x80402010,0x00f0003c,0x00100002);writer.finish();
        assertEquals(124,bytes.position());assertEquals(1,writer.written());
        var row=new Vector4f();for(int index=0;index<3;index++){
            pose.pose().getRow(index,row);for(int component=0;component<4;component++)assertEquals(Float.floatToRawIntBits(row.get(component)),bytes.getInt(index*16+component*4));
        }
        for(int axis=0;axis<3;axis++){assertEquals(quad.min(axis),bytes.getFloat(48+axis*4));assertEquals(quad.max(axis),bytes.getFloat(60+axis*4));}
        for(int index=0;index<8;index++)assertEquals(Float.floatToRawIntBits(uv[index]),bytes.getInt(72+index*4));
        assertEquals(0x007f2ac1,bytes.getInt(104));assertEquals(0x80402010,bytes.getInt(108));assertEquals(0x00f0003c,bytes.getInt(112));assertEquals(0x00100002,bytes.getInt(116));
        assertEquals(quad.cornerMask(),bytes.getInt(120)&4095);assertEquals(0,bytes.getInt(120)&(ModelQuadInstanceWriter.IDENTITY|ModelQuadInstanceWriter.TRANSLATION));
        assertEquals(org.joml.Runtime.HAS_Math_fma, (bytes.getInt(120)&ModelQuadInstanceWriter.FMA)!=0);
        assertThrows(IllegalStateException.class,()->writer.write(pose,quad,uv,new Vector3f(1,0,0),0,0,0));
    }
    @Test void boundedVisibleCountAndJomlPropertyFastPathsRemainExplicit(){
        ByteBuffer bytes=ByteBuffer.allocateDirect(248).order(ByteOrder.nativeOrder());ModelQuadInstanceWriter writer=new ModelQuadInstanceWriter(bytes,2);
        PoseStack.Pose pose=new PoseStack.Pose();ModelQuadGeometry quad=quad();float[] uv=new float[8];
        writer.write(pose,quad,uv,new Vector3f(0,1,0),-1,0,0);assertTrue((bytes.getInt(120)&ModelQuadInstanceWriter.IDENTITY)!=0);
        writer.finish(1);assertThrows(IllegalStateException.class,writer::finish);assertThrows(IllegalStateException.class,()->writer.finish(3));
        pose.translate(1,2,3);writer.write(pose,quad,uv,new Vector3f(0,1,0),-1,0,0);writer.finish();
        assertTrue((bytes.getInt(244)&ModelQuadInstanceWriter.TRANSLATION)!=0);assertEquals(0,bytes.getInt(244)&ModelQuadInstanceWriter.IDENTITY);
    }
    private static ModelQuadGeometry quad(){
        ModelPart.Vertex[] vertices={new ModelPart.Vertex(-2,0,8,0,0),new ModelPart.Vertex(6,0,8,1,0),new ModelPart.Vertex(6,4,8,1,1),new ModelPart.Vertex(-2,4,8,0,1)};
        ModelQuadGeometry result=ModelQuadGeometry.create(new ModelPart.Polygon(vertices,new Vector3f(.2f,.7f,-.4f)));assertNotNull(result);return result;
    }
}
