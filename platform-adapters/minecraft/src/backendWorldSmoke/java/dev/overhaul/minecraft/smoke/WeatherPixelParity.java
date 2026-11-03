package dev.overhaul.minecraft.smoke;

import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.textures.*;
import dev.overhaul.minecraft.shader.OwnedVertexShaderSource;
import dev.overhaul.minecraft.weather.InstancedWeatherPipelines;
import dev.overhaul.minecraft.weather.OverhaulWeatherRenderer;
import dev.overhaul.minecraft.weather.WeatherGeometryAccess;
import dev.overhaul.minecraft.weather.WeatherInstanceWriter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.WeatherEffectRenderer.ColumnInstance;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

/** Live game expansion is the independent oracle; no copied expansion formula or synthetic renderer owner. */
public final class WeatherPixelParity {
    private static final int WIDTH=192,HEIGHT=128,STRIDE=InstancedWeatherPipelines.INSTANCE_STRIDE;
    private static final Vec3 CAMERA=new Vec3(-2.25,1.125,-3.75);
    private static final int RADIUS=12;
    private static final float INTENSITY=.83f;
    private static final List<ColumnInstance> COLUMNS=List.of(
            new ColumnInstance(-4,-7,0,4,-.35f,.43f,0x00B00030),
            new ColumnInstance(-1,-7,-1,3,1.25f,-1.7f,0xFFD000C0));
    private WeatherPixelParity(){}

    /** Must run on the renderer owner outside an active pass. Does not advance the game's transient frame. */
    public static Map<String,Object> verify(Minecraft minecraft,OverhaulWeatherRenderer renderer){
        RenderSystem.assertOnRenderThread();
        if(!((Object)renderer instanceof WeatherGeometryAccess geometry))throw new IllegalStateException("Weather reference invoker is absent");
        GpuDevice device=RenderSystem.getDevice();
        require(device.getDeviceInfo().features().drawIndirect()&&device.getDeviceInfo().features().nonZeroFirstInstance(),"Weather parity needs enabled indirect firstInstance");
        var original=RenderSystem.getCompiledPipeline(RenderPipelines.WEATHER);
        require(original!=null,"Original weather pipeline failed to compile");
        var variants=new InstancedWeatherPipelines();
        try(var pipelines=new PipelineCache(device,new OwnedVertexShaderSource(minecraft.getResourceManager()));var scene=new Scene(device)){
            var instanced=pipelines.get(variants.variant(RenderPipelines.WEATHER));
            require(instanced!=null,"Instanced weather pipeline failed to compile");
            var rain=scene.compare(geometry,original,instanced,1,"rain",minecraft.gameDirectory.toPath());
            var snow=scene.compare(geometry,original,instanced,.8f,"snow",minecraft.gameDirectory.toPath());
            require(((Number)rain.get("alphaSum")).longValue()>((Number)snow.get("alphaSum")).longValue(),"Rain/snow near-opacity inputs must produce different alpha");
            return Map.of("status","PASS","reference","live WeatherEffectRenderer.prepareInstances + game BufferBuilder",
                    "camera",List.of(CAMERA.x,CAMERA.y,CAMERA.z),"radius",RADIUS,"intensity",INTENSITY,
                    "instanceStride",STRIDE,"firstInstance",1,"rain",rain,"snow",snow);
        }
    }

