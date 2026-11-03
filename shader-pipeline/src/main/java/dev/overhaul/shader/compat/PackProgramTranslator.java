package dev.overhaul.shader.compat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Explicit GLSL120 compatibility subset: attributes, scalar/vector/matrix uniforms,
 * sampler2D, varyings and gl_FragColor/gl_FragData. Unsupported fixed-function state
 * fails rather than producing a shader with guessed Minecraft semantics.
 */
public final class PackProgramTranslator {
    private static final String BODY_MARKER="\n// OVERHAUL_COMPATIBILITY_BODY\n";
    private static final Pattern DECLARATION = Pattern.compile("\\b(uniform|attribute|varying)\\s+(\\w+)\\s+(\\w+)\\s*;");
    private static final Pattern BUILTIN = Pattern.compile("\\bgl_\\w+\\b");
    private static final Pattern FRAG_DATA = Pattern.compile("gl_FragData\\s*\\[\\s*([0-7])\\s*]");
    private static final Set<String> BUILTINS = Set.of("gl_Position", "gl_FragCoord", "gl_FrontFacing",
            "gl_FragDepth", "gl_VertexID", "gl_InstanceID", "gl_PointCoord", "gl_PointSize",
            "gl_FragColor", "gl_FragData");
    private static final Map<String, Integer> SIZES = Map.ofEntries(
            Map.entry("float",4), Map.entry("int",4), Map.entry("bool",4),
            Map.entry("vec2",8), Map.entry("vec3",12), Map.entry("vec4",16),
            Map.entry("ivec2",8), Map.entry("ivec3",12), Map.entry("ivec4",16),
            Map.entry("mat2",32), Map.entry("mat3",48), Map.entry("mat4",64));
    private static final Map<String,String> TEXTURE_FUNCTIONS = Map.ofEntries(
            Map.entry("texture2D","texture"),Map.entry("texture2DProj","textureProj"),
            Map.entry("texture2DLod","textureLod"),Map.entry("texture2DProjLod","textureProjLod"),
            Map.entry("texture2DLodARB","textureLod"),Map.entry("texture2DProjLodARB","textureProjLod"),
            Map.entry("texture2DGradARB","textureGrad"),Map.entry("texture2DProjGradARB","textureProjGrad"));

    public TranslatedProgram translate(String vertex, String fragment) {
        return translate(vertex,fragment,CoordinateConvention.NATIVE_VULKAN);
    }
    /** Adapter declarations follow the generated descriptor/interface ABI and precede every original helper. */
    public static String insertBodyPreamble(String translated,String declarations){
        int offset=translated.indexOf(BODY_MARKER);
        if(offset<0||offset!=translated.lastIndexOf(BODY_MARKER)||declarations.contains(BODY_MARKER))throw new IllegalArgumentException("Expected one authoritative translated shader body");
        return translated.substring(0,offset)+"\n"+declarations+translated.substring(offset);
    }

