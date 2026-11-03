package dev.overhaul.minecraft.backend;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.textures.*;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.SpvModule;
import com.mojang.renderpearl.util.TextureViewAndSampler;
import dev.overhaul.shader.compile.*;
import dev.overhaul.vulkan.ImageRegion;
import dev.overhaul.vulkan.VulkanContext;
import org.joml.Vector4f;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import static org.junit.jupiter.api.Assertions.*;

@Tag("backend-native")
class PipelineDrawNativeTest {
    private static final int SIZE=32;
    private static final String VERTEX="""
            #version 450
            void main(){ vec2 p[3]=vec2[](vec2(-1,-1),vec2(3,-1),vec2(-1,3));gl_Position=vec4(p[gl_VertexIndex],0.5,1); }
            """;

    @Test void sampledUniformAndTexelDescriptorsSurviveLogicalCloseUntilSubmissionCompletes() throws Exception {
        var owner=device();
        try(AutoCloseable cleanup=owner::close;var compiler=new ShadercCompiler();
            var target=owner.createTexture("descriptor output",GpuTexture.USAGE_RENDER_ATTACHMENT|GpuTexture.USAGE_COPY_SRC,GpuFormat.RGBA8_UNORM,SIZE,SIZE,1,1);
            var targetView=owner.createTextureView(target,0,1)) {
            String vertex="""
                    #version 450
                    layout(location=0) in vec2 position;layout(location=1) in vec2 uv;
                    layout(location=0) out vec2 coord;
                    void main(){gl_Position=vec4(position,.5,1);coord=uv;}
                    """;
            String fragment="""
                    #version 450
                    layout(location=0) in vec2 coord;layout(location=0) out vec4 result;
                    layout(set=0,binding=0,std140) uniform Params{vec4 color;}params;
                    layout(set=0,binding=1) uniform sampler2D image;
                    layout(set=0,binding=2) uniform samplerBuffer texels;
                    void main(){result=params.color*texture(image,coord)+texelFetch(texels,0);}
                    """;
            var shaders=shaders(compiler,vertex,fragment);
            var info=info(shaders,List.of(new BackendRenderPipeline.CreateInfo.VertexBuffer(0,16,0)),
                    List.of(new BackendRenderPipeline.CreateInfo.AttribBinding(0,0,0,GpuFormat.RG32_FLOAT),new BackendRenderPipeline.CreateInfo.AttribBinding(0,1,8,GpuFormat.RG32_FLOAT)),
                    List.of(new BindGroupLayout.UniformDescription("Params",UniformType.UNIFORM_BUFFER),new BindGroupLayout.UniformDescription("image",UniformType.COMBINED_IMAGE_SAMPLER),new BindGroupLayout.UniformDescription("texels",GpuFormat.RGBA32_FLOAT)),
                    0,null,List.of(ColorTargetState.DEFAULT));
            var pipeline=OverhaulPipeline.compile(owner,info).finishCompile();
            var vertices=owner.createBuffer(()->"vertices",GpuBuffer.USAGE_VERTEX,floats(-1,-1,.5f,.5f,3,-1,.5f,.5f,-1,3,.5f,.5f));
            var indices=owner.createBuffer(()->"indices",GpuBuffer.USAGE_INDEX,ints(0,1,2));
            int uniformStride=Math.max(64,owner.getDeviceInfo().limits().minUniformOffsetAlignment());
            ByteBuffer parameterData=ByteBuffer.allocateDirect(uniformStride*4).order(ByteOrder.nativeOrder());
            parameterData.put(uniformStride,floats(.5f,.25f,0,1),0,16);parameterData.put(uniformStride*3,floats(.5f,.25f,0,1),0,16);
            var params=owner.createBuffer(()->"params",GpuBuffer.USAGE_UNIFORM,parameterData);
            var unused=owner.createBuffer(()->"unused bound vertex",GpuBuffer.USAGE_VERTEX,floats(0,0,0,0));
            var texels=owner.createBuffer(()->"texels",GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER,floats(0,0,.25f,0));
            var image=owner.createTexture("sample",GpuTexture.USAGE_TEXTURE_BINDING|GpuTexture.USAGE_COPY_DST,GpuFormat.RGBA8_UNORM,1,1,1,1);
            var view=owner.createTextureView(image,0,1);
            var sampler=owner.createSampler(AddressMode.CLAMP_TO_EDGE,AddressMode.CLAMP_TO_EDGE,FilterMode.NEAREST,FilterMode.NEAREST,1,OptionalDouble.empty());
            var encoder=owner.createCommandEncoder();encoder.writeToTexture(image,bytes((byte)128,(byte)255,(byte)0,(byte)255),0,0,0,0,1,1);
            try(var upload=encoder.createFence()){assertTrue(upload.awaitCompletion(-1L));}
            var query=new OverhaulQueryPool(owner,1);
            try(var pass=new OverhaulRenderPass(owner,RenderPassDescriptor.builder(()->"descriptors").withColorAttachment(targetView,Optional.of(new Vector4f(0,0,0,1))).build())){
            pass.setPipeline(pipeline);pass.setVertexBuffer(0,vertices.slice());pass.setIndexBuffer(indices,IndexType.INT);
            pass.setVertexBuffer(15,unused.slice());
            pass.setUniform(0,params.slice(uniformStride,16));pass.setUniform(1,new TextureViewAndSampler(view,sampler));pass.setUniform(2,texels.slice());pass.enableScissor(8,8,16,16);pass.drawIndexed(3,1,0,0,0);
            pass.setUniform(0,params.slice(uniformStride*3,16));pass.drawIndexed(3,1,0,0,0);
            pass.writeTimestamp(query,0);long queryGeneration=query.generation(0);
            assertThrows(IllegalStateException.class,()->pass.writeTimestamp(query,0));assertEquals(queryGeneration,query.generation(0));
            pass.setVertexBuffer(0,null);
            assertThrows(IllegalStateException.class,()->pass.drawIndexed(3,1,0,0,0),"Unbinding a required slot must affect subsequent draws");
            assertThrows(NullPointerException.class,()->pass.setIndexBuffer(null,IndexType.INT));
            assertThrows(NullPointerException.class,()->pass.setIndexBuffer(indices,null));
            assertTrue(pass.usesBuffer((OverhaulBuffer)params,uniformStride,16));assertTrue(pass.usesBuffer((OverhaulBuffer)params,uniformStride*3,16));
            assertFalse(pass.usesBuffer((OverhaulBuffer)params,uniformStride+16,uniformStride*2-16),"Disjoint transient slices must not invent reads in their gap");
            assertFalse(pass.usesBuffer((OverhaulBuffer)params,0,16));assertTrue(pass.usesBuffer((OverhaulBuffer)vertices,0,vertices.size()));
            assertTrue(pass.usesBuffer((OverhaulBuffer)indices,0,indices.size()));assertFalse(pass.usesBuffer((OverhaulBuffer)unused,0,unused.size()),"Binding an unused vertex slot is not a draw read");
            assertTrue(pass.usesResource((BackendResource)unused));
            var retained=pass.retainedResources();assertTrue(retained.containsAll(List.of(pipeline,vertices,indices,params,texels,view,sampler,targetView,unused,query)));
            assertThrows(UnsupportedOperationException.class,retained::clear);
            assertFalse(pass.isSealed());assertFalse(pass.isRecorded());assertThrows(IllegalStateException.class,pass::complete);
            pass.seal();pass.seal();assertTrue(pass.isSealed());assertFalse(pass.isRecorded());
            assertThrows(IllegalStateException.class,()->pass.setVertexBuffer(0,null));assertThrows(IllegalStateException.class,()->pass.setUniform(0,params.slice(uniformStride,16)));
            assertThrows(IllegalStateException.class,pass::disableScissor);assertThrows(IllegalStateException.class,()->pass.draw(3,1,0,0));
            assertThrows(IllegalStateException.class,()->pass.writeTimestamp(null,0));
            // The earlier draw still owns its captured vertex binding after logical unbind.
            pipeline.close();vertices.close();indices.close();params.close();texels.close();view.close();image.close();sampler.close();unused.close();
            assertTrue(((BackendResource)vertices).isClosed());assertDoesNotThrow(()->owner.buffer(vertices).handle());
            assertFalse(pass.isRecorded(),"Sealing and logical resource close must not record or submit work");
            owner.commands().submit(pass::record);assertTrue(pass.isRecorded());assertThrows(IllegalStateException.class,()->pass.record(null));
            assertTrue(query.getValue(0).isEmpty(),"Recording is not submission publication, even if the test lane already completed");
            pass.complete();assertThrows(IllegalStateException.class,pass::complete);assertTrue(query.getValue(0).isPresent());query.close();pass.close();
            assertThrows(IllegalStateException.class,()->owner.buffer(vertices));assertTrue(pass.retainedResources().isEmpty());assertFalse(retained.isEmpty());
            var pixels=owner.commands().readback(owner.image(target),ImageRegion.whole(owner.image(target),0,0),0);
            pixel(pixels,16,16,64,64,64,255);pixel(pixels,4,4,0,0,0,255);
            }
        }
    }

