package dev.overhaul.minecraft.particle;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.minecraft.resources.Identifier;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

/** Renderer-owned immutable pipeline variants retain the original fragment and complete render state. */
public final class InstancedParticlePipelines {
    public static final int INSTANCE_STRIDE=56;
    public static final Identifier VERTEX_SHADER=Identifier.fromNamespaceAndPath("overhaul","core/particle_instanced");
    public static final VertexFormat CORNERS=VertexFormat.builder(0).addAttribute("Corner",GpuFormat.RG32_FLOAT).build();
    public static final VertexFormat INSTANCES=VertexFormat.builder(1)
            .addAttribute("InstanceCenter",GpuFormat.RGB32_FLOAT)
            .addAttribute("InstanceRotation",GpuFormat.RGBA32_FLOAT)
            .addAttribute("InstanceSize",GpuFormat.R32_FLOAT)
            .addAttribute("InstanceUv",GpuFormat.RGBA32_FLOAT)
            .addAttribute("InstanceColor",GpuFormat.R32_UINT)
            .addAttribute("InstanceLight",GpuFormat.R32_UINT).build();
    private final Map<RenderPipeline,RenderPipeline> variants=new IdentityHashMap<>();

    public RenderPipeline variant(RenderPipeline original){return variants.computeIfAbsent(Objects.requireNonNull(original),Variant::new);}
    private static Map<ShaderType,Identifier> shaders(RenderPipeline original){
        Map<ShaderType,Identifier> result=new EnumMap<>(ShaderType.class);result.putAll(original.getShaders());result.put(ShaderType.VERTEX,VERTEX_SHADER);return result;
    }
    /** Protected constructor preserves arbitrary string-valued shader defines without reparsing them. */
    private static final class Variant extends RenderPipeline {
        private Variant(RenderPipeline original){
            super(Identifier.fromNamespaceAndPath("overhaul","particle_instanced/"+original.getLocation().getNamespace()+"/"+original.getLocation().getPath()),
                    shaders(original),original.getShaderDefines(),original.getBindGroupLayouts(),original.getColorTargetStates().toArray(new ColorTargetState[0]),
                    original.getDepthStencilState(),original.getPolygonMode(),original.isCull(),new VertexFormat[]{CORNERS,INSTANCES},
                    PrimitiveTopology.TRIANGLES,original.pushConstantSize(),original.getSortKey());
        }
    }
}
