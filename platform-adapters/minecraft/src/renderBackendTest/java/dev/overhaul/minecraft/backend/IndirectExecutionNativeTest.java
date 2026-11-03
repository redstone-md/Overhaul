package dev.overhaul.minecraft.backend;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.SpvModule;
import dev.overhaul.shader.compile.*;
import dev.overhaul.vulkan.ImageRegion;
import dev.overhaul.vulkan.VulkanContext;
import org.joml.Vector4f;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.VkCommandBuffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("backend-native")
class IndirectExecutionNativeTest {
    @Test void multirecordIndirectCommandsSelectDistinctInstanceInputsAndCountOnlyAcceptedNativeCalls() {
        try(var scene=new Scene()){
            assertEquals(scene.owner.context().maxIndirectDrawCount(),scene.owner.getDeviceInfo().limits().maxDrawIndirectDrawCount());
            var first=scene.pass();first.drawIndexedIndirect(scene.indexed.slice(),2);scene.encoder.submitRenderPass();
            assertEquals(0,scene.stat("acceptedPasses"),"Capture cannot publish native command statistics");
            var receipt=scene.owner.queue().flush();
            assertEquals(1,scene.stat("acceptedPasses"),"Accepted passes: "+scene.owner.drawStatistics());assertEquals(1,scene.stat("indexedIndirectCalls"),"Indexed native calls: "+scene.owner.drawStatistics());
            assertEquals(2,scene.stat("indexedIndirectCommands"));assertEquals(1,scene.stat("indexedIndirectMultiRecordCalls"));
            assertEquals(1,scene.stat("instancedIndexedIndirectCalls"));assertEquals(2,scene.stat("maxIndexedIndirectBatch"));
            assertThrows(IllegalStateException.class,first::complete,"A completion callback cannot double count its pass");
            scene.owner.queue().await(receipt,-1L);
            scene.checkPixels(false);
            var second=scene.pass();second.drawIndirect(scene.nonIndexed.slice(),2);
            second.drawIndexed(3,1,0,0,1);second.draw(3,1,0,2);scene.encoder.submitRenderPass();scene.await();
            assertEquals(2,scene.stat("acceptedPasses"));assertEquals(1,scene.stat("indexedIndirectCalls"));
            assertEquals(1,scene.stat("nonIndexedIndirectCalls"));assertEquals(2,scene.stat("nonIndexedIndirectCommands"));
            assertEquals(1,scene.stat("nonIndexedIndirectMultiRecordCalls"));assertEquals(2,scene.stat("maxNonIndexedIndirectBatch"));
            assertEquals(1,scene.stat("instancedNonIndexedIndirectCalls"));
            assertEquals(1,scene.stat("directIndexedCalls"));assertEquals(1,scene.stat("directNonIndexedCalls"));
            scene.checkPixels(false);
        }
    }

    @Test void failedBatchDiscardsRecordedDrawStatisticsAndPixelsThenRecovers() {
        try(var scene=new Scene()){
            scene.pass().drawIndexedIndirect(scene.indexed.slice(),2);scene.encoder.submitRenderPass();scene.await();
            var accepted=scene.owner.drawStatistics();scene.checkPixels(false);
            var failed=scene.pass();failed.drawIndexedIndirect(scene.indexed.slice(),2);
            failed.drawIndirect(scene.nonIndexed.slice(),2);failed.drawIndexed(3,1,0,0,1);failed.draw(3,1,0,2);
            scene.encoder.submitRenderPass();
            scene.owner.queue().enqueue(new OverhaulGpuOperation(){
                @Override public void record(VkCommandBuffer command){
                    assertEquals(accepted,scene.owner.drawStatistics(),"Earlier recorded passes in a rejected batch remain unpublished");
                    throw new IllegalStateException("Reject after native draw recording");
                }
                @Override public void close(){}
            });
            assertThrows(IllegalStateException.class,()->scene.owner.queue().flush());
            assertEquals(accepted,scene.owner.drawStatistics());scene.checkPixels(false);
            var recovery=scene.pass();recovery.enableScissor(0,0,16,32);recovery.drawIndexedIndirect(scene.indexed.slice(),2);
            scene.encoder.submitRenderPass();scene.await();
            assertEquals(2,scene.stat("acceptedPasses"),"Recovery passes: "+scene.owner.drawStatistics());assertEquals(2,scene.stat("indexedIndirectCalls"),"Recovery calls: "+scene.owner.drawStatistics());
            assertEquals(4,scene.stat("indexedIndirectCommands"));assertEquals(2,scene.stat("indexedIndirectMultiRecordCalls"));
            assertEquals(0,scene.stat("directIndexedCalls"));assertEquals(0,scene.stat("directNonIndexedCalls"));
            assertEquals(0,scene.stat("nonIndexedIndirectCalls"));scene.checkPixels(true);
        }
    }