    @Test void depthCompareWriteLoadAndIndependentMrtColorMasksAreNativeState() throws Exception {
        var owner=device();
        try(AutoCloseable cleanup=owner::close;var compiler=new ShadercCompiler();
            var first=owner.createTexture("first",GpuTexture.USAGE_RENDER_ATTACHMENT|GpuTexture.USAGE_COPY_SRC,GpuFormat.RGBA8_UNORM,SIZE,SIZE,1,1);
            var second=owner.createTexture("second",GpuTexture.USAGE_RENDER_ATTACHMENT|GpuTexture.USAGE_COPY_SRC,GpuFormat.RGBA8_UNORM,SIZE,SIZE,1,1);
            var depth=owner.createTexture("depth",GpuTexture.USAGE_RENDER_ATTACHMENT,GpuFormat.D32_FLOAT,SIZE,SIZE,1,1);
            var firstView=owner.createTextureView(first,0,1);var secondView=owner.createTextureView(second,0,1);var depthView=owner.createTextureView(depth,0,1)) {
            org.junit.jupiter.api.Assumptions.assumeTrue(owner.context().independentBlend(),"independent MRT write masks unavailable");
            String vertex=VERTEX.replace("void main()","layout(push_constant) uniform Push{vec4 color;float depth;}pc;void main()").replace("0.5,1","pc.depth,1");
            String fragment="""
                    #version 450
                    layout(push_constant) uniform Push{vec4 color;float depth;}pc;
                    layout(location=0) out vec4 first;layout(location=1) out vec4 second;
                    void main(){first=pc.color;second=vec4(pc.color.b,pc.color.r,pc.color.g,1);}
                    """;
            var mask=new ColorTargetState(Optional.empty(),GpuFormat.RGBA8_UNORM,ColorTargetState.WRITE_RED|ColorTargetState.WRITE_ALPHA);
            var info=info(shaders(compiler,vertex,fragment),List.of(),List.of(),List.of(),20,new DepthStencilState(CompareOp.LESS_THAN,true),List.of(mask,ColorTargetState.DEFAULT));
            try(var pipeline=OverhaulPipeline.compile(owner,info).finishCompile()){
                var encoder=owner.createCommandEncoder();
                var pass=encoder.createRenderPass(RenderPassDescriptor.builder(()->"MRT depth").withColorAttachment(firstView,Optional.of(new Vector4f(0,.3f,.4f,1)))
                        .withColorAttachment(secondView,Optional.of(new Vector4f(0,0,0,1))).withDepthAttachment(depthView,OptionalDouble.of(1)).build());
                pass.setPipeline(pipeline);pass.pushConstants(floats(.8f,.2f,.1f,1,.2f));pass.draw(3,1,0,0);
                pass.pushConstants(floats(.1f,.9f,.6f,1,.8f));pass.draw(3,1,0,0);encoder.submitRenderPass();
                var load=encoder.createRenderPass(RenderPassDescriptor.builder(()->"load previous depth").withColorAttachment(firstView,Optional.empty())
                        .withColorAttachment(secondView,Optional.empty()).withDepthAttachment(depthView,OptionalDouble.empty()).build());
                load.setPipeline(pipeline);load.pushConstants(floats(0,1,0,1,.3f));load.draw(3,1,0,0);encoder.submitRenderPass();
                try(var complete=encoder.createFence()){assertTrue(complete.awaitCompletion(-1L));}
                pixel(owner.commands().readback(owner.image(first),ImageRegion.whole(owner.image(first),0,0),0),16,16,204,77,102,255);
                pixel(owner.commands().readback(owner.image(second),ImageRegion.whole(owner.image(second),0,0),0),16,16,26,204,51,255);
            }
        }
    }

