package dev.overhaul.minecraft.model;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.textures.*;
import dev.overhaul.minecraft.shader.InstancedQuadGeometry;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import static org.junit.jupiter.api.Assertions.*;

/** Test-only native scene: original game vertex buffers and production instance records share all GPU inputs. */
final class ModelPixelHarness implements AutoCloseable {
    static final int WIDTH=192,HEIGHT=128;
    private static final int STRIDE=InstancedModelPipelines.INSTANCE_STRIDE;
    private final GpuDevice device;
    private final CommandEncoder encoder;
    private final GpuBuffer projection,dynamic,fog,lighting,globals,referenceIndices;
    private final GpuTexture atlas,light,overlay;
    private final GpuTextureView atlasView,lightView,overlayView;
    private final GpuSampler sampler;
    private final InstancedQuadGeometry geometry;
    private final Target expected,actual;
    private final List<ModelGameOracle.Draw> draws=ModelGameOracle.scene();
    private final Path evidenceDirectory=evidenceDirectory();
    private final Map<String,Object> completed=new LinkedHashMap<>();

    ModelPixelHarness(GpuDevice device){
        this.device=device;encoder=device.createCommandEncoder();
        projection=device.createBuffer(()->"model parity projection",GpuBuffer.USAGE_UNIFORM,matrix(new Matrix4f().m32(.5f)));
        dynamic=device.createBuffer(()->"model parity dynamic",GpuBuffer.USAGE_UNIFORM,dynamic());
        fog=device.createBuffer(()->"model parity fog",GpuBuffer.USAGE_UNIFORM,floats(0,0,0,1,100,101,100,101,100,100,0,0));
        lighting=device.createBuffer(()->"model parity lighting",GpuBuffer.USAGE_UNIFORM,floats(.2f,.7f,-.3f,0,-.7f,.2f,.6f,0));
        globals=device.createBuffer(()->"model parity globals",GpuBuffer.USAGE_UNIFORM,globals());
        referenceIndices=device.createBuffer(()->"model game quad indices",GpuBuffer.USAGE_INDEX,indices(draws.size()));
        atlas=texture("model parity atlas");light=texture("model parity signed lightmap");overlay=texture("model parity overlay");
        atlasView=device.createTextureView(atlas);lightView=device.createTextureView(light);overlayView=device.createTextureView(overlay);
        sampler=device.createSampler(AddressMode.REPEAT,AddressMode.REPEAT,FilterMode.NEAREST,FilterMode.NEAREST,1,OptionalDouble.empty());
        geometry=new InstancedQuadGeometry(device);expected=new Target(device);actual=new Target(device);
        encoder.writeToTexture(atlas,pattern(0),0,0,0,0,16,16);
        encoder.writeToTexture(light,pattern(1),0,0,0,0,16,16);
        encoder.writeToTexture(overlay,pattern(2),0,0,0,0,16,16);
    }

