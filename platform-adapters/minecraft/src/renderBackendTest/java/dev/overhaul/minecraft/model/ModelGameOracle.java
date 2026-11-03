package dev.overhaul.minecraft.model;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.SheetedDecalTextureGenerator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/** The resolved game's unmixed compiler and decal generator provide independent reference vertices. */
final class ModelGameOracle {
    private ModelGameOracle(){}
    record Draw(ModelPart.Cube cube,PoseStack.Pose pose,int color,int light,int overlay){}
    record Expanded(ByteBuffer vertices,List<float[]> uv8){}

    static List<Draw> scene(){
        var identity=new PoseStack.Pose();
        var translated=new PoseStack.Pose();translated.translate(.37f,-.29f,0);
        var reflected=new PoseStack.Pose();reflected.translate(-.39f,.28f,0);
        reflected.rotate(new Quaternionf().rotationZ(.317f));reflected.scale(-.71f,.53f,1.13f);
        return List.of(new Draw(cube(false),identity,0xB9D04793,0x00B00030,0x000A0005),
                new Draw(cube(true),translated,0xA343B1D7,0xFFD000C0,0x0003000B),
                new Draw(cube(false),reflected,0xCD93C03F,0x00700090,0x000D0002));
    }

    static ModelPart.Cube cube(boolean mirror){
        var cube=new ModelPart.Cube(2,3,-8,-8,0,16,16,0,.137f,.091f,0,mirror,32,32,Set.of(Direction.SOUTH));
        assertEquals(1,cube.polygons.length);
        var polygon=cube.polygons[0];var vertices=polygon.vertices().clone();
        float[] uv={.13f,.07f,.83f,.19f,.74f,.93f,.09f,.72f};
        for(int vertex=0;vertex<4;vertex++)vertices[vertex]=vertices[vertex].remap(uv[vertex*2],uv[vertex*2+1]);
        cube.polygons[0]=new ModelPart.Polygon(vertices,new Vector3f(.21731f,-.61371f,1.13719f));
        return cube;
    }

    static Expanded expand(List<Draw> draws,VertexFormat format,boolean decal){
        int stride=format.getVertexSize();ByteBuffer result;
        try(var storage=new ByteBufferBuilder(draws.size()*4*stride)){
            var builder=new BufferBuilder(storage,PrimitiveTopology.QUADS,format);
            for(var draw:draws){
                VertexConsumer consumer=decal?new SheetedDecalTextureGenerator(builder,draw.pose(),.73f):builder;
                draw.cube().compile(draw.pose(),consumer,draw.light(),draw.overlay(),draw.color());
            }
            try(var mesh=builder.buildOrThrow()){
                assertEquals(draws.size()*4,mesh.drawState().vertexCount());
                result=ByteBuffer.allocateDirect(mesh.vertexBuffer().remaining()).order(ByteOrder.nativeOrder());
                result.put(mesh.vertexBuffer().duplicate()).flip();
            }
        }
        var uv=new java.util.ArrayList<float[]>();int offset=format.getElement("UV0").offset();
        for(int draw=0;draw<draws.size();draw++){
            float[] pairs=new float[8];
            for(int vertex=0;vertex<4;vertex++){
                int base=(draw*4+vertex)*stride+offset;pairs[vertex*2]=result.getFloat(base);pairs[vertex*2+1]=result.getFloat(base+4);
            }
            uv.add(pairs);
        }
        if(decal&&format.contains("Color")){
            int color=format.getElement("Color").offset();
            for(int vertex=0;vertex<draws.size()*4;vertex++)assertEquals(0xFFFFFFFF,result.getInt(vertex*stride+color),"Game decal consumer forces white color");
        }
        return new Expanded(result,List.copyOf(uv));
    }

    static ResourceManager resources(){
        return(ResourceManager)Proxy.newProxyInstance(ResourceManager.class.getClassLoader(),new Class<?>[]{ResourceManager.class},(proxy,method,arguments)->{
            if(method.getName().equals("open")){
                Identifier id=(Identifier)arguments[0];String path="/assets/"+id.getNamespace()+"/"+id.getPath();
                var input=ModelGameOracle.class.getResourceAsStream(path);if(input==null)throw new IOException("Missing original game resource "+path);return input;
            }
            throw new AssertionError("Unexpected resource operation "+method.getName());
        });
    }
}