    @Test void directIndexedIndirectAndBothMultiDrawLayoutsProduceDifferentAccumulationCounts() throws Exception {
        var owner=device();
        try(AutoCloseable cleanup=owner::close;var compiler=new ShadercCompiler();
            var target=owner.createTexture("draw modes",GpuTexture.USAGE_RENDER_ATTACHMENT|GpuTexture.USAGE_COPY_SRC,GpuFormat.RGBA8_UNORM,SIZE,SIZE,1,1);
            var view=owner.createTextureView(target,0,1)) {
            String fragment="#version 450\nlayout(push_constant) uniform Color{vec4 value;}pc;layout(location=0) out vec4 result;void main(){result=pc.value;}";
            var additive=new ColorTargetState(new BlendFunction(BlendFactor.ONE,BlendFactor.ONE));
            try(var pipeline=OverhaulPipeline.compile(owner,info(shaders(compiler,VERTEX,fragment),List.of(),List.of(),List.of(),16,null,List.of(additive))).finishCompile();
                var unused=owner.createBuffer(()->"unused vertex binding",GpuBuffer.USAGE_VERTEX,floats(0,0,0,0));
                var index=owner.createBuffer(()->"indices",GpuBuffer.USAGE_INDEX,ints(0,1,2));
                var indirect=owner.createBuffer(()->"indirect",GpuBuffer.USAGE_INDIRECT_PARAMETERS,ints(3,1,0,0,3,1,0,0,3,1,0,0));
                var indexedIndirect=owner.createBuffer(()->"indexed indirect",GpuBuffer.USAGE_INDIRECT_PARAMETERS,ints(3,1,0,0,0,3,1,0,0,0,3,1,0,0,0))) {
                var encoder=owner.createCommandEncoder();var pass=encoder.createRenderPass(RenderPassDescriptor.builder(()->"draw modes").withColorAttachment(view,Optional.of(new Vector4f(0,0,0,1))).build());
                pass.setPipeline(pipeline);pass.setIndexBuffer(index,IndexType.INT);
                pass.setVertexBuffer(0,unused.slice());pass.setVertexBuffer(0,null);
                // Procedural shaders such as clouds intentionally consume no vertex buffer.
                pass.pushConstants(floats(.2f,0,0,0));pass.enableScissor(0,0,4,SIZE);pass.draw(3,1,0,0);
                pass.pushConstants(floats(0,.2f,0,0));pass.enableScissor(4,0,4,SIZE);pass.drawIndexed(3,1,0,0,0);
                pass.pushConstants(floats(0,0,.2f,0));pass.enableScissor(8,0,4,SIZE);pass.multiDraw(IntBuffer.wrap(new int[]{0,3,0,3}),1,0,2);
                pass.pushConstants(floats(.2f,0,.2f,0));pass.enableScissor(12,0,4,SIZE);pass.multiDrawIndexed(IntBuffer.wrap(new int[]{0,3,0,0,3,0}),1,0,2);
                pass.pushConstants(floats(.2f,0,0,0));pass.enableScissor(16,0,4,SIZE);pass.drawIndirect(indirect.slice(),3);
                pass.pushConstants(floats(0,.2f,0,0));pass.enableScissor(20,0,4,SIZE);pass.drawIndexedIndirect(indexedIndirect.slice(),3);
                pass.pushConstants(floats(0,0,.2f,0));pass.enableScissor(24,0,4,SIZE);pass.multiDraw(IntBuffer.wrap(new int[]{0,0,0,0}),IntBuffer.wrap(new int[]{3,3,3,3}),4);
                try(MemoryStack stack=MemoryStack.stackPush()){
                    pass.pushConstants(floats(.2f,.2f,0,0));pass.enableScissor(28,0,4,SIZE);pass.multiDrawIndexed(stack.pointers(0,0,0,0),IntBuffer.wrap(new int[]{3,3,3,3}),IntBuffer.wrap(new int[]{0,0,0,0}),4);
                }
                encoder.submitRenderPass();try(var complete=encoder.createFence()){assertTrue(complete.awaitCompletion(-1L));}
                var pixels=owner.commands().readback(owner.image(target),ImageRegion.whole(owner.image(target),0,0),0);
                pixel(pixels,2,16,51,0,0,255);pixel(pixels,6,16,0,51,0,255);pixel(pixels,10,16,0,0,102,255);pixel(pixels,14,16,102,0,102,255);
                pixel(pixels,18,16,153,0,0,255);pixel(pixels,22,16,0,153,0,255);pixel(pixels,26,16,0,0,204,255);pixel(pixels,30,16,204,204,0,255);
            }
        }
    }

