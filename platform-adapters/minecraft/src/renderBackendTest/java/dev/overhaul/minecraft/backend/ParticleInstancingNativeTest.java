package dev.overhaul.minecraft.backend;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.*;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import dev.overhaul.minecraft.particle.InstancedParticlePipelines;
import dev.overhaul.minecraft.particle.ParticleInstanceWriter;
import dev.overhaul.vulkan.VulkanContext;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Actual game quad expansion is the independent reference for production shader instancing. */
@Tag("backend-native")
class ParticleInstancingNativeTest {
    private static final int WIDTH=128,HEIGHT=96,STRIDE=56;
    private record Particle(float x,float y,float z,float qx,float qy,float qz,float qw,float size,
                            float u0,float u1,float v0,float v1,int color,int light){
        void addTo(QuadParticleRenderState state,SingleQuadParticle.Layer layer){
            state.add(layer,x,y,z,qx,qy,qz,qw,size,u0,u1,v0,v1,color,light);
        }
        void write(QuadParticleRenderState.ParticleConsumer consumer){
            consumer.consume(x,y,z,qx,qy,qz,qw,size,u0,u1,v0,v1,color,light);
        }
    }
    private static final List<Particle> PARTICLES=List.of(
            new Particle(-.46f,-.08f,.52f,0,0,.37f,1.54f,.27f,.13f,.83f,.07f,.91f,0xA0D04090,0x00B00030),
            new Particle(.44f,.13f,.57f,.306f,-.187f,-.459f,1.547f,.24f,.21f,.94f,.17f,.77f,0xC030B0E0,0xFFB000D0));

