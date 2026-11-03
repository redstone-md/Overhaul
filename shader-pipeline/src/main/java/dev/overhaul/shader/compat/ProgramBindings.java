package dev.overhaul.shader.compat;

import java.util.Map;

/** Descriptor set zero: binding zero is a std140 UBO, samplers start at one. */
public record ProgramBindings(Map<String, Uniform> uniforms, int uniformBytes,
                              Map<String, Integer> samplers, Map<String, Integer> attributes,
                              Map<String, Integer> varyings, Map<String,String> attributeTypes) {
    public ProgramBindings {
        uniforms = Map.copyOf(uniforms); samplers = Map.copyOf(samplers);
        attributes = Map.copyOf(attributes); varyings = Map.copyOf(varyings);
        attributeTypes = Map.copyOf(attributeTypes);
    }
    public record Uniform(String type, int byteOffset, int byteSize) {}
}