    Map<String,Long> compare(String label,RenderPipeline original,CompiledRenderPipeline reference,
                             CompiledRenderPipeline instanced,boolean decal,boolean partialAlpha,int minimumColors){
        assertNotNull(reference,"Original pipeline compilation returned null: "+label+" "+original.getShaders());
        assertNotNull(instanced,"Instanced pipeline compilation returned null: "+label+" "+original.getShaders());
        ModelGameOracle.Expanded game=ModelGameOracle.expand(draws,original.getVertexFormatBinding(0),decal);
        var memory=encoder.transientMemory();
        var expanded=memory.uploadGpu(game.vertices(),16,GpuBuffer.USAGE_VERTEX);
        memory.uploadGpu(ByteBuffer.allocateDirect(STRIDE),16,GpuBuffer.USAGE_VERTEX,STRIDE,STRIDE);
        int records=draws.size()+1;
        var source=ByteBuffer.allocateDirect(records*STRIDE+16).order(ByteOrder.nativeOrder());source.position(8);
        var writer=new ModelQuadInstanceWriter(source.slice(8,records*STRIDE),records);
        var sentinel=new PoseStack.Pose();sentinel.translate(4,4,0);
        ModelQuadGeometry first=ModelQuadGeometry.create(draws.getFirst().cube().polygons[0]);assertNotNull(first);
        writer.write(sentinel,first,game.uv8().getFirst(),new Vector3f(0,0,1),0xFFFF00FF,0x00F000F0,0);
        for(int i=0;i<draws.size();i++){
            var draw=draws.get(i);var polygon=draw.cube().polygons[0];
            ModelQuadGeometry quad=ModelQuadGeometry.create(polygon);assertNotNull(quad);
            writer.write(draw.pose(),quad,game.uv8().get(i),draw.pose().transformNormal(polygon.normal(),new Vector3f()),
                    decal?0xFFFFFFFF:draw.color(),draw.light(),draw.overlay());
        }
        writer.finish();assertEquals(records,writer.written());
        source.limit(8+records*STRIDE);int sourcePosition=source.position(),sourceLimit=source.limit();
        var instances=memory.uploadGpu(source,16,GpuBuffer.USAGE_VERTEX,0,STRIDE);
        assertEquals(sourcePosition,source.position());assertEquals(sourceLimit,source.limit());assertTrue(instances.offset()>0,label);
        source.clear();while(source.hasRemaining())source.put((byte)0x7F);
        memory.uploadGpu(ints(0,0,0,0,0),4,GpuBuffer.USAGE_INDIRECT_PARAMETERS);
        var commands=memory.uploadGpu(ints(6,draws.size(),0,0,1),4,GpuBuffer.USAGE_INDIRECT_PARAMETERS);
        assertTrue(commands.offset()>0,label);
        var depthState=original.getDepthStencilState();
        double clear=depthState!=null&&depthState.depthTest()==CompareOp.EQUAL?.5:RenderSystem.DEFAULT_DEPTH_CLEAR_VALUE;
        boolean multiplicative=original.getShaders().get(ShaderType.VERTEX).getPath().equals("core/rendertype_crumbling");
        var colorClear=multiplicative?new Vector4f(.2f,.3f,.4f,0):new Vector4f(0);
        try(var pass=expected.begin(encoder,label+" original",clear,colorClear)){
            bind(pass,original,reference);pass.setVertexBuffer(0,expanded);pass.setIndexBuffer(referenceIndices,IndexType.INT);
            pass.drawIndexed(draws.size()*6,1,0,0,0);
        }
        try(var pass=actual.begin(encoder,label+" instanced",clear,colorClear)){
            bind(pass,original,instanced);geometry.bind(pass,instances);pass.drawIndexedIndirect(commands,1);
        }
        try(var fence=encoder.createFence()){assertTrue(fence.awaitCompletion(-1L));}
        ByteBuffer referencePixels=expected.read(encoder),instancedPixels=actual.read(encoder);
        Map<String,Long> pixels=comparePixels(referencePixels,instancedPixels);
        Path referenceImage=save("model-"+label+"-reference.png",referencePixels),instancedImage=save("model-"+label+"-instanced.png",instancedPixels);
        var evidence=new LinkedHashMap<String,Object>();
        evidence.put("scenario",label);evidence.put("reference","unmixed ModelPart.Cube.compile + game BufferBuilder");
        evidence.put("pipeline",original.getLocation().toString());evidence.put("originalShaders",original.getShaders().toString());
        evidence.put("originalVertexStride",original.getVertexFormatBinding(0).getVertexSize());evidence.put("instanceStride",STRIDE);
        evidence.put("instances",draws.size());evidence.put("firstInstance",1);
        evidence.put("instanceSliceOffset",instances.offset());evidence.put("indirectSliceOffset",commands.offset());
        evidence.put("sourcePosition",sourcePosition);evidence.put("sourceLimit",sourceLimit);evidence.put("sourceOverwrittenAfterUpload",true);
        evidence.put("decal",decal);evidence.put("nonblackColorClear",multiplicative);evidence.put("fma",org.joml.Runtime.HAS_Math_fma);
        evidence.put("javaVersion",System.getProperty("java.version"));evidence.put("device",device.getDeviceInfo().toString());
        evidence.put("capturedAt",java.time.Instant.now().toString());evidence.put("width",WIDTH);evidence.put("height",HEIGHT);
        evidence.put("referenceImage",referenceImage.toString());evidence.put("instancedImage",instancedImage.toString());evidence.put("pixels",pixels);
        try{
            validatePixels(label,pixels,partialAlpha,minimumColors);evidence.put("status","PASS");
            completed.put(label,evidence);write("model-"+label+".json",evidence);
        }catch(AssertionError failure){
            evidence.put("status","FAIL");evidence.put("failure",failure.getMessage());write("model-"+label+".json",evidence);
            System.err.println("Model parity failure "+label+": "+pixels+"; evidence="+evidenceDirectory);throw failure;
        }
        System.out.println("Model parity "+label+": "+pixels+"; evidence="+evidenceDirectory);
        encoder.submit();return pixels;
    }

    void finishEvidence(){write("model-parity.json",Map.of("status","PASS","scope","native-model-input-pixel-comparison","scenarios",completed));}