    private static final class Scene implements AutoCloseable {
        private final CommandEncoder encoder;
        private final GpuBuffer projection,dynamic,fog,expandedIndices,quadIndices;
        private final GpuTexture atlas,light;
        private final GpuTextureView atlasView,lightView;
        private final GpuSampler atlasSampler,lightSampler;
        private final Target expected,actual;
        Scene(GpuDevice device){
            encoder=device.createCommandEncoder();
            projection=device.createBuffer(()->"weather parity projection",GpuBuffer.USAGE_UNIFORM,projection());
            dynamic=device.createBuffer(()->"weather parity transform",GpuBuffer.USAGE_UNIFORM,dynamic());
            fog=device.createBuffer(()->"weather parity fog",GpuBuffer.USAGE_UNIFORM,floats(0,0,0,1,100,101,100,101,100,100,0,0));
            expandedIndices=device.createBuffer(()->"weather reference indices",GpuBuffer.USAGE_INDEX,indices(COLUMNS.size()));
            quadIndices=device.createBuffer(()->"weather shared quad indices",GpuBuffer.USAGE_INDEX,indices(1));
            atlas=device.createTexture("weather parity repeat atlas",GpuTexture.USAGE_COPY_DST|GpuTexture.USAGE_TEXTURE_BINDING,GpuFormat.RGBA8_UNORM,16,16,1,1);
            light=device.createTexture("weather parity lightmap",GpuTexture.USAGE_COPY_DST|GpuTexture.USAGE_TEXTURE_BINDING,GpuFormat.RGBA8_UNORM,16,16,1,1);
            atlasView=device.createTextureView(atlas);lightView=device.createTextureView(light);
            atlasSampler=device.createSampler(AddressMode.REPEAT,AddressMode.REPEAT,FilterMode.NEAREST,FilterMode.NEAREST,1,OptionalDouble.empty());
            lightSampler=device.createSampler(AddressMode.CLAMP_TO_EDGE,AddressMode.CLAMP_TO_EDGE,FilterMode.LINEAR,FilterMode.LINEAR,1,OptionalDouble.empty());
            expected=new Target(device);actual=new Target(device);
            encoder.writeToTexture(atlas,texture(false),0,0,0,0,16,16);encoder.writeToTexture(light,texture(true),0,0,0,0,16,16);
        }
        Map<String,Object> compare(WeatherGeometryAccess geometry,CompiledRenderPipeline original,CompiledRenderPipeline instanced,
                                   float opacity,String label,Path directory){
            var memory=encoder.transientMemory();GpuBufferSlice expanded;
            try(var bytes=ByteBufferBuilder.exactlySized(COLUMNS.size()*4*DefaultVertexFormat.PARTICLE.getVertexSize())){
                var builder=new BufferBuilder(bytes,PrimitiveTopology.QUADS,DefaultVertexFormat.PARTICLE);
                geometry.overhaul$prepareReference(builder,COLUMNS,CAMERA,opacity,RADIUS,INTENSITY);
                try(var mesh=builder.buildOrThrow()){expanded=memory.uploadGpu(mesh.vertexBuffer(),16,GpuBuffer.USAGE_VERTEX);}
            }
            memory.uploadGpu(ByteBuffer.allocateDirect(STRIDE),16,GpuBuffer.USAGE_VERTEX,STRIDE,STRIDE);
            int records=COLUMNS.size()+1;
            var source=ByteBuffer.allocateDirect(records*STRIDE+16).order(ByteOrder.nativeOrder());source.position(8);
            var writer=new WeatherInstanceWriter(source.slice(8,records*STRIDE),records,geometry,CAMERA,opacity,RADIUS,INTENSITY);
            writer.write(new ColumnInstance(8,-7,0,4,0,0,0x00F000F0));
            for(var column:COLUMNS)writer.write(column);writer.finish();source.limit(8+records*STRIDE);
            int position=source.position(),limit=source.limit();
            var instances=memory.uploadGpu(source,16,GpuBuffer.USAGE_VERTEX,0,STRIDE);
            require(source.position()==position&&source.limit()==limit,"Transient upload consumed weather source state");
            require(instances.offset()>0,"Weather proof must bind a nonzero instance slice offset");
            source.clear();while(source.hasRemaining())source.put((byte)0x7F);
            var corners=memory.uploadGpu(floats(1,-1,1,1,-1,1,-1,-1),16,GpuBuffer.USAGE_VERTEX);
            memory.uploadGpu(ints(0,0,0,0,0),4,GpuBuffer.USAGE_INDIRECT_PARAMETERS);
            var commands=memory.uploadGpu(ints(6,COLUMNS.size(),0,0,1),4,GpuBuffer.USAGE_INDIRECT_PARAMETERS);
            require(commands.offset()>0,"Weather proof must bind a nonzero indirect slice offset");
            try(var pass=expected.begin(encoder,label+" expanded")){
                bind(pass,original);pass.setVertexBuffer(0,expanded);pass.setIndexBuffer(expandedIndices,IndexType.INT);
                pass.drawIndexed(COLUMNS.size()*6,1,0,0,0);
            }
            try(var pass=actual.begin(encoder,label+" instanced")){
                bind(pass,instanced);pass.setVertexBuffer(0,corners);pass.setVertexBuffer(1,instances);pass.setIndexBuffer(quadIndices,IndexType.INT);
                pass.drawIndexedIndirect(commands,1);
            }
            try(var fence=encoder.createFence()){require(fence.awaitCompletion(-1L),"Weather parity fence did not complete");}
            ByteBuffer reference=expected.read(encoder),result=actual.read(encoder);
            var evidence=comparePixels(reference,result);
            Path output=directory.resolve("overhaul-backend-smoke");
            evidence.put("referenceImage",save(output,"weather-parity-"+label+"-reference.png",reference));
            evidence.put("instancedImage",save(output,"weather-parity-"+label+"-instanced.png",result));
            evidence.put("nearOpacity",opacity);evidence.put("columns",COLUMNS.size());evidence.put("instanceSliceOffset",instances.offset());
            evidence.put("indirectSliceOffset",commands.offset());evidence.put("sourcePosition",position);evidence.put("sourceLimit",limit);
            evidence.put("sourceOverwrittenAfterUpload",true);evidence.put("signedLight",true);return Map.copyOf(evidence);
        }
        private void bind(RenderPass pass,CompiledRenderPipeline pipeline){
            pass.setPipeline(pipeline);pass.setUniform("Projection",projection);pass.setUniform("DynamicTransforms",dynamic);pass.setUniform("Fog",fog);
            pass.setUniform("Sampler0",atlasView,atlasSampler);pass.setUniform("Sampler2",lightView,lightSampler);
        }
        @Override public void close(){
            actual.close();expected.close();lightSampler.close();atlasSampler.close();lightView.close();atlasView.close();light.close();atlas.close();
            quadIndices.close();expandedIndices.close();fog.close();dynamic.close();projection.close();
        }
    }
    private static final class Target implements AutoCloseable {
        private final GpuTexture color,depth;private final GpuTextureView colorView,depthView;private final GpuBuffer readback;
        Target(GpuDevice device){
            color=device.createTexture("weather parity color",GpuTexture.USAGE_RENDER_ATTACHMENT|GpuTexture.USAGE_COPY_SRC,GpuFormat.RGBA8_UNORM,WIDTH,HEIGHT,1,1);
            depth=device.createTexture("weather parity depth",GpuTexture.USAGE_RENDER_ATTACHMENT,GpuFormat.D32_FLOAT,WIDTH,HEIGHT,1,1);
            colorView=device.createTextureView(color);depthView=device.createTextureView(depth);
            readback=device.createBuffer(()->"weather parity readback",GpuBuffer.USAGE_MAP_READ|GpuBuffer.USAGE_COPY_DST,WIDTH*HEIGHT*4);
        }
        RenderPass begin(CommandEncoder encoder,String label){
            return encoder.createRenderPass(()->"Overhaul weather parity "+label,colorView,Optional.of(new Vector4f(0)),depthView,OptionalDouble.of(RenderSystem.DEFAULT_DEPTH_CLEAR_VALUE));
        }
        ByteBuffer read(CommandEncoder encoder){
            encoder.copyTextureToBuffer(color,readback,0,()->{},0);
            try(var mapped=readback.map(0,readback.size(),true,false)){return ByteBuffer.allocateDirect(WIDTH*HEIGHT*4).put(mapped.data().duplicate()).flip();}
        }
        @Override public void close(){readback.close();depthView.close();colorView.close();depth.close();color.close();}
    }
    private static LinkedHashMap<String,Object> comparePixels(ByteBuffer reference,ByteBuffer actual){
        int covered=0,partial=0,different=0,maximum=0;long alphaSum=0;
        var colors=new HashSet<Integer>();var alphas=new HashSet<Integer>();
        for(int pixel=0;pixel<WIDTH*HEIGHT;pixel++){
            int offset=pixel*4;boolean mismatch=false;
            for(int channel=0;channel<4;channel++){
                int error=Math.abs((reference.get(offset+channel)&255)-(actual.get(offset+channel)&255));
                maximum=Math.max(maximum,error);if(error>2)mismatch=true;
            }
            if(mismatch)different++;
            int alpha=reference.get(offset+3)&255;alphaSum+=alpha;
            if(alpha>0){covered++;if(alpha<245)partial++;colors.add(reference.getInt(offset));alphas.add(alpha);}
        }
        require(covered>1000,"Weather reference coverage is insufficient: "+covered);
        require(partial>1000&&alphas.size()>8,"Weather opacity/texture must produce varied partial alpha: "+partial+" pixels, "+alphas.size()+" values");
        require(colors.size()>32,"Weather atlas/lightmap must produce varied visible color: "+colors.size());
        require(different<=4,"Instanced weather differs from original game expansion at "+different+" pixels; max channel error "+maximum);
        var result=new LinkedHashMap<String,Object>();result.put("coveredPixels",covered);result.put("partialAlphaPixels",partial);result.put("distinctAlphaValues",alphas.size());
        result.put("distinctColors",colors.size());result.put("differentPixels",different);result.put("maxChannelError",maximum);result.put("alphaSum",alphaSum);return result;
    }
    private static String save(Path directory,String name,ByteBuffer pixels){
        var image=new BufferedImage(WIDTH,HEIGHT,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<HEIGHT;y++)for(int x=0;x<WIDTH;x++){
            int p=(y*WIDTH+x)*4;int argb=((pixels.get(p+3)&255)<<24)|((pixels.get(p)&255)<<16)|((pixels.get(p+1)&255)<<8)|(pixels.get(p+2)&255);
            image.setRGB(x,HEIGHT-1-y,argb);
        }
        try{Files.createDirectories(directory);Path file=directory.resolve(name);if(!ImageIO.write(image,"png",file.toFile()))throw new IOException("PNG writer absent");return file.toString();}
        catch(IOException failure){throw new IllegalStateException("Cannot save weather parity pixels",failure);}
    }
    private static ByteBuffer projection(){
        var bytes=ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder());
        new Matrix4f().zero().m00(.35f).m11(.26f).m20(.07f).m22(.05f).m32(.5f).m33(1).get(0,bytes);return bytes;
    }
    private static ByteBuffer dynamic(){
        var bytes=ByteBuffer.allocateDirect(160).order(ByteOrder.nativeOrder());new Matrix4f().get(0,bytes);new Matrix4f().get(64,bytes);
        bytes.putFloat(128,.87f).putFloat(132,.73f).putFloat(136,.91f).putFloat(140,.8f);return bytes;
    }
    private static ByteBuffer texture(boolean light){
        var bytes=ByteBuffer.allocateDirect(16*16*4);
        for(int y=0;y<16;y++)for(int x=0;x<16;x++){
            if(light)bytes.put((byte)(40+x*11)).put((byte)(50+y*9)).put((byte)(70+((3*x+5*y)%16)*8)).put((byte)255);
            else bytes.put((byte)(30+x*13)).put((byte)(20+y*14)).put((byte)(40+((5*x+3*y)%16)*12)).put((byte)(128+((x+2*y)%8)*16));
        }return bytes.flip();
    }
    private static ByteBuffer indices(int quads){var bytes=ByteBuffer.allocateDirect(quads*24).order(ByteOrder.nativeOrder());for(int i=0;i<quads;i++)for(int index:new int[]{0,1,2,2,3,0})bytes.putInt(i*4+index);return bytes.flip();}
    private static ByteBuffer floats(float...values){var bytes=ByteBuffer.allocateDirect(values.length*4).order(ByteOrder.nativeOrder());for(float value:values)bytes.putFloat(value);return bytes.flip();}
    private static ByteBuffer ints(int...values){var bytes=ByteBuffer.allocateDirect(values.length*4).order(ByteOrder.nativeOrder());for(int value:values)bytes.putInt(value);return bytes.flip();}
    private static void require(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
}
