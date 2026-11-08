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
    private static final Pattern DECLARATION = Pattern.compile("(?:\\b(flat|smooth|noperspective|centroid)\\s+)?\\b(uniform|attribute|varying)\\s+(\\w+)\\s+(\\w+)\\s*;");
    private static final Set<String> ATTRIBUTE_TYPES = Set.of("float","vec2","vec3","vec4");
    private static final Set<String> INTEGER_VARYINGS = Set.of("int","ivec2","ivec3","ivec4","uint","uvec2","uvec3","uvec4");
    private static final Map<String,Integer> VARYING_SLOTS = Map.of("mat2",2,"mat3",3,"mat4",4);
    private static final Pattern BUILTIN = Pattern.compile("\\bgl_\\w+\\b");
    private static final Pattern FRAG_DATA = Pattern.compile("gl_FragData\\s*\\[\\s*([0-7])\\s*]");
    private static final Set<String> BUILTINS = Set.of("gl_Position", "gl_FragCoord", "gl_FrontFacing",
            "gl_FragDepth", "gl_VertexID", "gl_InstanceID", "gl_PointCoord", "gl_PointSize",
            "gl_FragColor", "gl_FragData");
    private static final Set<String> GEOMETRY_BUILTINS = Set.of("gl_in","gl_PrimitiveIDIn","gl_PrimitiveID","gl_InvocationID","gl_Layer","gl_ViewportIndex");
    private static final Set<String> COMPUTE_BUILTINS = Set.of("gl_GlobalInvocationID","gl_LocalInvocationID","gl_WorkGroupID",
            "gl_NumWorkGroups","gl_LocalInvocationIndex","gl_WorkGroupSize");
    /** Sampled types a pack may declare; storage images come from {@link PackStorageImages#IMAGE_TYPES}. */
    private static final Set<String> SAMPLER_TYPES = Set.of("sampler2D","sampler2DShadow","sampler3D","usampler2D","isampler2D","usampler3D","isampler3D",
            "samplerCube","samplerCubeShadow","sampler2DArray","sampler2DArrayShadow","isampler2DArray","usampler2DArray",
            "samplerBuffer","usamplerBuffer","isamplerBuffer");
    private static final Pattern LOCAL_SIZE = Pattern.compile("\\blayout\\s*\\(([^)]*local_size_[xyz][^)]*)\\)\\s*in\\s*;");
    private static final Pattern WORK_GROUPS = Pattern.compile("\\bconst\\s+ivec3\\s+workGroups\\s*=\\s*ivec3\\s*\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)");
    private static final Pattern WORK_GROUPS_RENDER = Pattern.compile("\\bconst\\s+vec2\\s+workGroupsRender\\s*=\\s*vec2\\s*\\(\\s*([\\d.]+)\\s*,\\s*([\\d.]+)\\s*\\)");
    private static final Map<String, Integer> SIZES = Map.ofEntries(
            Map.entry("float",4), Map.entry("int",4), Map.entry("bool",4),
            Map.entry("vec2",8), Map.entry("vec3",12), Map.entry("vec4",16),
            Map.entry("ivec2",8), Map.entry("ivec3",12), Map.entry("ivec4",16),
            Map.entry("uint",4), Map.entry("uvec2",8), Map.entry("uvec3",12), Map.entry("uvec4",16),
            Map.entry("mat2",32), Map.entry("mat3",48), Map.entry("mat4",64));
    private static final Map<String,String> TEXTURE_FUNCTIONS = Map.ofEntries(
            Map.entry("texture2D","texture"),Map.entry("texture2DProj","textureProj"),
            Map.entry("texture2DLod","textureLod"),Map.entry("texture2DProjLod","textureProjLod"),
            Map.entry("texture2DLodARB","textureLod"),Map.entry("texture2DProjLodARB","textureProjLod"),
            Map.entry("texture2DGradARB","textureGrad"),Map.entry("texture2DProjGradARB","textureProjGrad"),
            Map.entry("texture3D","texture"),Map.entry("texture3DLod","textureLod"),Map.entry("texelFetch3D","texelFetch"));

    private final Map<String,String> imageFormats;

    public PackProgramTranslator() { this(Map.of()); }
    /** {@code imageFormats} names each pack image's GLSL format, for storage declarations that do not state one. */
    public PackProgramTranslator(Map<String,String> imageFormats) { this.imageFormats = Map.copyOf(imageFormats); }

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
        return translate(vertex,null,fragment,coordinates);
    }

    /**
     * With a geometry program between them, the vertex stage's varyings feed its {@code in T name[]} inputs and its
     * outputs feed the fragment stage; it then owns the move from OpenGL clip space, once per emitted vertex.
     */
    public TranslatedProgram translate(String vertex, String geometry, String fragment, CoordinateConvention coordinates) {
        String vs = prepare(vertex), fs = prepare(fragment), gs = geometry == null ? null : prepare(geometry);
        if (coordinates == null) throw new NullPointerException("coordinates");
        Map<String,PackStorageImages.Storage> storage = PackStorageImages.empty();
        vs = PackStorageImages.lift(vs,storage); fs = PackStorageImages.lift(fs,storage);
        Set<Integer> buffers = new TreeSet<>();
        vs = PackStorageBuffers.lift(vs,buffers); fs = PackStorageBuffers.lift(fs,buffers);
        PackGeometryInterface.Parsed parsed = null;
        if (gs != null) {
            parsed = PackGeometryInterface.parse(PackStorageBuffers.lift(PackStorageImages.lift(gs,storage),buffers));
            gs = PackDeclarationNormalizer.declarations(parsed.source(),"").vertex();
        }
        var declarations = PackDeclarationNormalizer.declarations(vs,fs);
        vs = declarations.vertex(); fs = declarations.fragment();
        if (coordinates != CoordinateConvention.NATIVE_VULKAN) {
            var varyings = PackDeclarationNormalizer.legacyVaryings(vs,fs);
            vs = varyings.vertex(); fs = varyings.fragment();
            boolean gameFramebuffer=coordinates==CoordinateConvention.OPENGL_LEGACY;
            boolean reversed=coordinates==CoordinateConvention.OPENGL_REVERSED_DEPTH_TARGET;
            vs = gs == null ? LegacyShaderContract.lower(vs,true,gameFramebuffer,reversed) : LegacyShaderContract.lowerVertexBeforeGeometry(vs,gameFramebuffer,reversed);
            fs = LegacyShaderContract.lower(fs,false,gameFramebuffer,reversed);
            if (gs != null) gs = LegacyShaderContract.lowerGeometry(gs,gameFramebuffer,reversed);
        }
        validateBuiltins(vs,Set.of()); validateBuiltins(fs,Set.of());
        if (gs != null) validateBuiltins(gs,GEOMETRY_BUILTINS);
        Map<String,String> uniforms = new TreeMap<>(), samplers = new TreeMap<>();
        Map<String,String> attributes = new TreeMap<>(), vertexVaryings = new TreeMap<>(), fragmentVaryings = new TreeMap<>();
        Map<String,String> geometryVaryings = new TreeMap<>();
        scan(vs, true, uniforms, samplers, attributes, vertexVaryings);
        scan(fs, false, uniforms, samplers, attributes, fragmentVaryings);
        if (gs != null) {
            Map<String,String> geometryAttributes = new TreeMap<>();
            scan(gs, true, uniforms, samplers, geometryAttributes, geometryVaryings);
            if (!geometryAttributes.isEmpty()) throw unsupported("Geometry programs have no vertex attributes");
            for (var input : parsed.inputs().entrySet()) if (!input.getValue().equals(varyingType(vertexVaryings.getOrDefault(input.getKey(),"")))) {
                throw unsupported("Geometry input has no matching vertex varying: " + input.getKey());
            }
        }
        Map<String,String> fed = gs == null ? vertexVaryings : geometryVaryings;
        for (Map.Entry<String,String> entry : fragmentVaryings.entrySet()) {
            if (!varyingType(entry.getValue()).equals(varyingType(fed.getOrDefault(entry.getKey(),"")))) {
                throw unsupported("Fragment input has no matching " + (gs == null ? "vertex" : "geometry") + " varying: " + entry.getKey());
            }
        }
        Map<String,Integer> attributeLocations = locations(attributes), varyingLocations = locations(vertexVaryings), fedLocations = locations(fed);
        ProgramBindings bindings = bindings(uniforms,samplers,buffers,attributeLocations,varyingLocations,attributes);
        String geometryText = gs == null ? null : insertBodyPreamble(rewrite(gs,true,uniforms,samplers,Map.of(),geometryVaryings,fedLocations,bindings,storage,buffers),
                PackGeometryInterface.declarations(parsed.inputs(),varyingLocations));
        return new TranslatedProgram(rewrite(vs,true,uniforms,samplers,attributes,vertexVaryings,varyingLocations,bindings,storage,buffers),
                geometryText,rewrite(fs,false,uniforms,samplers,attributes,fragmentVaryings,fedLocations,bindings,storage,buffers),bindings,coordinates,
                parsed == null ? null : parsed.primitive());
    }

    private static String prepare(String source) {
        String prepared = PackExtensions.lower(stripComments(source));
        rejectUnprocessedDirectives(prepared);
        return prepared;
    }

    /**
     * A compute program: uniforms, samplers and storage images only. Its dispatch size comes from the pack's
     * {@code workGroups} or {@code workGroupsRender} constant, read before the constants are compiled away.
     */
    public TranslatedCompute translateCompute(String source) {
        String cs = PackExtensions.lower(stripComments(source));
        rejectUnprocessedDirectives(cs);
        Map<String,PackStorageImages.Storage> storage = PackStorageImages.empty();
        Set<Integer> buffers = new TreeSet<>();
        cs = PackDeclarationNormalizer.declarations(PackStorageBuffers.lift(PackStorageImages.lift(cs,storage),buffers),"").vertex();
        validateBuiltins(cs,COMPUTE_BUILTINS);
        Map<String,String> uniforms = new TreeMap<>(), samplers = new TreeMap<>(), attributes = new TreeMap<>(), varyings = new TreeMap<>();
        scan(cs,false,uniforms,samplers,attributes,varyings);
        if (!attributes.isEmpty() || !varyings.isEmpty()) throw unsupported("Compute programs have no attributes or varyings");
        ProgramBindings bindings = bindings(uniforms,samplers,buffers,Map.of(),Map.of(),Map.of());
        int[] local = localSize(cs);
        Matcher groups = WORK_GROUPS.matcher(cs), render = WORK_GROUPS_RENDER.matcher(cs);
        int[] workGroups = groups.find() ? new int[]{Integer.parseInt(groups.group(1)),Integer.parseInt(groups.group(2)),Integer.parseInt(groups.group(3))} : null;
        float[] scale = workGroups == null && render.find() ? new float[]{Float.parseFloat(render.group(1)),Float.parseFloat(render.group(2))} : null;
        if (workGroups == null && scale == null) scale = new float[]{1,1};
        cs = LOCAL_SIZE.matcher(cs).replaceAll("");
        String body = rewrite(cs,false,uniforms,samplers,Map.of(),Map.of(),Map.of(),bindings,storage,buffers);
        return new TranslatedCompute(insertBodyPreamble(body,"layout(local_size_x="+local[0]+",local_size_y="+local[1]+",local_size_z="+local[2]+") in;\n"),
                bindings,local,workGroups,scale);
    }
    private static int[] localSize(String source) {
        int[] size = {1,1,1};
        Matcher matcher = LOCAL_SIZE.matcher(source);
        if (!matcher.find()) throw unsupported("Compute program declares no local_size");
        for (String part : matcher.group(1).split(",")) {
            String[] pair = part.split("=");
            if (pair.length != 2) throw unsupported("Unsupported compute layout: "+matcher.group(1));
            int axis = switch (pair[0].trim()) { case "local_size_x" -> 0; case "local_size_y" -> 1; case "local_size_z" -> 2; default -> throw unsupported("Unsupported compute layout: "+part.trim()); };
            try { size[axis] = Integer.parseInt(pair[1].trim()); } catch (NumberFormatException failure) { throw unsupported("Compute local size must be a literal: "+part.trim()); }
        }
        if (matcher.find()) throw unsupported("Compute program declares local_size twice");
        return size;
    }
    /** Samplers and storage images bind from one, then each storage buffer after them. */
    private static ProgramBindings bindings(Map<String,String> uniforms, Map<String,String> samplers, Set<Integer> buffers,
                                            Map<String,Integer> attributeLocations, Map<String,Integer> varyingLocations, Map<String,String> attributes) {
        Map<String,Integer> samplerBindings = new LinkedHashMap<>();
        Map<String,String> types = new LinkedHashMap<>(samplers);
        for (String name : samplers.keySet()) samplerBindings.put(name, samplerBindings.size()+1);
        for (int index : buffers) {
            String name = PackStorageBuffers.name(index);
            if (types.put(name,ProgramBindings.BUFFER) != null) throw unsupported("Storage buffer name collides with a sampler: "+name);
            samplerBindings.put(name, samplerBindings.size()+1);
        }
        Map<String,ProgramBindings.Uniform> layout = new LinkedHashMap<>();
        int offset = 0;
        for (Map.Entry<String,String> uniform : uniforms.entrySet()) {
            String type = uniform.getValue();
            int size = SIZES.get(type), alignment = alignment(type);
            offset = align(offset, alignment);
            layout.put(uniform.getKey(), new ProgramBindings.Uniform(type, offset, size));
            offset += size;
        }
        return new ProgramBindings(layout, align(offset,16), samplerBindings, attributeLocations, varyingLocations,attributes,types);
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

    private static void validateBuiltins(String source, Set<String> extra) {
        Matcher matcher = BUILTIN.matcher(source);
        while (matcher.find()) if (!BUILTINS.contains(matcher.group()) && !extra.contains(matcher.group())) {
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
            String qualifier = matcher.group(1), kind = matcher.group(2), type = matcher.group(3), name = matcher.group(4);
            if (kind.equals("uniform")) {
                if (SAMPLER_TYPES.contains(type) || PackStorageImages.IMAGE_TYPES.contains(type)) put(samplers,name,type);
                else if (SIZES.containsKey(type)) put(uniforms,name,type);
                else throw unsupported("Unsupported uniform type: " + type);
                if (samplers.containsKey(name) && uniforms.containsKey(name)) throw unsupported("Conflicting uniform: " + name);
            } else {
                boolean integer = INTEGER_VARYINGS.contains(type);
                if (kind.equals("attribute")) {
                    // A matrix input fills one location per column, as GL lays out an instanced transform.
                    if (!ATTRIBUTE_TYPES.contains(type) && !VARYING_SLOTS.containsKey(type)) throw unsupported("Unsupported interface type: " + type);
                    if (!vertex) throw unsupported("Attributes are vertex-stage inputs");
                    put(attributes,name,type);
                } else {
                    if (!ATTRIBUTE_TYPES.contains(type) && !integer && !VARYING_SLOTS.containsKey(type)) throw unsupported("Unsupported interface type: " + type);
                    // Vulkan requires a flat integer fragment input; a vertex output may omit the qualifier.
                    if (integer && !vertex) qualifier = "flat";
                    put(varyings,name,qualifier == null ? type : qualifier+" "+type);
                }
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

    /** A matrix fills one location per column, so the next interface value starts after it. */
    private static Map<String,Integer> locations(Map<String,String> values) {
        Map<String,Integer> result = new LinkedHashMap<>();
        int next = 0;
        for (var entry : values.entrySet()) {
            result.put(entry.getKey(),next);
            next += VARYING_SLOTS.getOrDefault(varyingType(entry.getValue()),1);
        }
        return result;
    }
    private static String varyingType(String declared) { return declared.substring(declared.lastIndexOf(' ')+1); }

    private static int alignment(String type) {
        if (type.startsWith("mat") || type.endsWith("3") || type.endsWith("4")) return 16;
        return type.endsWith("2") ? 8 : 4;
    }
    private static int align(int value, int alignment) { return (value+alignment-1)/alignment*alignment; }

    /** The name a sampler is declared under; a leading underscore would make "__", which GLSL reserves. */
    static String samplerName(String sampler) {
        return (sampler.startsWith("_") ? "overhaulSamplerU" : "overhaulSampler_") + sampler;
    }

    private String rewrite(String source, boolean vertex, Map<String,String> uniforms,
                                  Map<String,String> samplers, Map<String,String> attributes,
                                  Map<String,String> varyings, Map<String,Integer> locations, ProgramBindings bindings, Map<String,PackStorageImages.Storage> storage, Set<Integer> buffers) {
        String body = DECLARATION.matcher(source).replaceAll("").replaceAll("(?m)^\\s*#version[^\\r\\n]*", "");
        body = PackStorageBuffers.place(body,buffers,bindings.samplers());
        // Legacy packs commonly name a sampler "texture", now also a GLSL function.
        for (String sampler : samplers.keySet()) {
            body = body.replaceAll("\\b" + Pattern.quote(sampler) + "\\b", Matcher.quoteReplacement(samplerName(sampler)));
        }
        for(var function:TEXTURE_FUNCTIONS.entrySet())body=body.replaceAll("\\b"+function.getKey()+"\\b",function.getValue());
        boolean shadow2D=Pattern.compile("\\bshadow2D\\s*\\(").matcher(body).find(),shadowProj=Pattern.compile("\\bshadow2DProj\\s*\\(").matcher(body).find();
        body=body.replaceAll("\\bshadow2D\\b","overhaulShadow2D").replaceAll("\\bshadow2DProj\\b","overhaulShadow2DProj");
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
        for (String sampler : samplers.keySet()) {
            String type = samplers.get(sampler);
            int binding = bindings.samplers().get(sampler);
            if (PackStorageImages.IMAGE_TYPES.contains(type)) prefix.append(PackStorageImages.declaration(sampler,type,binding,storage.get(sampler),imageFormats));
            else prefix.append("layout(set=0,binding=").append(binding).append(") uniform ").append(type).append(' ').append(samplerName(sampler)).append(";\n");
        }
        if (vertex) for (Map.Entry<String,String> attribute : attributes.entrySet()) prefix.append("layout(location=")
                .append(bindings.attributes().get(attribute.getKey())).append(") in ").append(attribute.getValue())
                .append(' ').append(attribute.getKey()).append(";\n");
        for (Map.Entry<String,String> varying : varyings.entrySet()) prefix.append("layout(location=")
                .append(locations.get(varying.getKey())).append(vertex ? ") out " : ") in ")
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
        if(shadow2D){
            prefix.append("vec4 overhaulShadow2D(sampler2DShadow s,vec3 p){return vec4(texture(s,p));}\n");
            if(!vertex)prefix.append("vec4 overhaulShadow2D(sampler2DShadow s,vec3 p,float bias){return vec4(texture(s,p,bias));}\n");
        }
        if(shadowProj){
            prefix.append("vec4 overhaulShadow2DProj(sampler2DShadow s,vec4 p){return vec4(textureProj(s,p));}\n");
            if(!vertex)prefix.append("vec4 overhaulShadow2DProj(sampler2DShadow s,vec4 p,float bias){return vec4(textureProj(s,p,bias));}\n");
        }
        return prefix.append(BODY_MARKER).append(body).toString();
    }

    private static UnsupportedShaderException unsupported(String message) { return new UnsupportedShaderException(message); }
    /** {@code geometry} and {@code primitive} are null for a program without a geometry stage. */
    public record TranslatedProgram(String vertex, String geometry, String fragment, ProgramBindings bindings, CoordinateConvention coordinates,
                                    PackGeometryInterface.Primitive primitive) {
        public TranslatedProgram(String vertex, String fragment, ProgramBindings bindings, CoordinateConvention coordinates) {
            this(vertex,null,fragment,bindings,coordinates,null);
        }
    }
    /** {@code workGroups} is null when the dispatch scales with the screen by {@code workGroupsRender}. */
    public record TranslatedCompute(String source, ProgramBindings bindings, int[] localSize, int[] workGroups, float[] workGroupsRender) {}
}