    public TranslatedProgram translate(String vertex, String fragment, CoordinateConvention coordinates) {
        String vs = stripComments(vertex), fs = stripComments(fragment);
        // These legacy texture operations are lowered to core Vulkan GLSL below.
        String textureExtension="(?m)^\\s*#extension\\s+GL_ARB_shader_texture_lod\\s*:\\s*(?:enable|require|warn|disable)\\s*$";
        vs=vs.replaceAll(textureExtension,"");fs=fs.replaceAll(textureExtension,"");
        if (coordinates == null) throw new NullPointerException("coordinates");
        rejectUnprocessedDirectives(vs); rejectUnprocessedDirectives(fs);
        if (coordinates != CoordinateConvention.NATIVE_VULKAN) {
            boolean gameFramebuffer=coordinates==CoordinateConvention.OPENGL_LEGACY;
            boolean reversed=coordinates==CoordinateConvention.OPENGL_REVERSED_DEPTH_TARGET;
            vs = LegacyShaderContract.lower(vs,true,gameFramebuffer,reversed); fs = LegacyShaderContract.lower(fs,false,gameFramebuffer,reversed);
        }
        validateBuiltins(vs); validateBuiltins(fs);
        Map<String,String> uniforms = new TreeMap<>(), samplers = new TreeMap<>();
        Map<String,String> attributes = new TreeMap<>(), vertexVaryings = new TreeMap<>(), fragmentVaryings = new TreeMap<>();
        scan(vs, true, uniforms, samplers, attributes, vertexVaryings);
        scan(fs, false, uniforms, samplers, attributes, fragmentVaryings);
        for (Map.Entry<String,String> entry : fragmentVaryings.entrySet()) {
            if (!entry.getValue().equals(vertexVaryings.get(entry.getKey()))) {
                throw unsupported("Fragment input has no matching vertex varying: " + entry.getKey());
            }
        }
        Map<String,Integer> attributeLocations = locations(attributes), varyingLocations = locations(vertexVaryings);
        Map<String,Integer> samplerBindings = new LinkedHashMap<>();
        for (String name : samplers.keySet()) samplerBindings.put(name, samplerBindings.size()+1);
        Map<String,ProgramBindings.Uniform> layout = new LinkedHashMap<>();
        int offset = 0;
        for (Map.Entry<String,String> uniform : uniforms.entrySet()) {
            String type = uniform.getValue();
            int size = SIZES.get(type), alignment = alignment(type);
            offset = align(offset, alignment);
            layout.put(uniform.getKey(), new ProgramBindings.Uniform(type, offset, size));
            offset += size;
        }
        ProgramBindings bindings = new ProgramBindings(layout, align(offset,16), samplerBindings, attributeLocations, varyingLocations,attributes);
        return new TranslatedProgram(rewrite(vs,true,uniforms,samplers,attributes,vertexVaryings,bindings),
                rewrite(fs,false,uniforms,samplers,attributes,fragmentVaryings,bindings),bindings,coordinates);
    }

    private static void rejectUnprocessedDirectives(String source) {
        if (Pattern.compile("(?m)^\\s*#(?:if|ifdef|ifndef|elif|else|endif|define|undef|include)\\b").matcher(source).find()) {
            throw unsupported("Preprocess conditional and macro directives before compatibility translation");
        }
    }

    private static String stripComments(String source) {
        if (source == null) throw new NullPointerException("shader source");
        return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", "");
    }

    private static void validateBuiltins(String source) {
        Matcher matcher = BUILTIN.matcher(source);
        while (matcher.find()) if (!BUILTINS.contains(matcher.group())) {
            throw unsupported("Unsupported fixed-function or stage builtin: " + matcher.group());
        }
        if (Pattern.compile("\\bftransform\\s*\\(").matcher(source).find()) {
            throw unsupported("ftransform requires an explicit vertex attribute and model-view-projection uniform");
        }
        if (source.contains("#extension")) throw unsupported("Pack extensions require an explicit compatibility implementation");
    }

    private static void scan(String source, boolean vertex, Map<String,String> uniforms,
                             Map<String,String> samplers, Map<String,String> attributes, Map<String,String> varyings) {
        Matcher matcher = DECLARATION.matcher(source);
        while (matcher.find()) {
            String kind = matcher.group(1), type = matcher.group(2), name = matcher.group(3);
            if (kind.equals("uniform")) {
                if (type.equals("sampler2D")) put(samplers,name,type);
                else if (SIZES.containsKey(type)) put(uniforms,name,type);
                else throw unsupported("Unsupported uniform type: " + type);
                if (samplers.containsKey(name) && uniforms.containsKey(name)) throw unsupported("Conflicting uniform: " + name);
            } else {
                if (!Set.of("float","vec2","vec3","vec4").contains(type)) throw unsupported("Unsupported interface type: " + type);
                if (kind.equals("attribute")) {
                    if (!vertex) throw unsupported("Attributes are vertex-stage inputs");
                    put(attributes,name,type);
                } else put(varyings,name,type);
            }
        }
        String remaining = DECLARATION.matcher(source).replaceAll("");
        if (Pattern.compile("\\b(uniform|attribute|varying)\\b").matcher(remaining).find()) {
            throw unsupported("Unsupported declaration syntax: arrays, blocks and multi-declarations need explicit lowering");
        }
    }

