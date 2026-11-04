package dev.overhaul.minecraft.backend;

import dev.overhaul.shader.compat.ProgramBindings;
import dev.overhaul.shader.compat.UnsupportedShaderException;
import dev.overhaul.shader.pack.CompiledShaderPack;
import dev.overhaul.vulkan.NativeGraphicsPipeline;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Actual copied vertex formats drive native input bindings independently of the original shader's live inputs. */
abstract class SceneVertexInputs {
    record Sources(String vertex,String fragment){}
    record Decoder(String vertexPreamble,String fragmentPreamble,String initialize,boolean quadNormals){}
    record Storage(int slot,int strideWords,int positionWords){}
    final OverhaulPipeline original;
    final PipelineCompileMetadata metadata;
    final boolean shadow;
    private final Map<Integer,NativeGraphicsPipeline.Binding> bindings=new LinkedHashMap<>();
    private final List<NativeGraphicsPipeline.Attribute> attributes=new ArrayList<>();
    private List<NativeGraphicsPipeline.Binding> compiledBindings;
    private List<NativeGraphicsPipeline.Attribute> compiledAttributes;
    private Storage storage;
    SceneVertexInputs(OverhaulPipeline original,boolean shadow){this.original=original;this.shadow=shadow;metadata=original.compileMetadata();if(metadata==null)throw new UnsupportedShaderException("Scene pipeline lacks copied source/input metadata");if(shadow)metadata.requireSource();else metadata.requireScene();}
    static SceneVertexInputs create(OverhaulPipeline original,boolean shadow){return original.compileMetadata().family()==PipelineCompileMetadata.Family.TERRAIN?new TerrainSceneInputs(original,shadow):new EntitySceneInputs(original,shadow);}
    Sources sources(CompiledShaderPack.Sources sources){return new Sources(sources.vertex(),sources.fragment());}
    String fragment(String source){return source;}
    abstract Decoder decode(ProgramBindings bindings,int storageBinding,boolean activeNormal);
    final boolean hasField(String name){for(var input:metadata.inputs())if(input.fields().containsKey(name))return true;return false;}
    final String input(String type,String name,int location){return input(type,name,location,"overhaulInput"+name);}
    final String input(String type,String semantic,int location,String variable){bind(semantic,location);return "layout(location="+location+") in "+type+" "+variable+";\n";}
    final void bind(String name,int location){
        if(compiledBindings!=null||compiledAttributes!=null)throw new IllegalStateException("Scene input layout is already published");
        var source=metadata.field(name);var found=source.input();var field=source.field();
        bindings.putIfAbsent(found.slot(),new NativeGraphicsPipeline.Binding(found.slot(),found.stride(),found.stepRate()));
        attributes.add(new NativeGraphicsPipeline.Attribute(location,found.slot(),field.format(),field.offset()));
    }
    final Storage quadStorage(){
        var position=metadata.field("Position");
        if(original.info().primitiveTopology()!=com.mojang.renderpearl.api.pipeline.PrimitiveTopology.QUADS||position.input().stepRate()!=0)
            throw new UnsupportedShaderException("Geometric normals require an actual per-vertex QUADS source profile");
        if(position.field().format()!=org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32B32_SFLOAT||position.input().stride()%4!=0||position.field().offset()%4!=0)
            throw new UnsupportedShaderException("Quad normal reconstruction requires aligned float positions");
        storage=new Storage(position.input().slot(),position.input().stride()/4,position.field().offset()/4);return storage;
    }
    final Storage storage(){return storage;}
    final String normalInput(int location){
        int format=metadata.field("Normal").field().format();
        if(format!=org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8_SNORM&&format!=org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_SNORM
                &&format!=org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16_SFLOAT&&format!=org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT
                &&format!=org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32B32_SFLOAT&&format!=org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32B32A32_SFLOAT)
            throw new UnsupportedShaderException("Named normal input must contain decoded signed-normalized or floating-point components");
        return input("vec3","Normal",location);
    }
    final void bindKernel(String source){
        var matcher=Pattern.compile("layout\\s*\\(\\s*location\\s*=\\s*(\\d+)\\s*\\)\\s+in\\s+\\w+\\s+(\\w+)\\s*;").matcher(source);
        while(matcher.find())bind(matcher.group(2),Integer.parseInt(matcher.group(1)));
    }
    final List<NativeGraphicsPipeline.Binding> bindings(){if(compiledBindings==null)compiledBindings=List.copyOf(bindings.values());return compiledBindings;}
    final List<NativeGraphicsPipeline.Attribute> attributes(){if(compiledAttributes==null)compiledAttributes=List.copyOf(attributes);return compiledAttributes;}
    static int slot(OverhaulPipeline pipeline,String name){for(int slot=0;slot<pipeline.uniforms().size();slot++)if(pipeline.uniforms().get(slot).name().equals(name))return slot;throw new UnsupportedShaderException("Scene uniform binding is absent: "+name);}
    static String assign(ProgramBindings bindings,Map<String,String> values){
        StringBuilder result=new StringBuilder();for(String attribute:bindings.attributes().keySet()){
            if(dev.overhaul.shader.pack.PackVertexDefaults.known(attribute))continue;
            String value=values.get(attribute);if(value==null)throw new UnsupportedShaderException("Unsupported scene vertex attribute: "+attribute);
            result.append(attribute).append('=').append(value).append(";\n");
        }return result.toString();
    }
}