    private static final class Scene implements AutoCloseable {
        private final OverhaulDeviceBackend owner=new OverhaulDeviceBackend(VulkanContext.openHeadless(),new GpuDebugOptions(0,false,false,false));
        private OverhaulCommandEncoder encoder;
        private OverhaulPipeline pipeline;
        private GpuTexture target;
        private GpuTextureView view;
        private GpuBuffer vertices,instances,indices,indexed,nonIndexed;
        Scene(){
            try(var compiler=new ShadercCompiler()){
                assumeTrue(owner.context().multiDrawIndirect()&&owner.context().indirectFirstInstance(),"Native multirecord proof requires enabled MDI and first-instance support");
                assertTrue(owner.context().maxIndirectDrawCount()>=2,"Enabled multi-draw limit must be usable: "+owner.context().maxIndirectDrawCount());
                String vertex="""
                        #version 450
                        layout(location=0)in vec2 position;layout(location=1)in vec2 offset;
                        layout(location=2)in vec4 color;layout(location=0)out vec4 tint;
                        void main(){gl_Position=vec4(position+offset,.5,1);tint=color;}
                        """;
                String fragment="#version 450\nlayout(location=0)in vec4 tint;layout(location=0)out vec4 result;void main(){result=tint;}";
                var shaders=List.of(shader(compiler,vertex,ShaderStage.VERTEX,ShaderType.VERTEX),shader(compiler,fragment,ShaderStage.FRAGMENT,ShaderType.FRAGMENT));
                var info=new BackendRenderPipeline.CreateInfo("indirect native instances",shaders,
                        List.of(new BackendRenderPipeline.CreateInfo.VertexBuffer(0,8,0),new BackendRenderPipeline.CreateInfo.VertexBuffer(1,24,1)),
                        List.of(new BackendRenderPipeline.CreateInfo.AttribBinding(0,0,0,GpuFormat.RG32_FLOAT),
                                new BackendRenderPipeline.CreateInfo.AttribBinding(1,1,0,GpuFormat.RG32_FLOAT),
                                new BackendRenderPipeline.CreateInfo.AttribBinding(1,2,8,GpuFormat.RGBA32_FLOAT)),
                        List.of(),0,null,PolygonMode.FILL,false,List.of(ColorTargetState.DEFAULT),PrimitiveTopology.TRIANGLES);
                pipeline=(OverhaulPipeline)OverhaulPipeline.compile(owner,info).finishCompile();
                target=owner.createTexture("instance readback",GpuTexture.USAGE_RENDER_ATTACHMENT|GpuTexture.USAGE_COPY_SRC,GpuFormat.RGBA8_UNORM,32,32,1,1);
                view=owner.createTextureView(target,0,1);
                vertices=owner.createBuffer(()->"triangle",GpuBuffer.USAGE_VERTEX,floats(-.4f,-.8f,.4f,-.8f,0,.8f));
                // Instance zero is a blue sentinel: ignoring firstInstance cannot accidentally pass the pixel checks.
                instances=owner.createBuffer(()->"section instances",GpuBuffer.USAGE_VERTEX,floats(0,0,0,0,1,1,-.5f,0,1,0,0,1,.5f,0,0,1,0,1));
                indices=owner.createBuffer(()->"triangle indices",GpuBuffer.USAGE_INDEX,ints(0,1,2));
                indexed=owner.createBuffer(()->"two indexed records",GpuBuffer.USAGE_INDIRECT_PARAMETERS,ints(3,1,0,0,1,3,1,0,0,2));
                nonIndexed=owner.createBuffer(()->"two non-indexed records",GpuBuffer.USAGE_INDIRECT_PARAMETERS,ints(3,1,0,1,3,1,0,2));
                encoder=owner.createCommandEncoder();
            }catch(RuntimeException|Error failure){owner.close();throw failure;}
        }
        OverhaulRenderPass pass(){
            var pass=(OverhaulRenderPass)encoder.createRenderPass(RenderPassDescriptor.builder(()->"distinct first instances")
                    .withColorAttachment(view,Optional.of(new Vector4f(0,0,0,1))).build());
            pass.setPipeline(pipeline);pass.setVertexBuffer(0,vertices.slice());pass.setVertexBuffer(1,instances.slice());pass.setIndexBuffer(indices,IndexType.INT);
            return pass;
        }
        void await(){try(var fence=encoder.createFence()){assertTrue(fence.awaitCompletion(-1L));}}
        long stat(String key){return owner.drawStatistics().get(key);}
        void checkPixels(boolean rightCleared){
            var image=owner.image(target);var pixels=owner.commands().readback(image,ImageRegion.whole(image,0,0),0);
            pixel(pixels,8,16,255,0,0,255);pixel(pixels,24,16,0,rightCleared?0:255,0,255);pixel(pixels,16,16,0,0,0,255);
        }
        @Override public void close(){owner.close();}
    }
    private static BackendRenderPipeline.CreateInfo.Shader shader(ShadercCompiler compiler,String source,ShaderStage stage,ShaderType type){
        return new BackendRenderPipeline.CreateInfo.Shader("indirect test "+stage,"main",new Module(compiler.compile("indirect test "+stage,source,stage),type));
    }
    private record Module(SpirvBinary binary,ShaderType type)implements SpvModule{
        @Override public void close(){}@Override public ByteBuffer spv(){return binary.directBuffer();}
        @Override public Reflection reflect(){throw new UnsupportedOperationException("Explicit shader input layout");}
        @Override public Reflection getReflectionInfoIfAvailable(){return null;}
    }
    private static ByteBuffer floats(float...values){var data=ByteBuffer.allocateDirect(values.length*4).order(ByteOrder.nativeOrder());for(float value:values)data.putFloat(value);return data.flip();}
    private static ByteBuffer ints(int...values){var data=ByteBuffer.allocateDirect(values.length*4).order(ByteOrder.nativeOrder());for(int value:values)data.putInt(value);return data.flip();}
    private static void pixel(ByteBuffer pixels,int x,int y,int...expected){for(int channel=0;channel<4;channel++)assertEquals(expected[channel],pixels.get((y*32+x)*4+channel)&255,1,"pixel("+x+","+y+") channel "+channel);}
}
