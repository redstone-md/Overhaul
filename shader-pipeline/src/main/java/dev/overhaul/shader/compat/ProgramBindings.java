package dev.overhaul.shader.compat;

import java.util.Map;

/** Descriptor set zero: binding zero is a std140 UBO, samplers start at one. */
public record ProgramBindings(Map<String, Uniform> uniforms, int uniformBytes,
                              Map<String, Integer> samplers, Map<String, Integer> attributes,
                              Map<String, Integer> varyings, Map<String,String> attributeTypes,
                              Map<String,String> samplerTypes) {
    public ProgramBindings(Map<String,Uniform> uniforms,int uniformBytes,Map<String,Integer> samplers,
                           Map<String,Integer> attributes,Map<String,Integer> varyings,Map<String,String> attributeTypes){
        this(uniforms,uniformBytes,samplers,attributes,varyings,attributeTypes,ordinarySamplers(samplers));
    }
    public ProgramBindings {
        uniforms = Map.copyOf(uniforms); samplers = Map.copyOf(samplers);
        attributes = Map.copyOf(attributes); varyings = Map.copyOf(varyings);
        attributeTypes = Map.copyOf(attributeTypes);
        samplerTypes=Map.copyOf(samplerTypes);
        if(!samplerTypes.keySet().equals(samplers.keySet()))throw new IllegalArgumentException("Every sampler requires its declared type");
        for(String type:samplerTypes.values())if(!type.equals("sampler2D")&&!type.equals("sampler2DShadow"))throw new IllegalArgumentException("Unsupported sampler type: "+type);
    }
    public boolean comparisonSampler(String name){String type=samplerTypes.get(name);if(type==null)throw new IllegalArgumentException("Unknown sampler: "+name);return type.equals("sampler2DShadow");}
    private static Map<String,String> ordinarySamplers(Map<String,Integer> samplers){Map<String,String> result=new java.util.LinkedHashMap<>();for(String name:samplers.keySet())result.put(name,"sampler2D");return result;}
    public record Uniform(String type, int byteOffset, int byteSize) {}
}