    @Test void stagedUniformOverwriteBetweenQueuedPassesPreservesEarlierDrawAndSourceSnapshot() throws Exception {
        var owner=device();
        try(AutoCloseable cleanup=owner::close;var compiler=new ShadercCompiler();
            var target=owner.createTexture("ordered shared UBO",GpuTexture.USAGE_RENDER_ATTACHMENT|GpuTexture.USAGE_COPY_SRC,GpuFormat.RGBA8_UNORM,SIZE,SIZE,1,1);
            var view=owner.createTextureView(target,0,1);
            var uniform=owner.createBuffer(()->"shared color",GpuBuffer.USAGE_UNIFORM|GpuBuffer.USAGE_COPY_DST,floats(1,0,0,1))) {
            String fragment="#version 450\nlayout(set=0,binding=0,std140)uniform Params{vec4 color;}params;layout(location=0)out vec4 result;void main(){result=params.color;}";
            try(var pipeline=OverhaulPipeline.compile(owner,info(shaders(compiler,VERTEX,fragment),List.of(),List.of(),
                    List.of(new BindGroupLayout.UniformDescription("Params",UniformType.UNIFORM_BUFFER)),0,null,List.of(ColorTargetState.DEFAULT))).finishCompile()) {
                var encoder=owner.createCommandEncoder();
                var first=encoder.createRenderPass(RenderPassDescriptor.builder(()->"old uniform draw").withColorAttachment(view,Optional.of(new Vector4f(0,0,0,1))).build());
                first.setPipeline(pipeline);first.setUniform(0,uniform.slice());first.enableScissor(0,0,SIZE/2,SIZE);first.draw(3,1,0,0);encoder.submitRenderPass();
                ByteBuffer green=floats(0,1,0,1);encoder.writeToBuffer(uniform.slice(),green);
                green.putFloat(0,1).putFloat(4,0); // Mutate the caller's storage after staging capture.
                var second=encoder.createRenderPass(RenderPassDescriptor.builder(()->"new uniform draw").withColorAttachment(view,Optional.empty()).build());
                second.setPipeline(pipeline);second.setUniform(0,uniform.slice());second.enableScissor(SIZE/2,0,SIZE/2,SIZE);second.draw(3,1,0,0);encoder.submitRenderPass();
                assertEquals(0,owner.queue().submissions(),"Capturing two passes and an upload must not submit or wait");
                assertEquals(0,owner.queue().hostWaits());assertEquals(3,owner.queue().pendingOperations());
                uniform.close();pipeline.close();
                try(var fence=encoder.createFence()){assertTrue(fence.awaitCompletion(-1L));}
                assertEquals(1,owner.queue().submissions());
                ByteBuffer pixels=owner.commands().readback(owner.image(target),ImageRegion.whole(owner.image(target),0,0),0);
                pixel(pixels,8,16,255,0,0,255);pixel(pixels,24,16,0,255,0,255);
            }
        }
    }

