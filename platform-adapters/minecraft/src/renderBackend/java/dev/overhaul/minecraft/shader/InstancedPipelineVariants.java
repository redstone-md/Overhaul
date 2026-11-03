package dev.overhaul.minecraft.shader;

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

/** Renderer-owned vertex variants preserve every original fragment, descriptor and attachment contract. */
public final class InstancedPipelineVariants {
    public static final VertexFormat CORNERS=VertexFormat.builder(0).addAttribute("Corner",GpuFormat.RG32_FLOAT).build();
    private final Identifier vertex;
    private final String prefix;
    private final VertexFormat[] bindings;
    private final Map<RenderPipeline,RenderPipeline> variants=new IdentityHashMap<>();

    public InstancedPipelineVariants(Identifier vertex,String prefix,VertexFormat... bindings){
        this.vertex=Objects.requireNonNull(vertex);this.prefix=Objects.requireNonNull(prefix);this.bindings=bindings.clone();
        if(bindings.length==0)throw new IllegalArgumentException("Instanced vertex bindings are required");
        for(VertexFormat format:this.bindings)Objects.requireNonNull(format);
    }
    public RenderPipeline variant(RenderPipeline original){return variants.computeIfAbsent(Objects.requireNonNull(original),this::create);}
    private RenderPipeline create(RenderPipeline original){
        Identifier location=Identifier.fromNamespaceAndPath(vertex.getNamespace(),prefix+"/"+original.getLocation().getNamespace()+"/"+original.getLocation().getPath());
        Map<ShaderType,Identifier> shaders=new EnumMap<>(ShaderType.class);shaders.putAll(original.getShaders());shaders.put(ShaderType.VERTEX,vertex);
        return new Variant(location,original,shaders,bindings.clone());
    }
    /** Direct construction retains arbitrary string-valued shader definitions without reparsing. */
    private static final class Variant extends RenderPipeline {
        private Variant(Identifier location,RenderPipeline original,Map<ShaderType,Identifier> shaders,VertexFormat[] bindings){
            super(location,shaders,original.getShaderDefines(),original.getBindGroupLayouts(),original.getColorTargetStates().toArray(new ColorTargetState[0]),
                    original.getDepthStencilState(),original.getPolygonMode(),original.isCull(),bindings,
                    PrimitiveTopology.TRIANGLES,original.pushConstantSize(),original.getSortKey());
        }
    }
}