    private void bind(RenderPass pass,RenderPipeline specification,CompiledRenderPipeline pipeline){
        pass.setPipeline(pipeline);
        for(var uniform:BindGroupLayout.flattenUniforms(specification.getBindGroupLayouts())){
            switch(uniform.name()){
                case "Projection"->pass.setUniform(uniform.name(),projection);
                case "DynamicTransforms"->pass.setUniform(uniform.name(),dynamic);
                case "Fog"->pass.setUniform(uniform.name(),fog);
                case "Lighting"->pass.setUniform(uniform.name(),lighting);
                case "Globals"->pass.setUniform(uniform.name(),globals);
                case "Sampler0","GlintSampler"->pass.setUniform(uniform.name(),atlasView,sampler);
                case "Sampler1"->pass.setUniform(uniform.name(),overlayView,sampler);
                case "Sampler2"->pass.setUniform(uniform.name(),lightView,sampler);
                default->throw new AssertionError("Unspecified original model binding "+uniform.name());
            }
        }
    }

    private GpuTexture texture(String name){return device.createTexture(name,GpuTexture.USAGE_TEXTURE_BINDING|GpuTexture.USAGE_COPY_DST,GpuFormat.RGBA8_UNORM,16,16,1,1);}

    private static Map<String,Long> comparePixels(ByteBuffer reference,ByteBuffer actual){
        long covered=0,partial=0,different=0,maximum=0;var colors=new HashSet<Integer>();
        // The fixture leaves the lower-left pixel untouched. Compare against its clear color, including nonblack crumbling clears.
        int background=reference.getInt(0);
        for(int pixel=0;pixel<WIDTH*HEIGHT;pixel++){
            int offset=pixel*4;boolean mismatch=false;
            for(int channel=0;channel<4;channel++){
                int error=Math.abs((reference.get(offset+channel)&255)-(actual.get(offset+channel)&255));maximum=Math.max(maximum,error);
                if(error>2)mismatch=true;
            }
            if(mismatch)different++;
            if(reference.getInt(offset)!=background){covered++;colors.add(reference.getInt(offset));int alpha=reference.get(offset+3)&255;if(alpha>0&&alpha<245)partial++;}
        }
        return Map.of("coveredPixels",covered,"partialAlphaPixels",partial,"distinctColors",(long)colors.size(),"differentPixels",different,"maxChannelError",maximum);
    }

    private static void validatePixels(String label,Map<String,Long> pixels,boolean requirePartial,int minimumColors){
        assertTrue(pixels.get("coveredPixels")>1000,label+" original reference must be visibly populated: "+pixels);
        if(requirePartial)assertTrue(pixels.get("partialAlphaPixels")>1000,label+" original reference needs substantial partial alpha: "+pixels);
        assertTrue(pixels.get("distinctColors")>=minimumColors,label+" original reference color variation is insufficient: "+pixels);
        assertTrue(pixels.get("differentPixels")<=4,label+" production input adapter differs from actual game expansion: "+pixels);
    }

    private static final class Target implements AutoCloseable {
        private final GpuTexture color,depth;
        private final GpuTextureView colorView,depthView;
        private final GpuBuffer readback;
        Target(GpuDevice device){
            color=device.createTexture("model parity color",GpuTexture.USAGE_RENDER_ATTACHMENT|GpuTexture.USAGE_COPY_SRC,GpuFormat.RGBA8_UNORM,WIDTH,HEIGHT,1,1);
            depth=device.createTexture("model parity depth",GpuTexture.USAGE_RENDER_ATTACHMENT,GpuFormat.D32_FLOAT,WIDTH,HEIGHT,1,1);
            colorView=device.createTextureView(color);depthView=device.createTextureView(depth);
            readback=device.createBuffer(()->"model pixel readback",GpuBuffer.USAGE_MAP_READ|GpuBuffer.USAGE_COPY_DST,WIDTH*HEIGHT*4);
        }
        RenderPass begin(CommandEncoder encoder,String label,double depthClear,Vector4f colorClear){
            return encoder.createRenderPass(()->label,colorView,Optional.of(colorClear),depthView,OptionalDouble.of(depthClear));
        }
        ByteBuffer read(CommandEncoder encoder){
            encoder.copyTextureToBuffer(color,readback,0,()->{},0);
            try(var mapped=readback.map(0,readback.size(),true,false)){
                return ByteBuffer.allocateDirect(WIDTH*HEIGHT*4).put(mapped.data().duplicate()).flip();
            }
        }
        @Override public void close(){readback.close();depthView.close();colorView.close();depth.close();color.close();}
    }

    @Override public void close(){
        actual.close();expected.close();geometry.close();sampler.close();overlayView.close();lightView.close();atlasView.close();overlay.close();light.close();atlas.close();
        referenceIndices.close();globals.close();lighting.close();fog.close();dynamic.close();projection.close();
    }