    @Test void timestampGenerationsHideOldAvailabilityAndFailedRecordings() throws Exception {
        var owner=device();
        try(AutoCloseable cleanup=owner::close;var query=new OverhaulQueryPool(owner,1)) {
            query.prepare(0);long first=query.generation(0);
            owner.commands().submit(command->{query.reset(command,0);query.write(command,0);});
            assertTrue(query.getValue(0).isEmpty());query.markSubmitted(0,first);assertTrue(query.getValue(0).isPresent());
            query.prepare(0);long older=query.generation(0);query.prepare(0);long latest=query.generation(0);
            owner.commands().submit(command->{query.reset(command,0);query.write(command,0);});
            query.markSubmitted(0,older);assertTrue(query.getValue(0).isEmpty(),"A superseded submission cannot publish the latest query generation");
            owner.commands().submit(command->{query.reset(command,0);query.write(command,0);});
            query.markSubmitted(0,latest);assertTrue(query.getValue(0).isPresent());
            query.prepare(0);
            assertThrows(IllegalStateException.class,()->owner.commands().submit(command->{query.reset(command,0);query.write(command,0);throw new IllegalStateException("recording interrupted");}));
            assertTrue(query.getValue(0).isEmpty(),"Old native availability must not escape a failed recording");
            query.prepare(0);long recovered=query.generation(0);
            owner.commands().submit(command->{query.reset(command,0);query.write(command,0);});query.markSubmitted(0,recovered);
            assertTrue(query.getValue(0).isPresent());
        }
    }