    @Test void productionInstancedShaderMatchesGameExpandedQuadsWithPackedLightColorAndTransientOffsets() throws Exception {
        var backend=new OverhaulDeviceBackend(VulkanContext.openHeadless(),new GpuDebugOptions(0,false,false,false));
        var device=new FrontendGpuDevice(backend);boolean rendererOwns=false;
        try{
            assumeTrue(device.getDeviceInfo().features().nonZeroFirstInstance(),"Sentinel proof needs enabled firstInstance");
            RenderSystem.initRenderThread();RenderSystem.initRenderer(device);rendererOwns=true;
            var original=SingleQuadParticle.Layer.OPAQUE.pipeline();
            var variant=new InstancedParticlePipelines().variant(original);
            assertEquals(STRIDE,variant.getVertexFormatBinding(1).getVertexSize());
            assertEquals(0,variant.getVertexFormatBinding(0).getStepRate());assertEquals(1,variant.getVertexFormatBinding(1).getStepRate());
            try(var sources=new ClasspathShaderSource();var reference=compile(device,original,sources);var instanced=compile(device,variant,sources);
                var projection=device.createBuffer(()->"identity projection",GpuBuffer.USAGE_UNIFORM,matrix());
                var dynamic=device.createBuffer(()->"particle modulation",GpuBuffer.USAGE_UNIFORM,dynamic());
                var fog=device.createBuffer(()->"disabled fog",GpuBuffer.USAGE_UNIFORM,fog());
                var atlas=device.createTexture("pattern atlas",GpuTexture.USAGE_TEXTURE_BINDING|GpuTexture.USAGE_COPY_DST,GpuFormat.RGBA8_UNORM,16,16,1,1);
                var light=device.createTexture("asymmetric lightmap",GpuTexture.USAGE_TEXTURE_BINDING|GpuTexture.USAGE_COPY_DST,GpuFormat.RGBA8_UNORM,16,16,1,1);
                var atlasView=device.createTextureView(atlas);var lightView=device.createTextureView(light);
                var sampler=device.createSampler(AddressMode.CLAMP_TO_EDGE,AddressMode.CLAMP_TO_EDGE,FilterMode.NEAREST,FilterMode.NEAREST,1,OptionalDouble.empty());
                var expected=new Target(device);var actual=new Target(device)){
                var encoder=device.createCommandEncoder();encoder.writeToTexture(atlas,texture(false),0,0,0,0,16,16);
                encoder.writeToTexture(light,texture(true),0,0,0,0,16,16);
                var state=new QuadParticleRenderState();for(var particle:PARTICLES)particle.addTo(state,SingleQuadParticle.Layer.OPAQUE);
                GpuBufferSlice expanded;
                try(var storage=new ByteBufferBuilder(4*PARTICLES.size()*original.getVertexFormatBinding(0).getVertexSize())){
                    var builder=new BufferBuilder(storage,PrimitiveTopology.QUADS,original.getVertexFormatBinding(0));
                    state.buildLayer(SingleQuadParticle.Layer.OPAQUE,builder);
                    try(var mesh=builder.buildOrThrow()){
                        expanded=encoder.transientMemory().uploadGpu(mesh.vertexBuffer(),16,GpuBuffer.USAGE_VERTEX);
                    }
                }
                var referenceIndices=device.createBuffer(()->"expanded quad indices",GpuBuffer.USAGE_INDEX,indices(PARTICLES.size()));
                var staticIndices=device.createBuffer(()->"static quad indices",GpuBuffer.USAGE_INDEX,indices(1));
                try(referenceIndices;staticIndices){
                    // Force an independent nonzero slice offset before uploading a sentinel + two real instances.
                    var transientMemory=encoder.transientMemory();
                    transientMemory.uploadGpu(ByteBuffer.allocateDirect(STRIDE),16,GpuBuffer.USAGE_VERTEX,STRIDE,STRIDE);
                    ByteBuffer payload=ByteBuffer.allocateDirect(8+(PARTICLES.size()+1)*STRIDE+8).order(ByteOrder.nativeOrder());payload.position(8);
                    var writer=new ParticleInstanceWriter(payload.slice(8,(PARTICLES.size()+1)*STRIDE),PARTICLES.size()+1);
                    new Particle(3,3,.5f,0,0,0,1,.5f,0,1,0,1,0xFFFF00FF,0x00F000F0).write(writer);
                    for(var particle:PARTICLES)particle.write(writer);writer.finish();
                    payload.limit(8+(PARTICLES.size()+1)*STRIDE).position(8);
                    int position=payload.position(),limit=payload.limit();
                    var instances=transientMemory.uploadGpu(payload,16,GpuBuffer.USAGE_VERTEX,0,STRIDE);
                    assertEquals(position,payload.position());assertEquals(limit,payload.limit());assertTrue(instances.offset()>0);
                    while(payload.hasRemaining())payload.put((byte)0x7F);
                    var corners=transientMemory.uploadGpu(floats(1,-1,1,1,-1,1,-1,-1),16,GpuBuffer.USAGE_VERTEX);
                    transientMemory.uploadGpu(ints(0,0,0,0,0),4,GpuBuffer.USAGE_INDIRECT_PARAMETERS);
                    var commands=transientMemory.uploadGpu(ints(6,PARTICLES.size(),0,0,1),4,GpuBuffer.USAGE_INDIRECT_PARAMETERS);
                    assertTrue(commands.offset()>0);
                    try(var pass=expected.begin(encoder)){
                        bind(pass,reference,projection,dynamic,fog,atlasView,lightView,sampler);
                        pass.setVertexBuffer(0,expanded);pass.setIndexBuffer(referenceIndices,IndexType.INT);pass.drawIndexed(6*PARTICLES.size(),1,0,0,0);
                    }
                    try(var pass=actual.begin(encoder)){
                        bind(pass,instanced,projection,dynamic,fog,atlasView,lightView,sampler);
                        pass.setVertexBuffer(0,corners);pass.setVertexBuffer(1,instances);pass.setIndexBuffer(staticIndices,IndexType.INT);
                        pass.drawIndexedIndirect(commands,1);
                    }
                    try(var fence=encoder.createFence()){assertTrue(fence.awaitCompletion(-1L));}
                    compare(expected.read(encoder),actual.read(encoder));
                    assertEquals(1L,backend.drawStatistics().get("instancedIndexedIndirectCalls").longValue());
                    assertEquals(1L,backend.drawStatistics().get("indexedIndirectCommands").longValue());
                    encoder.submit();
                }
            }
        }finally{
            // RenderSystem exclusively owns frontend compiler callbacks once initialized.
            if(rendererOwns)RenderSystem.shutdownRenderer();else device.close();
        }
    }
    private static CompiledRenderPipeline compile(FrontendGpuDevice device,RenderPipeline specification,ClasspathShaderSource source) throws Exception{
        return device.compilePipeline(specification,source,CompletableFuture.delayedExecutor(0,java.util.concurrent.TimeUnit.MILLISECONDS)).get().finishCompile();
    }
    private static void bind(RenderPass pass,CompiledRenderPipeline pipeline,GpuBuffer projection,GpuBuffer dynamic,GpuBuffer fog,
                             GpuTextureView atlas,GpuTextureView light,GpuSampler sampler){
        pass.setPipeline(pipeline);pass.setUniform("Projection",projection);pass.setUniform("DynamicTransforms",dynamic);pass.setUniform("Fog",fog);
        pass.setUniform("Sampler0",atlas,sampler);pass.setUniform("Sampler2",light,sampler);
    }
    private static final class Target implements AutoCloseable {
        private final GpuTexture color,depth;private final GpuTextureView colorView,depthView;private final GpuBuffer readback;
        Target(FrontendGpuDevice device){
            color=device.createTexture("particle color",GpuTexture.USAGE_RENDER_ATTACHMENT|GpuTexture.USAGE_COPY_SRC,GpuFormat.RGBA8_UNORM,WIDTH,HEIGHT,1,1);
            depth=device.createTexture("particle depth",GpuTexture.USAGE_RENDER_ATTACHMENT,GpuFormat.D32_FLOAT,WIDTH,HEIGHT,1,1);
            colorView=device.createTextureView(color);depthView=device.createTextureView(depth);
            readback=device.createBuffer(()->"particle pixel readback",GpuBuffer.USAGE_MAP_READ|GpuBuffer.USAGE_COPY_DST,WIDTH*HEIGHT*4);
        }
        RenderPass begin(CommandEncoder encoder){return encoder.createRenderPass(()->"particle parity",colorView,Optional.of(new Vector4f(0)),depthView,OptionalDouble.of(RenderSystem.DEFAULT_DEPTH_CLEAR_VALUE));}
        ByteBuffer read(CommandEncoder encoder){
            encoder.copyTextureToBuffer(color,readback,0,()->{},0);
            try(var mapped=readback.map(0,readback.size(),true,false)){
                return ByteBuffer.allocateDirect(WIDTH*HEIGHT*4).put(mapped.data().duplicate()).flip();
            }
        }
        @Override public void close(){readback.close();depthView.close();colorView.close();depth.close();color.close();}
    }
    private static void compare(ByteBuffer expected,ByteBuffer actual){
        int covered=0,partialAlpha=0,different=0;var colors=new java.util.HashSet<Integer>();
        for(int pixel=0;pixel<WIDTH*HEIGHT;pixel++){
            int offset=pixel*4;boolean mismatch=false;
            for(int c=0;c<4;c++)if(Math.abs((expected.get(offset+c)&255)-(actual.get(offset+c)&255))>2)mismatch=true;
            if(mismatch)different++;
            int alpha=expected.get(offset+3)&255;
            if(alpha>0){covered++;if(alpha<245)partialAlpha++;colors.add(expected.getInt(offset));}
        }
        assertTrue(covered>800,"Reference scene must contain substantial particle coverage: "+covered);
        assertTrue(partialAlpha>500,"Packed alpha must survive actual particle fragment sampling");
        assertTrue(colors.size()>32,"Asymmetric atlas/lightmap must produce varied visible output: "+colors.size());
        assertTrue(different<=4,"Instanced shader differs from actual game expansion at "+different+" pixels");
    }
    private static ByteBuffer matrix(){var bytes=ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder());new Matrix4f().get(0,bytes);return bytes;}
    private static ByteBuffer dynamic(){
        var bytes=ByteBuffer.allocateDirect(160).order(ByteOrder.nativeOrder());new Matrix4f().get(0,bytes);new Matrix4f().get(64,bytes);
        bytes.putFloat(128,.8f).putFloat(132,.7f).putFloat(136,.6f).putFloat(140,.75f);return bytes;
    }
    private static ByteBuffer fog(){return floats(0,0,0,1,100,101,100,101,100,100,0,0);}
    private static ByteBuffer texture(boolean light){
        var bytes=ByteBuffer.allocateDirect(16*16*4);
        for(int y=0;y<16;y++)for(int x=0;x<16;x++){
            if(light)bytes.put((byte)(40+x*11)).put((byte)(50+y*9)).put((byte)(70+((3*x+5*y)%16)*8)).put((byte)255);
            else bytes.put((byte)(30+x*13)).put((byte)(20+y*14)).put((byte)(40+((5*x+3*y)%16)*12)).put((byte)(128+((x+2*y)%8)*16));
        }return bytes.flip();
    }
    private static ByteBuffer indices(int quads){var data=ByteBuffer.allocateDirect(quads*24).order(ByteOrder.nativeOrder());for(int i=0;i<quads;i++)for(int index:new int[]{0,1,2,2,3,0})data.putInt(i*4+index);return data.flip();}
    private static ByteBuffer floats(float...values){var data=ByteBuffer.allocateDirect(values.length*4).order(ByteOrder.nativeOrder());for(float value:values)data.putFloat(value);return data.flip();}
    private static ByteBuffer ints(int...values){var data=ByteBuffer.allocateDirect(values.length*4).order(ByteOrder.nativeOrder());for(int value:values)data.putInt(value);return data.flip();}
}
