package dev.overhaul.minecraft.model;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import dev.overhaul.minecraft.backend.OverhaulDeviceBackend;
import dev.overhaul.minecraft.backend.NativeBackendStatistics;
import dev.overhaul.vulkan.VulkanContext;
import net.minecraft.client.renderer.RenderPipelines;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("backend-native")
class ModelInstancingNativeTest {
    private record Scenario(String name,RenderPipeline pipeline,boolean decal,boolean partialAlpha,int minimumColors){}

    @Test void production124ByteInstancesMatchActualGameCubeAndDecalExpansionThroughOriginalPrograms() throws Exception {
        var backend=new OverhaulDeviceBackend(VulkanContext.openHeadless(),new GpuDebugOptions(0,false,false,false));
        var device=new FrontendGpuDevice(backend);boolean rendererOwns=false;
        try{
            assumeTrue(device.getDeviceInfo().features().drawIndirect()&&device.getDeviceInfo().features().nonZeroFirstInstance(),"Model sentinel proof needs enabled indirect firstInstance");
            RenderSystem.initRenderThread();RenderSystem.initRenderer(device);rendererOwns=true;
            var variants=new InstancedModelPipelines();verifyFullState(variants);
            List<Scenario> scenarios=List.of(
                    new Scenario("entity",RenderPipelines.ENTITY_TRANSLUCENT,false,true,32),
                    new Scenario("entity-decal-white-and-game-uv",RenderPipelines.ENTITY_TRANSLUCENT,true,true,32),
                    new Scenario("outline",RenderPipelines.OUTLINE_NO_CULL,false,true,2),
                    new Scenario("glint",RenderPipelines.GLINT,false,false,32),
                    new Scenario("entity-solid-glint",RenderPipelines.ENTITY_SOLID_GLINT,false,true,32),
                    new Scenario("crumbling",RenderPipelines.CRUMBLING,false,true,32));
            try(var source=new ModelVertexShaderSource(ModelGameOracle.resources());var scene=new ModelPixelHarness(device)){
                for(var scenario:scenarios){
                    var variant=variants.variant(scenario.pipeline());
                    try(var original=compile(device,scenario.pipeline(),source);var instanced=compile(device,variant,source)){
                        scene.compare(scenario.name(),scenario.pipeline(),original,instanced,scenario.decal(),scenario.partialAlpha(),scenario.minimumColors());
                    }
                }
                scene.finishEvidence();
            }
            assertEquals((long)scenarios.size(),NativeBackendStatistics.draws(backend).get("instancedIndexedIndirectCalls"));
            assertEquals((long)scenarios.size(),NativeBackendStatistics.draws(backend).get("indexedIndirectCommands"));
        }finally{if(rendererOwns)RenderSystem.shutdownRenderer();else device.close();}
    }

    private static void verifyFullState(InstancedModelPipelines variants){
        var originals=List.of(RenderPipelines.ENTITY_TRANSLUCENT,RenderPipelines.ENTITY_SOLID_GLINT,
                RenderPipelines.GLINT,RenderPipelines.OUTLINE_NO_CULL,RenderPipelines.CRUMBLING,
                RenderPipelines.OIT_ENTITY.depthBoundsPipeline(),RenderPipelines.OIT_ENTITY.transmittancePipeline(),
                RenderPipelines.OIT_ENTITY.accumulatePipeline());
        for(var original:originals){
            assertTrue(InstancedModelPipelines.compatible(original),original.getLocation()+" topology="+original.getPrimitiveTopology()+" formats="+original.getVertexFormatBindings()+" shaders="+original.getShaders());
            var variant=variants.variant(original);assertSame(variant,variants.variant(original));
            assertEquals(ModelVertexShaderSource.adaptedVertex(original.getShaders().get(ShaderType.VERTEX)),variant.getShaders().get(ShaderType.VERTEX));
            assertEquals(original.getShaders().get(ShaderType.FRAGMENT),variant.getShaders().get(ShaderType.FRAGMENT));
            assertSame(original.getShaderDefines(),variant.getShaderDefines());assertEquals(original.getBindGroupLayouts(),variant.getBindGroupLayouts());
            assertEquals(original.getColorTargetStates(),variant.getColorTargetStates());assertEquals(original.getDepthStencilState(),variant.getDepthStencilState());
            assertEquals(original.getPolygonMode(),variant.getPolygonMode());assertEquals(original.isCull(),variant.isCull());
            assertEquals(original.pushConstantSize(),variant.pushConstantSize());assertEquals(original.getSortKey(),variant.getSortKey());
            assertEquals(PrimitiveTopology.TRIANGLES,variant.getPrimitiveTopology());
            assertEquals(2,variant.getVertexFormatBindings().stream().filter(java.util.Objects::nonNull).count());assertEquals(0,variant.getVertexFormatBinding(0).getStepRate());
            assertEquals(1,variant.getVertexFormatBinding(1).getStepRate());assertEquals(124,variant.getVertexFormatBinding(1).getVertexSize());
        }
    }

    private static CompiledRenderPipeline compile(FrontendGpuDevice device,RenderPipeline pipeline,ModelVertexShaderSource source) throws Exception {
        var pending=device.compilePipeline(pipeline,source,CompletableFuture.delayedExecutor(0,TimeUnit.MILLISECONDS)).get();
        assertNotNull(pending,"Missing pending pipeline "+pipeline.getLocation()+" "+pipeline.getShaders());
        var result=pending.finishCompile();assertNotNull(result,"Failed pipeline "+pipeline.getLocation()+" "+pipeline.getShaders());return result;
    }
}