    private static void put(Map<String,String> values, String name, String type) {
        String prior = values.putIfAbsent(name,type);
        if (prior != null && !prior.equals(type)) throw unsupported("Conflicting type for " + name);
    }

    private static Map<String,Integer> locations(Map<String,String> values) {
        Map<String,Integer> result = new LinkedHashMap<>();
        for (String name : values.keySet()) result.put(name,result.size());
        return result;
    }

    private static int alignment(String type) {
        if (type.startsWith("mat") || type.endsWith("3") || type.endsWith("4")) return 16;
        return type.endsWith("2") ? 8 : 4;
    }
    private static int align(int value, int alignment) { return (value+alignment-1)/alignment*alignment; }

    private static String rewrite(String source, boolean vertex, Map<String,String> uniforms,
                                  Map<String,String> samplers, Map<String,String> attributes,
                                  Map<String,String> varyings, ProgramBindings bindings) {
        String body = DECLARATION.matcher(source).replaceAll("").replaceAll("(?m)^\\s*#version[^\\r\\n]*", "");
        // Legacy packs commonly name a sampler "texture", now also a GLSL function.
        for (String sampler : samplers.keySet()) {
            body = body.replaceAll("\\b" + Pattern.quote(sampler) + "\\b", "overhaulSampler_" + sampler);
        }
        for(var function:TEXTURE_FUNCTIONS.entrySet())body=body.replaceAll("\\b"+function.getKey()+"\\b",function.getValue());
        body = body.replaceAll("\\bgl_VertexID\\b", "gl_VertexIndex")
                .replaceAll("\\bgl_InstanceID\\b", "gl_InstanceIndex");
        StringBuilder prefix = new StringBuilder("#version 450\n");
        if (!uniforms.isEmpty()) {
            prefix.append("layout(set=0,binding=0,std140) uniform OverhaulPackUniforms {\n");
            for (Map.Entry<String,String> uniform : uniforms.entrySet()) {
                prefix.append("layout(offset=").append(bindings.uniforms().get(uniform.getKey()).byteOffset())
                        .append(") ").append(uniform.getValue()).append(' ').append(uniform.getKey()).append(";\n");
            }
            prefix.append("};\n");
        }
        for (String sampler : samplers.keySet()) prefix.append("layout(set=0,binding=")
                .append(bindings.samplers().get(sampler)).append(") uniform sampler2D overhaulSampler_").append(sampler).append(";\n");
        if (vertex) for (Map.Entry<String,String> attribute : attributes.entrySet()) prefix.append("layout(location=")
                .append(bindings.attributes().get(attribute.getKey())).append(") in ").append(attribute.getValue())
                .append(' ').append(attribute.getKey()).append(";\n");
        for (Map.Entry<String,String> varying : varyings.entrySet()) prefix.append("layout(location=")
                .append(bindings.varyings().get(varying.getKey())).append(vertex ? ") out " : ") in ")
                .append(varying.getValue()).append(' ').append(varying.getKey()).append(";\n");
        if (!vertex) {
            Set<Integer> outputs = new TreeSet<>();
            if (body.contains("gl_FragColor")) { outputs.add(0); body = body.replace("gl_FragColor","overhaulColor0"); }
            Matcher matcher = FRAG_DATA.matcher(body);
            StringBuffer replaced = new StringBuffer();
            while (matcher.find()) {
                int location = Integer.parseInt(matcher.group(1)); outputs.add(location);
                matcher.appendReplacement(replaced,"overhaulColor"+location);
            }
            matcher.appendTail(replaced); body = replaced.toString();
            if (body.contains("gl_FragData")) throw unsupported("Fragment output indices must be constant in [0,7]");
            for (int location : outputs) prefix.append("layout(location=").append(location)
                    .append(") out vec4 overhaulColor").append(location).append(";\n");
        }
        return prefix.append(BODY_MARKER).append(body).toString();
    }

    private static UnsupportedShaderException unsupported(String message) { return new UnsupportedShaderException(message); }
    public record TranslatedProgram(String vertex, String fragment, ProgramBindings bindings, CoordinateConvention coordinates) {}
}
