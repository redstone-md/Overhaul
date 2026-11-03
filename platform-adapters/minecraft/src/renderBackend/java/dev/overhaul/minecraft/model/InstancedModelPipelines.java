package dev.overhaul.minecraft.model;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import dev.overhaul.minecraft.shader.InstancedPipelineVariants;
import dev.overhaul.minecraft.shader.SceneDrawDomain;
import net.minecraft.resources.Identifier;
import net.minecraft.client.renderer.oit.OitPipelineSet;
import net.minecraft.client.renderer.oit.OitStage;
import java.util.HashMap;
import java.util.EnumMap;
import java.util.Map;

/** Quad-instance ABI shared by the certified original entity, glint, outline and crumbling programs. */
public final class InstancedModelPipelines {
    public static final int INSTANCE_STRIDE=124;
    public static final VertexFormat CORNERS=InstancedPipelineVariants.CORNERS;
    public static final VertexFormat INSTANCES=VertexFormat.builder(1)
            .addAttribute("InstanceRow0",GpuFormat.RGBA32_FLOAT).addAttribute("InstanceRow1",GpuFormat.RGBA32_FLOAT).addAttribute("InstanceRow2",GpuFormat.RGBA32_FLOAT)
            .addAttribute("InstanceMin",GpuFormat.RGB32_FLOAT).addAttribute("InstanceMax",GpuFormat.RGB32_FLOAT)
            .addAttribute("InstanceUv01",GpuFormat.RGBA32_FLOAT).addAttribute("InstanceUv23",GpuFormat.RGBA32_FLOAT)
            .addAttribute("InstanceMaterial",GpuFormat.RGBA32_UINT).addAttribute("InstanceEncoding",GpuFormat.R32_UINT).build();
    private final Map<SceneDrawDomain,Map<Identifier,InstancedPipelineVariants>> variants=new EnumMap<>(SceneDrawDomain.class);
    public static boolean compatible(RenderPipeline original){
        if(original==null||original.getPrimitiveTopology()!=PrimitiveTopology.QUADS)return false;
        // RenderPearl keeps a fixed-size table; unused binding slots are null.
        var bindings=original.getVertexFormatBindings();
        for(int binding=1;binding<bindings.size();binding++)if(bindings.get(binding)!=null)return false;
        Identifier vertex=original.getShaders().get(ShaderType.VERTEX);if(!ModelVertexShaderSource.supports(vertex))return false;
        VertexFormat format=original.getVertexFormatBinding(0);
        return switch(vertex.getPath()){
            case "core/entity"->format==DefaultVertexFormat.ENTITY||format==DefaultVertexFormat.ENTITY_GLINT_SPECIAL;
            case "core/glint"->format==DefaultVertexFormat.POSITION_TEX;
            case "core/rendertype_outline"->format==DefaultVertexFormat.POSITION_TEX_COLOR;
            case "core/rendertype_crumbling"->format==DefaultVertexFormat.BLOCK;
            default->false;
        };
    }
    public RenderPipeline variant(RenderPipeline original){
        return variant(original,SceneDrawDomain.UNKNOWN);
    }
    public RenderPipeline variant(RenderPipeline original,SceneDrawDomain domain){
        if(!compatible(original))throw new UnsupportedOperationException("Unsupported model vertex contract: "+original);
        Identifier vertex=original.getShaders().get(ShaderType.VERTEX);
        return variants.computeIfAbsent(domain,key->new HashMap<>()).computeIfAbsent(vertex,
                key->new InstancedPipelineVariants(ModelVertexShaderSource.adaptedVertex(key),"model_quad_instanced",domain,CORNERS,INSTANCES)).variant(original);
    }
    public static boolean compatibleOit(OitPipelineSet family){
        if(family==null)return true;
        for(var stage:OitStage.values())if(!compatible(family.getPipeline(stage)))return false;
        return true;
    }
}
