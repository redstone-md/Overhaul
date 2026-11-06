package dev.overhaul.shader.compile;

import dev.overhaul.shader.compat.ProgramBindings;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Reads surviving SPIR-V interface variables after optimization, retaining compiler-assigned binding numbers. */
public final class SpirvInterface {
    private final Set<Integer> descriptors=new HashSet<>(),inputs=new HashSet<>(),outputs=new HashSet<>();
    private SpirvInterface(SpirvBinary binary,Set<Integer> importedSets){
        ByteBuffer words=ByteBuffer.wrap(binary.bytes()).order(ByteOrder.LITTLE_ENDIAN);
        if(words.remaining()<20||words.remaining()%4!=0)throw invalid();
        if(words.getInt(0)==0x03022307)words.order(ByteOrder.BIG_ENDIAN);
        if(words.getInt(0)!=0x07230203)throw invalid();
        Map<Integer,Integer> bindings=new HashMap<>(),sets=new HashMap<>(),locations=new HashMap<>(),variables=new HashMap<>();
        for(int word=5;word<words.remaining()/4;){
            int instruction=words.getInt(word*4),count=instruction>>>16,opcode=instruction&65535;
            if(count<1||count>words.remaining()/4-word)throw invalid();
            if(opcode==71&&count>=4){
                int id=words.getInt((word+1)*4),decoration=words.getInt((word+2)*4),value=words.getInt((word+3)*4);
                if(decoration==33)bindings.put(id,value);else if(decoration==34)sets.put(id,value);else if(decoration==30)locations.put(id,value);
            }else if(opcode==59&&count>=4)variables.put(words.getInt((word+2)*4),words.getInt((word+3)*4));
            word+=count;
        }
        for(var variable:variables.entrySet()){
            int id=variable.getKey(),storage=variable.getValue();
            if(bindings.containsKey(id)){
                int set=sets.getOrDefault(id,-1);
                if(set!=0&&!importedSets.contains(set))throw new ShaderCompilationException("Pack descriptor escaped declared descriptor sets");
                if(set==0)descriptors.add(bindings.get(id));
            }
            if(storage==1&&locations.containsKey(id))inputs.add(locations.get(id));
            if(storage==3&&locations.containsKey(id))outputs.add(locations.get(id));
        }
    }
    public static Set<Integer> inputLocations(SpirvBinary binary){return Set.copyOf(new SpirvInterface(binary,Set.of()).inputs);}
    public static Set<Integer> outputLocations(SpirvBinary binary){return Set.copyOf(new SpirvInterface(binary,Set.of()).outputs);}
    public static ProgramBindings active(ProgramBindings declared,SpirvBinary vertex,SpirvBinary fragment){
        return active(declared,vertex,fragment,Set.of());
    }
    public static ProgramBindings active(ProgramBindings declared,SpirvBinary vertex,SpirvBinary fragment,Set<Integer> importedSets){
        SpirvInterface vs=new SpirvInterface(vertex,importedSets),fs=new SpirvInterface(fragment,importedSets);Set<Integer> descriptors=new HashSet<>(vs.descriptors);descriptors.addAll(fs.descriptors);
        Map<String,Integer> samplers=new LinkedHashMap<>(),attributes=new LinkedHashMap<>();Map<String,String> types=new LinkedHashMap<>(),samplerTypes=new LinkedHashMap<>();
        for(var sampler:declared.samplers().entrySet())if(descriptors.contains(sampler.getValue())){samplers.put(sampler.getKey(),sampler.getValue());samplerTypes.put(sampler.getKey(),declared.samplerTypes().get(sampler.getKey()));}
        for(var attribute:declared.attributes().entrySet())if(vs.inputs.contains(attribute.getValue())){
            attributes.put(attribute.getKey(),attribute.getValue());types.put(attribute.getKey(),declared.attributeTypes().get(attribute.getKey()));
        }
        boolean block=descriptors.contains(0);
        return new ProgramBindings(block?declared.uniforms():Map.of(),block?declared.uniformBytes():0,samplers,attributes,declared.varyings(),types,samplerTypes);
    }
    /** A compute program has one stage and no interface, so only its descriptors filter the declared bindings. */
    public static ProgramBindings activeCompute(ProgramBindings declared,SpirvBinary compute){
        Set<Integer> descriptors=new SpirvInterface(compute,Set.of()).descriptors;
        Map<String,Integer> samplers=new LinkedHashMap<>();Map<String,String> samplerTypes=new LinkedHashMap<>();
        for(var sampler:declared.samplers().entrySet())if(descriptors.contains(sampler.getValue())){samplers.put(sampler.getKey(),sampler.getValue());samplerTypes.put(sampler.getKey(),declared.samplerTypes().get(sampler.getKey()));}
        boolean block=descriptors.contains(0);
        return new ProgramBindings(block?declared.uniforms():Map.of(),block?declared.uniformBytes():0,samplers,Map.of(),Map.of(),Map.of(),samplerTypes);
    }
    private static ShaderCompilationException invalid(){return new ShaderCompilationException("Malformed compiled SPIR-V interface");}
}
