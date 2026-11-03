package dev.overhaul.minecraft.particle;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import dev.overhaul.minecraft.shader.InstancedPipelineVariants;
import net.minecraft.resources.Identifier;

/** Renderer-owned immutable pipeline variants retain the original fragment and complete render state. */
public final class InstancedParticlePipelines {
    public static final int INSTANCE_STRIDE=56;
    public static final Identifier VERTEX_SHADER=Identifier.fromNamespaceAndPath("overhaul","core/particle_instanced");
    public static final VertexFormat CORNERS=InstancedPipelineVariants.CORNERS;
    public static final VertexFormat INSTANCES=VertexFormat.builder(1)
            .addAttribute("InstanceCenter",GpuFormat.RGB32_FLOAT)
            .addAttribute("InstanceRotation",GpuFormat.RGBA32_FLOAT)
            .addAttribute("InstanceSize",GpuFormat.R32_FLOAT)
            .addAttribute("InstanceUv",GpuFormat.RGBA32_FLOAT)
            .addAttribute("InstanceColor",GpuFormat.R32_UINT)
            .addAttribute("InstanceLight",GpuFormat.R32_UINT).build();
    private final InstancedPipelineVariants variants=new InstancedPipelineVariants(VERTEX_SHADER,"particle_instanced",CORNERS,INSTANCES);
    public RenderPipeline variant(RenderPipeline original){return variants.variant(original);}
}
