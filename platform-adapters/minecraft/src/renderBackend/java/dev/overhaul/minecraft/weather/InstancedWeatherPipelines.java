package dev.overhaul.minecraft.weather;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import dev.overhaul.minecraft.shader.InstancedPipelineVariants;
import net.minecraft.resources.Identifier;

/** Exact precomputed weather endpoints avoid changing vanilla float vertex-expansion order. */
public final class InstancedWeatherPipelines {
    public static final int INSTANCE_STRIDE=48;
    public static final Identifier VERTEX_SHADER=Identifier.fromNamespaceAndPath("overhaul","core/weather_instanced");
    public static final VertexFormat CORNERS=InstancedPipelineVariants.CORNERS;
    public static final VertexFormat INSTANCES=VertexFormat.builder(1)
            .addAttribute("InstanceHorizontal",GpuFormat.RGBA32_FLOAT)
            .addAttribute("InstanceVertical",GpuFormat.RG32_FLOAT)
            .addAttribute("InstanceUv",GpuFormat.RGBA32_FLOAT)
            .addAttribute("InstanceColor",GpuFormat.R32_UINT)
            .addAttribute("InstanceLight",GpuFormat.R32_UINT).build();
    private final InstancedPipelineVariants variants=new InstancedPipelineVariants(VERTEX_SHADER,"weather_instanced",CORNERS,INSTANCES);
    public RenderPipeline variant(RenderPipeline original){return variants.variant(original);}
}
