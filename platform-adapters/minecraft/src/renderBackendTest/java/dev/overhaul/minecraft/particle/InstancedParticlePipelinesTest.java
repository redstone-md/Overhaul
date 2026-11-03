package dev.overhaul.minecraft.particle;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.*;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

@Tag("backend-native")
class InstancedParticlePipelinesTest {
    @Test void vertexVariantPreservesOitFragmentDefinitionsDescriptorsAndAttachmentState(){
        RenderPipeline original=RenderPipeline.builder().withLocation(Identifier.parse("overhaul:oit_particle_contract"))
                .withVertexShader(Identifier.parse("minecraft:core/particle")).withFragmentShader(Identifier.parse("minecraft:core/particle"))
                .withShaderDefine("OIT_ALPHA_ONLY").withShaderDefine("DEPTH_LAYERS",4).withShaderDefine("EXACT_THRESHOLD",.1f)
                .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS).withBindGroupLayout(BindGroupLayouts.SAMPLER0_SAMPLER2)
                .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN,false,1.5f,2.5f)).withCull(true).withPolygonMode(PolygonMode.WIREFRAME)
                .withColorTargetState(0,new ColorTargetState(Optional.empty(),GpuFormat.RGBA8_UNORM,ColorTargetState.WRITE_RED|ColorTargetState.WRITE_ALPHA))
                .withColorTargetState(1,new ColorTargetState(Optional.empty(),GpuFormat.RGBA16_FLOAT,ColorTargetState.WRITE_COLOR))
                .withVertexBinding(0,DefaultVertexFormat.PARTICLE).withPushConstantSize(16).withPrimitiveTopology(PrimitiveTopology.QUADS).build();
        InstancedParticlePipelines factory=new InstancedParticlePipelines();RenderPipeline variant=factory.variant(original);
        assertSame(variant,factory.variant(original));assertNotSame(variant,new InstancedParticlePipelines().variant(original),"Caches belong to renderer instances");
        assertEquals(original.getShaders().get(ShaderType.FRAGMENT),variant.getShaders().get(ShaderType.FRAGMENT));
        assertEquals(InstancedParticlePipelines.VERTEX_SHADER,variant.getShaders().get(ShaderType.VERTEX));
        assertSame(original.getShaderDefines(),variant.getShaderDefines());assertEquals(original.getBindGroupLayouts(),variant.getBindGroupLayouts());
        assertEquals(original.getDepthStencilState(),variant.getDepthStencilState());assertEquals(original.getColorTargetStates(),variant.getColorTargetStates());
        assertEquals(original.getPolygonMode(),variant.getPolygonMode());assertEquals(original.isCull(),variant.isCull());
        assertEquals(original.pushConstantSize(),variant.pushConstantSize());assertEquals(original.getSortKey(),variant.getSortKey());
        assertEquals(PrimitiveTopology.TRIANGLES,variant.getPrimitiveTopology());assertEquals(2,variant.getVertexFormatBindings().size());
        assertEquals(8,variant.getVertexFormatBinding(0).getVertexSize());assertEquals(0,variant.getVertexFormatBinding(0).getStepRate());
        assertEquals(56,variant.getVertexFormatBinding(1).getVertexSize());assertEquals(1,variant.getVertexFormatBinding(1).getStepRate());
        assertEquals(48,variant.getVertexFormatBinding(1).getElement("InstanceColor").offset());assertEquals(GpuFormat.R32_UINT,variant.getVertexFormatBinding(1).getElement("InstanceColor").format());
        assertEquals(52,variant.getVertexFormatBinding(1).getElement("InstanceLight").offset());assertEquals(GpuFormat.R32_UINT,variant.getVertexFormatBinding(1).getElement("InstanceLight").format());
    }
}
