package dev.overhaul.minecraft.model;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.oit.OitPipelineSet;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@Tag("backend-native")
class ModelPipelineCompatibilityTest {
    @Test void pinnedProgramsAcceptUnusedNullBindingSlotsIncludingAllEntityOitStages(){
        RenderPipeline[] pipelines={RenderPipelines.ENTITY_TRANSLUCENT,RenderPipelines.ENTITY_SOLID_GLINT,
                RenderPipelines.GLINT,RenderPipelines.OUTLINE_NO_CULL,RenderPipelines.CRUMBLING,
                RenderPipelines.OIT_ENTITY.depthBoundsPipeline(),RenderPipelines.OIT_ENTITY.transmittancePipeline(),RenderPipelines.OIT_ENTITY.accumulatePipeline()};
        for(RenderPipeline pipeline:pipelines){
            assertTrue(pipeline.getVertexFormatBindings().size()>1,"Pinned binding tables include unused null slots");
            assertTrue(InstancedModelPipelines.compatible(pipeline),pipeline.getLocation().toString());
        }
        assertTrue(InstancedModelPipelines.compatible(base("core/entity").withVertexBinding(0,DefaultVertexFormat.ENTITY_GLINT_SPECIAL).build()));
    }
    @Test void extraNonNullBindingsCannotBeDiscardedByTheInstancedVertexContract(){
        for(int slot:new int[]{1,7,15}){
            RenderPipeline original=base("core/entity").withVertexBinding(0,DefaultVertexFormat.ENTITY)
                    .withVertexBinding(slot,DefaultVertexFormat.POSITION).build();
            assertFalse(InstancedModelPipelines.compatible(original),"Extra binding "+slot);
            assertThrows(UnsupportedOperationException.class,()->new InstancedModelPipelines().variant(original));
        }
    }
    @Test void allOitPassesMustBeCompatibleBeforeAnimationBegins(){
        assertTrue(InstancedModelPipelines.compatibleOit(null));
        assertTrue(InstancedModelPipelines.compatibleOit(RenderPipelines.OIT_ENTITY));
        var valid=RenderPipelines.ENTITY_TRANSLUCENT;
        var invalid=base("core/particle").withVertexBinding(0,DefaultVertexFormat.ENTITY).build();
        assertFalse(InstancedModelPipelines.compatibleOit(new OitPipelineSet(valid,invalid,valid)));
        assertFalse(InstancedModelPipelines.compatibleOit(new OitPipelineSet(valid,valid,null)));
    }
    @Test void wrongNormalEncodingMissingBaseBindingTopologyAndUnknownShaderFailBeforeCapture(){
        VertexFormat unquantizedNormal=VertexFormat.builder(0).addAttribute("Position",GpuFormat.RGB32_FLOAT)
                .addAttribute("Color",GpuFormat.RGBA8_UNORM).addAttribute("UV0",GpuFormat.RG32_FLOAT)
                .addAttribute("UV1",GpuFormat.RG16_SINT).addAttribute("UV2",GpuFormat.RG16_SINT)
                .addAttribute("Normal",GpuFormat.RGB32_FLOAT).build();
        assertFalse(InstancedModelPipelines.compatible(base("core/entity").withVertexBinding(0,unquantizedNormal).build()),"A float normal input cannot silently become quantized");
        assertFalse(InstancedModelPipelines.compatible(base("core/entity").build()));
        assertFalse(InstancedModelPipelines.compatible(base("core/entity").withVertexBinding(0,DefaultVertexFormat.ENTITY).withPrimitiveTopology(PrimitiveTopology.TRIANGLES).build()));
        assertFalse(InstancedModelPipelines.compatible(base("core/particle").withVertexBinding(0,DefaultVertexFormat.ENTITY).build()));
        assertFalse(InstancedModelPipelines.compatible(base("core/glint").withVertexBinding(0,DefaultVertexFormat.ENTITY).build()));
        assertFalse(InstancedModelPipelines.compatible(null));
    }
    private static RenderPipeline.Builder base(String vertex){
        return RenderPipeline.builder().withLocation(Identifier.parse("overhaul:model_compatibility")).withVertexShader(Identifier.parse("minecraft:"+vertex))
                .withFragmentShader(Identifier.parse("minecraft:core/entity")).withPrimitiveTopology(PrimitiveTopology.QUADS);
    }
}
