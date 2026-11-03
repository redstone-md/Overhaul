package dev.overhaul.minecraft.weather;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@Tag("backend-native")
class WeatherInstanceWriterTest {
    @Test void preservesLargeWorldSubtractionEndpointOrderUvQuirkAlphaAndLight(){
        ByteBuffer storage=ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder());storage.putLong(0,0x1122334455667788L);storage.position(8).limit(56);
        WeatherInstanceWriter writer=new WeatherInstanceWriter(storage,1,new Direction(466,.6f,-.8f),
                new Vec3(30_000_000.25,64.125,-30_000_000.75),1f,4,.8f);
        writer.write(new WeatherEffectRenderer.ColumnInstance(30_000_002,-30_000_003,60,70,-.125f,.375f,0x00d4003c));writer.finish();
        assertEquals(56,storage.position());assertEquals(0x1122334455667788L,storage.getLong(0));
        float[] expected={1.95f,-1.35f,2.55f,-2.15f,5.875f,-4.125f,-.125f,.875f,15.375f,17.875f};
        for(int index=0;index<expected.length;index++)assertEquals(expected[index],storage.getFloat(8+index*4),1e-6,"Float field "+index);
        assertEquals(0x98ffffff,storage.getInt(48));assertEquals(0x00d4003c,storage.getInt(52));
        assertEquals(48,InstancedWeatherPipelines.INSTANCES.getVertexSize());assertEquals(1,InstancedWeatherPipelines.INSTANCES.getStepRate());
    }
    @Test void fixedCountRejectsTruncatedOrGrowingCapturedStorageAndKeepsDegenerateTableValues(){
        assertThrows(IllegalArgumentException.class,()->new WeatherInstanceWriter(ByteBuffer.allocateDirect(47),1,new Direction(528,0,0),Vec3.ZERO,1,1,1));
        WeatherInstanceWriter empty=new WeatherInstanceWriter(ByteBuffer.allocateDirect(0),0,new Direction(528,0,0),Vec3.ZERO,1,1,1);empty.finish();
        WeatherEffectRenderer.ColumnInstance column=new WeatherEffectRenderer.ColumnInstance(0,0,-1,1,0,0,0);
        assertThrows(IllegalStateException.class,()->empty.write(column));
        ByteBuffer storage=ByteBuffer.allocateDirect(48).order(ByteOrder.nativeOrder());
        WeatherInstanceWriter writer=new WeatherInstanceWriter(storage,1,new Direction(528,Float.NaN,Float.NaN),Vec3.ZERO,.8f,1,1);
        assertThrows(IllegalStateException.class,writer::finish);writer.write(column);writer.finish();
        for(int offset=0;offset<16;offset+=4)assertTrue(Float.isNaN(storage.getFloat(offset)),"No invented direction for vanilla's degenerate center entry");
        assertThrows(IllegalStateException.class,()->writer.write(column));
    }
    private static final class Direction implements WeatherGeometryAccess {
        private final int index;private final float x,z;
        private Direction(int index,float x,float z){this.index=index;this.x=x;this.z=z;}
        @Override public float overhaul$columnX(int index){assertEquals(this.index,index);return x;}
        @Override public float overhaul$columnZ(int index){assertEquals(this.index,index);return z;}
        @Override public void overhaul$prepareReference(VertexConsumer output,List<WeatherEffectRenderer.ColumnInstance> columns,Vec3 camera,float nearOpacity,int radius,float intensity){throw new UnsupportedOperationException("The live parity probe uses the actual renderer reference");}
    }
}