    private static Path evidenceDirectory(){
        Path base=Path.of(System.getProperty("overhaul.model.parityDirectory","build/verification/model-parity"));
        Path directory=base.resolve(org.joml.Runtime.HAS_Math_fma?"fma":"separate")
                .resolve("run-"+ProcessHandle.current().pid()+"-"+System.currentTimeMillis()).toAbsolutePath();
        try{Files.createDirectories(directory);return directory;}
        catch(IOException failure){throw new IllegalStateException("Cannot prepare model parity evidence",failure);}
    }
    private Path save(String name,ByteBuffer pixels){
        var image=new BufferedImage(WIDTH,HEIGHT,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<HEIGHT;y++)for(int x=0;x<WIDTH;x++){
            int p=(y*WIDTH+x)*4;int argb=((pixels.get(p+3)&255)<<24)|((pixels.get(p)&255)<<16)|((pixels.get(p+1)&255)<<8)|(pixels.get(p+2)&255);
            image.setRGB(x,HEIGHT-1-y,argb);
        }
        Path output=evidenceDirectory.resolve(name);
        try{if(!ImageIO.write(image,"png",output.toFile()))throw new IOException("PNG writer unavailable");return output;}
        catch(IOException failure){throw new IllegalStateException("Cannot save model parity image "+output,failure);}
    }
    private void write(String name,Object value){
        try{Files.writeString(evidenceDirectory.resolve(name),json(value)+"\n",StandardCharsets.UTF_8);}
        catch(IOException failure){throw new IllegalStateException("Cannot save model parity receipt",failure);}
    }
    private static String json(Object value){
        if(value==null)return"null";
        if(value instanceof Number||value instanceof Boolean)return value.toString();
        if(value instanceof Map<?,?> map){
            var joined=new java.util.StringJoiner(",\n","{\n","\n}");
            for(var entry:map.entrySet())joined.add(json(String.valueOf(entry.getKey()))+": "+json(entry.getValue()));return joined.toString();
        }
        return"\""+value.toString().replace("\\","\\\\").replace("\"","\\\"").replace("\r","\\r").replace("\n","\\n")+"\"";
    }
    private static ByteBuffer matrix(Matrix4f matrix){var bytes=ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder());matrix.get(0,bytes);return bytes;}
    private static ByteBuffer dynamic(){
        var bytes=ByteBuffer.allocateDirect(160).order(ByteOrder.nativeOrder());new Matrix4f().get(0,bytes);
        new Matrix4f().translation(.13f,.17f,0).scale(.91f,1.07f,1).get(64,bytes);
        bytes.putFloat(128,.87f).putFloat(132,.73f).putFloat(136,.91f).putFloat(140,.8f);return bytes;
    }
    private static ByteBuffer globals(){
        var bytes=ByteBuffer.allocateDirect(48).order(ByteOrder.nativeOrder());bytes.putFloat(12,.61f).putFloat(28,.37f);
        bytes.putFloat(32,WIDTH).putFloat(36,HEIGHT);return bytes;
    }
    private static ByteBuffer pattern(int kind){
        var bytes=ByteBuffer.allocateDirect(16*16*4);
        for(int y=0;y<16;y++)for(int x=0;x<16;x++){
            if(kind==1)bytes.put((byte)(40+x*11)).put((byte)(50+y*9)).put((byte)(70+((3*x+5*y)%16)*8)).put((byte)255);
            else if(kind==2)bytes.put((byte)(30+x*13)).put((byte)(20+y*14)).put((byte)(40+((5*x+3*y)%16)*12)).put((byte)(80+((x+2*y)%8)*19));
            else bytes.put((byte)(30+x*13)).put((byte)(20+y*14)).put((byte)(40+((5*x+3*y)%16)*12)).put((byte)(128+((x+2*y)%8)*16));
        }return bytes.flip();
    }
    private static ByteBuffer indices(int quads){var bytes=ByteBuffer.allocateDirect(quads*24).order(ByteOrder.nativeOrder());for(int quad=0;quad<quads;quad++)for(int index:new int[]{0,1,2,2,3,0})bytes.putInt(quad*4+index);return bytes.flip();}
    private static ByteBuffer floats(float... values){var bytes=ByteBuffer.allocateDirect(values.length*4).order(ByteOrder.nativeOrder());for(float value:values)bytes.putFloat(value);return bytes.flip();}
    private static ByteBuffer ints(int... values){var bytes=ByteBuffer.allocateDirect(values.length*4).order(ByteOrder.nativeOrder());for(int value:values)bytes.putInt(value);return bytes.flip();}
}
