package dev.overhaul.shader.compat;

import java.util.Map;

/** Descriptor set zero: binding zero is a std140 UBO, samplers start at one and storage buffers follow them. */
public record ProgramBindings(Map<String, Uniform> uniforms, int uniformBytes,
                              Map<String, Integer> samplers, Map<String, Integer> attributes,
                              Map<String, Integer> varyings, Map<String,String> attributeTypes,
                              Map<String,String> samplerTypes) {
    public ProgramBindings(Map<String,Uniform> uniforms,int uniformBytes,Map<String,Integer> samplers,
                           Map<String,Integer> attributes,Map<String,Integer> varyings,Map<String,String> attributeTypes){
        this(uniforms,uniformBytes,samplers,attributes,varyings,attributeTypes,ordinarySamplers(samplers));
    }
    public ProgramBindings {
        uniforms = interned(uniforms, uniform -> new Uniform(uniform.type().intern(), uniform.byteOffset(), uniform.byteSize()));
        samplers = interned(samplers, value -> value);
        attributes = Map.copyOf(attributes); varyings = Map.copyOf(varyings);
        attributeTypes = Map.copyOf(attributeTypes);
        samplerTypes = interned(samplerTypes, String::intern);
        if(!samplerTypes.keySet().equals(samplers.keySet()))throw new IllegalArgumentException("Every sampler requires its declared type");
        for(String type:samplerTypes.values())if(!TYPES.contains(type))throw new IllegalArgumentException("Unsupported sampler type: "+type);
    }
    private static final java.util.Set<String> TYPES=java.util.Set.of("sampler2D","sampler2DShadow","sampler3D","usampler2D","isampler2D","usampler3D","isampler3D",
            "image2D","image3D","uimage2D","uimage3D","iimage2D","iimage3D",ProgramBindings.BUFFER);
    /** The type of a storage buffer binding, named by the pack's {@code bufferObject} index. */
    public static final String BUFFER="buffer";
    private static final String BUFFER_PREFIX="overhaulBuffer";
    /** Bound as a storage image, written with imageStore, rather than through a sampler. */
    public boolean storageImage(String name){return type(name).contains("image");}
    /** Bound as a storage buffer: one of the pack's {@code bufferObject} buffers. */
    public boolean storageBuffer(String name){return type(name).equals(BUFFER);}
    /** The {@code bufferObject} index a storage buffer binding names. */
    public int bufferIndex(String name){if(!storageBuffer(name))throw new IllegalArgumentException("Not a storage buffer: "+name);return Integer.parseInt(name.substring(BUFFER_PREFIX.length()));}
    /** A 3D sampler or image, bound to a 3D view. */
    public boolean volume(String name){return type(name).endsWith("3D");}
    /** True when any binding is a storage image, which needs the storage descriptor layout. */
    public boolean hasStorageImages(){return samplerTypes.values().stream().anyMatch(type->type.contains("image"));}
    public boolean hasStorageBuffers(){return samplerTypes.containsValue(BUFFER);}
    private String type(String name){String type=samplerTypes.get(name);if(type==null)throw new IllegalArgumentException("Unknown sampler: "+name);return type;}
    public boolean comparisonSampler(String name){String type=samplerTypes.get(name);if(type==null)throw new IllegalArgumentException("Unknown sampler: "+name);return type.equals("sampler2DShadow");}
    private static Map<String,String> ordinarySamplers(Map<String,Integer> samplers){Map<String,String> result=new java.util.LinkedHashMap<>();for(String name:samplers.keySet())result.put(name,"sampler2D");return result;}
    /**
     * Names and types are looked up every frame with string literals as keys; interned, each key comparison is an
     * identity check instead of a character compare.
     */
    private static <V> Map<String,V> interned(Map<String,V> source, java.util.function.UnaryOperator<V> value){
        Map<String,V> result=new java.util.HashMap<>(source.size()*2);
        source.forEach((name,entry)->result.put(name.intern(),value.apply(entry)));
        return Map.copyOf(result);
    }
    public record Uniform(String type, int byteOffset, int byteSize) {}
}
