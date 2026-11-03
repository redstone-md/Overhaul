package dev.overhaul.minecraft.terrain;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import dev.overhaul.core.memory.DirectSlabPool;
import dev.overhaul.core.memory.StreamingVertexWriter;
import dev.overhaul.core.culling.TerrainGeometryBuilder;
import java.nio.ByteBuffer;
import java.util.function.Consumer;

/** Minecraft 26.3 BLOCK encoding, independent of native BufferBuilder vertex writes. */
public final class TerrainVertexConsumer implements VertexConsumer,AutoCloseable {
    public static final int STRIDE=28;
    private static final int COLOR=1,UV=2,LIGHT=4;
    private final StreamingVertexWriter writer;
    private final TerrainGeometryBuilder geometry;
    private final boolean solid;
    private final float[] quad=new float[12];
    private int pending;
    private boolean finished,closed;

    public TerrainVertexConsumer(DirectSlabPool pool,VertexFormat format,Consumer<ByteBuffer> output){
        this(pool,format,output,null,false);
    }
    public TerrainVertexConsumer(DirectSlabPool pool,VertexFormat format,Consumer<ByteBuffer> output,TerrainGeometryBuilder geometry,boolean solid){
        validateFormat(format);writer=new StreamingVertexWriter(pool,STRIDE,output);
        this.geometry=geometry;this.solid=solid;
    }
    public static void validateFormat(VertexFormat format){
        if(format.getVertexSize()!=STRIDE||format.getStepRate()!=0||format.getElements().size()!=4
                ||!element(format,"Position",0,GpuFormat.RGB32_FLOAT)||!element(format,"Color",12,GpuFormat.RGBA8_UNORM)
                ||!element(format,"UV0",16,GpuFormat.RG32_FLOAT)||!element(format,"UV2",24,GpuFormat.RG16_SINT))
            throw new UnsupportedOperationException("Terrain encoder requires the Minecraft 26.3 BLOCK vertex layout: "+format);
    }
    private static boolean element(VertexFormat format,String name,int offset,GpuFormat type){var field=format.getElement(name);return field!=null&&field.offset()==offset&&field.format()==type;}
    @Override public VertexConsumer addVertex(float x,float y,float z){
        begin();position(x,y,z);pending=COLOR|UV|LIGHT;return this;
    }
    private void position(float x,float y,float z){
        writer.putFloat(0,x);writer.putFloat(4,y);writer.putFloat(8,z);
        if(geometry==null)return;
        geometry.vertex(x,y,z);
        if(solid){int corner=(writer.recordCount()-1)%4;quad[corner*3]=x;quad[corner*3+1]=y;quad[corner*3+2]=z;if(corner==3)geometry.solidQuad(quad);}
    }
    private void begin(){
        requireOpen();checkComplete();if(writer.recordCount()>=16_777_215)throw new IllegalStateException("Too many terrain vertices");writer.appendRecord();
    }
    @Override public VertexConsumer setColor(int red,int green,int blue,int alpha){
        if(take(COLOR)){writer.putByte(12,red);writer.putByte(13,green);writer.putByte(14,blue);writer.putByte(15,alpha);}return this;
    }
    @Override public VertexConsumer setColor(int argb){return setColor(argb>>>16,argb>>>8,argb,argb>>>24);}
    @Override public VertexConsumer setUv(float u,float v){if(take(UV)){writer.putFloat(16,u);writer.putFloat(20,v);}return this;}
    @Override public VertexConsumer setUv2(int u,int v){if(take(LIGHT)){writer.putShort(24,u);writer.putShort(26,v);}return this;}
    @Override public VertexConsumer setLight(int packed){return setUv2(packed,packed>>>16);}
    // BLOCK deliberately has no overlay, normals, glint UV, or line-width attributes.
    @Override public VertexConsumer setUv1(int u,int v){return this;}
    @Override public VertexConsumer setUv3(float u,float v){return this;}
    @Override public VertexConsumer setNormal(float x,float y,float z){return this;}
    @Override public VertexConsumer setLineWidth(float width){return this;}
    @Override public void addVertex(float x,float y,float z,int argb,float u,float v,int overlay,int light,float nx,float ny,float nz){
        begin();position(x,y,z);pending=COLOR|UV|LIGHT;setColor(argb);setUv(u,v);setLight(light);
    }
    private boolean take(int bit){requireOpen();if((pending&bit)==0)return false;pending&=~bit;return true;}
    private void checkComplete(){if(pending!=0)throw new IllegalStateException("Terrain vertex is missing "+((pending&COLOR)!=0?"Color ":"")+((pending&UV)!=0?"UV0 ":"")+((pending&LIGHT)!=0?"UV2":""));}
    private void requireOpen(){if(closed||finished)throw new IllegalStateException("Terrain consumer is closed or finished");}
    public int vertexCount(){return writer.recordCount();}
    public long byteCount(){return writer.byteCount();}
    public void finish(){requireOpen();checkComplete();writer.finish();finished=true;}
    @Override public void close(){if(!closed){closed=true;writer.close();}}
}
