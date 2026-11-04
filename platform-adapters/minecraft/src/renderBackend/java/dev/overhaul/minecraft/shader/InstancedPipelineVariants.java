package dev.overhaul.minecraft.shader;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.minecraft.resources.Identifier;
import net.minecraft.client.renderer.ShaderDefines;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Renderer-owned vertex variants preserve every original fragment, descriptor and attachment contract. */
public final class InstancedPipelineVariants {
    public static final VertexFormat CORNERS=VertexFormat.builder(0).addAttribute("Corner",GpuFormat.RG32_FLOAT).build();
    private static final int VERTEX_BINDING_SLOTS=16;
    private final Identifier vertex;
    private final String prefix;
    private final VertexFormat[] bindings;
    private final SceneDrawDomain domain;
    private final Map<RenderPipeline,RenderPipeline> variants=new IdentityHashMap<>();

    public InstancedPipelineVariants(Identifier vertex,String prefix,VertexFormat... bindings){
        this(vertex,prefix,SceneDrawDomain.UNKNOWN,bindings);
    }
    public InstancedPipelineVariants(Identifier vertex,String prefix,SceneDrawDomain domain,VertexFormat... bindings){
        this.vertex=Objects.requireNonNull(vertex);this.prefix=Objects.requireNonNull(prefix);
        this.domain=Objects.requireNonNull(domain);
        if(bindings.length==0)throw new IllegalArgumentException("Instanced vertex bindings are required");
        for(VertexFormat format:bindings)Objects.requireNonNull(format);
        // Frontend draw validation reads every slot, so the list keeps RenderPipeline.Builder's fixed size.
        this.bindings=Arrays.copyOf(bindings,VERTEX_BINDING_SLOTS);
    }
    public RenderPipeline variant(RenderPipeline original){return variants.computeIfAbsent(Objects.requireNonNull(original),this::create);}
    private RenderPipeline create(RenderPipeline original){
        SceneDrawDomain effective=effectiveDomain(original,domain);
        String scope=effective==SceneDrawDomain.UNKNOWN?"":effective.name().toLowerCase(Locale.ROOT)+"/";
        Identifier location=Identifier.fromNamespaceAndPath(vertex.getNamespace(),prefix+"/"+scope+original.getLocation().getNamespace()+"/"+original.getLocation().getPath());
        Map<ShaderType,Identifier> shaders=new EnumMap<>(ShaderType.class);shaders.putAll(original.getShaders());shaders.put(ShaderType.VERTEX,vertex);
        return new Variant(location,original,shaders,bindings.clone(),PrimitiveTopology.TRIANGLES,effective);
    }
    private static SceneDrawDomain effectiveDomain(RenderPipeline original,SceneDrawDomain requested){
        String encoded=original.getShaderDefines().values().get(SceneDrawDomain.DEFINE);
        boolean flag=original.getShaderDefines().flags().contains(SceneDrawDomain.DEFINE);
        SceneDrawDomain inherited=original instanceof SceneDomainPipeline tagged?Objects.requireNonNull(tagged.sceneDomain()):SceneDrawDomain.UNKNOWN;
        if(flag||(inherited==SceneDrawDomain.UNKNOWN?encoded!=null:!inherited.name().equals(encoded)))
            throw new IllegalArgumentException("Scene domain definition is reserved for typed owned pipeline variants");
        return requested==SceneDrawDomain.UNKNOWN?inherited:requested;
    }
    private static ShaderDefines defines(RenderPipeline original,SceneDrawDomain domain){
        if(domain==SceneDrawDomain.UNKNOWN)return original.getShaderDefines();
        var values=new HashMap<>(original.getShaderDefines().values());values.put(SceneDrawDomain.DEFINE,domain.name());
        return new ShaderDefines(Map.copyOf(values),original.getShaderDefines().flags());
    }
    /** Plain-layout tags share the exact clone implementation with instanced variants. */
    public static final class Domains {
        private final Map<SceneDrawDomain,Map<RenderPipeline,RenderPipeline>> variants=new EnumMap<>(SceneDrawDomain.class);
        public RenderPipeline tag(RenderPipeline original,SceneDrawDomain domain){
            Objects.requireNonNull(original);Objects.requireNonNull(domain);SceneDrawDomain effective=effectiveDomain(original,domain);
            if(effective==SceneDrawDomain.UNKNOWN)return original;
            if(original instanceof SceneDomainPipeline tagged&&tagged.sceneDomain()==effective)return original;
            return variants.computeIfAbsent(effective,key->new IdentityHashMap<>()).computeIfAbsent(original,key->{
                Identifier id=key.getLocation();Identifier location=Identifier.fromNamespaceAndPath("overhaul","scene_domain/"+effective.name().toLowerCase(Locale.ROOT)+"/"+id.getNamespace()+"/"+id.getPath());
                return new Variant(location,key,key.getShaders(),key.getVertexFormatBindings().toArray(new VertexFormat[0]),key.getPrimitiveTopology(),effective);
            });
        }
    }
    /** Direct construction retains arbitrary string-valued shader definitions without reparsing. */
    private static final class Variant extends RenderPipeline implements SceneDomainPipeline {
        private final SceneDrawDomain domain;
        private Variant(Identifier location,RenderPipeline original,Map<ShaderType,Identifier> shaders,VertexFormat[] bindings,PrimitiveTopology topology,SceneDrawDomain domain){
            super(location,shaders,defines(original,domain),original.getBindGroupLayouts(),original.getColorTargetStates().toArray(new ColorTargetState[0]),
                    original.getDepthStencilState(),original.getPolygonMode(),original.isCull(),bindings,
                    topology,original.pushConstantSize(),original.getSortKey());
            this.domain=domain;
        }
        @Override public SceneDrawDomain sceneDomain(){return domain;}
    }
}