    private static OverhaulDeviceBackend device(){return new OverhaulDeviceBackend(VulkanContext.openHeadless(),new GpuDebugOptions(0,false,false,false));}
    private static List<BackendRenderPipeline.CreateInfo.Shader> shaders(ShadercCompiler compiler,String vertex,String fragment){
        return List.of(new BackendRenderPipeline.CreateInfo.Shader("test.vert","main",new Module(compiler.compile("test.vert",vertex,ShaderStage.VERTEX),ShaderType.VERTEX)),
                new BackendRenderPipeline.CreateInfo.Shader("test.frag","main",new Module(compiler.compile("test.frag",fragment,ShaderStage.FRAGMENT),ShaderType.FRAGMENT)));
    }
    private static BackendRenderPipeline.CreateInfo info(List<BackendRenderPipeline.CreateInfo.Shader> shaders,List<BackendRenderPipeline.CreateInfo.VertexBuffer> vertices,
            List<BackendRenderPipeline.CreateInfo.AttribBinding> attributes,List<BindGroupLayout.UniformDescription> uniforms,int constants,DepthStencilState depth,List<ColorTargetState> colors){
        return new BackendRenderPipeline.CreateInfo("native test",shaders,vertices,attributes,uniforms,constants,depth,PolygonMode.FILL,false,colors,PrimitiveTopology.TRIANGLES);
    }
    private record Module(SpirvBinary binary,ShaderType type)implements SpvModule{
        @Override public void close(){}@Override public ByteBuffer spv(){return binary.directBuffer();}@Override public Reflection reflect(){throw new UnsupportedOperationException("Test source already has explicit descriptor bindings");}@Override public Reflection getReflectionInfoIfAvailable(){return null;}
    }
    private static ByteBuffer floats(float...values){ByteBuffer data=ByteBuffer.allocateDirect(values.length*4).order(ByteOrder.nativeOrder());for(float value:values)data.putFloat(value);return data.flip();}
    private static ByteBuffer ints(int...values){ByteBuffer data=ByteBuffer.allocateDirect(values.length*4).order(ByteOrder.nativeOrder());for(int value:values)data.putInt(value);return data.flip();}
    private static ByteBuffer bytes(byte...values){return ByteBuffer.allocateDirect(values.length).put(values).flip();}
    private static void pixel(ByteBuffer pixels,int x,int y,int...expected){for(int channel=0;channel<4;channel++)assertEquals(expected[channel],pixels.get((y*SIZE+x)*4+channel)&255,2,"pixel("+x+","+y+") channel "+channel);}
}
