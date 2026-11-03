package dev.overhaul.minecraft.backend;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import dev.overhaul.minecraft.model.ModelVertexShaderSource;
import dev.overhaul.minecraft.shader.SceneDomainPipeline;
import dev.overhaul.minecraft.shader.SceneDrawDomain;
import dev.overhaul.shader.pack.ShaderPackProperties;
import dev.overhaul.shader.compat.UnsupportedShaderException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable source identity, input ABI and material state copied before frontend schema lowering. */
record PipelineCompileMetadata(String name,String vertex,String fragment,Family family,boolean modelInstances,
                               Map<String,String> defines,Set<String> flags,List<Input> inputs,
                               SceneDrawDomain domain,String domainError) {
    enum Family { TERRAIN,ENTITY,BLOCK }
    record Field(int format,int offset){}
    record BoundField(Input input,Field field){}
    record Input(int slot,int stride,int stepRate,Map<String,Field> fields){Input{fields=Map.copyOf(fields);}}
    PipelineCompileMetadata{defines=Map.copyOf(defines);flags=Set.copyOf(flags);inputs=List.copyOf(inputs);}
    static PipelineCompileMetadata capture(RenderPipeline pipeline){
        var vertex=pipeline.getShaders().get(ShaderType.VERTEX);var fragment=pipeline.getShaders().get(ShaderType.FRAGMENT);
        if(vertex==null||fragment==null)return null;
        var original=vertex.getNamespace().equals("overhaul")?ModelVertexShaderSource.originalVertex(vertex):null;boolean model=original!=null;if(original==null)original=vertex;
        if(!original.getNamespace().equals("minecraft")||!fragment.getNamespace().equals("minecraft"))return null;
        Family family=switch(original.getPath()){
            case "core/terrain"->fragment.getPath().equals("core/terrain")?Family.TERRAIN:null;
            case "core/entity"->fragment.getPath().equals("core/entity")?Family.ENTITY:null;
            case "core/block"->fragment.getPath().equals("core/block")?Family.BLOCK:null;
            default->null;
        };
        if(family==null)return null;
        var definitions=pipeline.getShaderDefines();List<Input> inputs=new ArrayList<>();var bindings=pipeline.getVertexFormatBindings();
        for(int slot=0;slot<bindings.size();slot++){
            var format=bindings.get(slot);if(format==null)continue;Map<String,Field> fields=new LinkedHashMap<>();
            for(var element:format.getElements())fields.put(element.name(),new Field(OverhaulFormats.format(element.format()),element.offset()));
            inputs.add(new Input(slot,format.getVertexSize(),format.getStepRate(),fields));
        }
        SceneDrawDomain domain=pipeline instanceof SceneDomainPipeline tagged?tagged.sceneDomain():SceneDrawDomain.UNKNOWN;
        String marker=definitions.values().get(SceneDrawDomain.DEFINE),error=null;
        if(domain==null){domain=SceneDrawDomain.UNKNOWN;error="Null internal scene-domain marker";}
        if(marker!=null&&(!(pipeline instanceof SceneDomainPipeline)||!marker.equals(domain.name()))||domain!=SceneDrawDomain.UNKNOWN&&marker==null||definitions.flags().contains(SceneDrawDomain.DEFINE))
            error="Untrusted or inconsistent internal scene-domain marker";
        return new PipelineCompileMetadata(pipeline.getLocation().toString(),vertex.toString(),fragment.toString(),family,model,definitions.values(),definitions.flags(),inputs,domain,error);
    }
    boolean defined(String name){return defines.containsKey(name)||flags.contains(name);}
    void requireScene(){
        if(domainError!=null)throw new UnsupportedShaderException(domainError);
        if(family==Family.ENTITY&&defined("GLINT"))throw new UnsupportedShaderException("Combined entity foil requires an explicit shader-pack armor-glint material route");
    }
    Input input(int slot){for(Input input:inputs)if(input.slot()==slot)return input;throw new UnsupportedShaderException("Missing scene vertex binding: "+slot);}
    BoundField field(String name){
        BoundField found=null;for(Input input:inputs)if(input.fields().containsKey(name)){
            if(found!=null)throw new UnsupportedShaderException("Ambiguous scene vertex semantic: "+name);found=new BoundField(input,input.fields().get(name));
        }
        if(found==null)throw new UnsupportedShaderException("Missing actual scene vertex semantic: "+name);return found;
    }
    ShaderPackProperties.AlphaTest inheritedAlphaTest(){
        String literal=defines.get("ALPHA_CUTOUT");
        if(literal==null){if(flags.contains("ALPHA_CUTOUT"))throw new UnsupportedShaderException("Scene ALPHA_CUTOUT requires its actual numeric threshold");return null;}
        float threshold;
        try{threshold=Float.parseFloat(literal);}catch(NumberFormatException invalid){throw new UnsupportedShaderException("Unsupported scene ALPHA_CUTOUT expression: "+literal);}
        if(!Float.isFinite(threshold)||threshold<0||threshold>1)throw new UnsupportedShaderException("Scene ALPHA_CUTOUT threshold must be finite and within [0,1]");
        return new ShaderPackProperties.AlphaTest(true,"GEQUAL",threshold);
    }
}
