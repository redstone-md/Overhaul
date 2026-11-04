package dev.overhaul.minecraft.shader;

import dev.overhaul.minecraft.model.ModelVertexShaderSource;
import dev.overhaul.shader.compile.ShaderStage;
import dev.overhaul.shader.compile.ShadercCompiler;
import dev.overhaul.shader.compile.SpirvBinary;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Rewrites exact vanilla scene text into cascade receivers; any mismatch fails rather than guessing. */
public final class OwnedReceiverShaders {
    /** Every pinned closure the receiver programs read: terrain, entity models and block models. */
    public static final List<String> MANIFESTS=List.of("/overhaul-terrain-shaders.properties","/overhaul-model-shaders.properties","/overhaul-block-shaders.properties");
    public record Compiled(SpirvBinary vertex,SpirvBinary fragment) {}
    private record Edit(String target,String replacement){}
    private static final String RECEIVER="/assets/overhaul/shaderpacks/csm/cascade_receiver.glsl";
    private static final String CORE="shaders/core/",INCLUDES="shaders/include/",NAMESPACE="minecraft:",OWNED_NAMESPACE="overhaul:";
    private static final List<String> OWNED_INCLUDES=List.of("model_quad_inputs.glsl","packed_vertex.glsl");
    private static final String FIRST_OUTPUT="layout(location = 0) out float sphericalVertexDistance;";
    // After every vanilla varying, including the foil coordinates the entity program places at location 7.
    private static final Edit OUTPUTS=new Edit(FIRST_OUTPUT,"layout(location = 8) out vec4 overhaulShadowedColor;\nlayout(location = 9) out vec3 overhaulPosition;\nlayout(location = 10) out float overhaulViewDepth;\n"+FIRST_OUTPUT);
    private static final Edit LIT_VERTEX=new Edit("vertexColor = Color * sample_lightmap(Sampler2, UV2);",
            "vertexColor = Color * sample_lightmap(Sampler2, UV2);\n    overhaulShadowedColor = Color * sample_lightmap(Sampler2, ivec2(UV2.x, 0));\n    overhaulPosition = pos;\n    overhaulViewDepth = -(ModelViewMat * vec4(pos, 1.0)).z;");
    private static final String ENTITY_CLIP="gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);";
    private static final Edit ENTITY_POSITION=new Edit(ENTITY_CLIP,ENTITY_CLIP+"\n    overhaulPosition = Position;\n    overhaulViewDepth = -(ModelViewMat * vec4(Position, 1.0)).z;");
    private static final String ENTITY_LIGHT="lightMapColor = sample_lightmap(Sampler2, UV2);";
    private static final Edit ENTITY_SHADOWED=new Edit(ENTITY_LIGHT,ENTITY_LIGHT+"\n    overhaulShadowedColor = sample_lightmap(Sampler2, ivec2(UV2.x, 0));");
    private static final Pattern MAIN=Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*\\)");
    // The entity program lights its colour inside this helper, which main follows.
    private static final Pattern ENTITY_HELPER=Pattern.compile("\\bvec4\\s+calculateFinalColor\\s*\\(");
    private static final Pattern BLOCK=Pattern.compile("layout\\s*\\(\\s*std140\\s*\\)\\s*uniform\\s+(\\w+)");
    private static final Pattern SAMPLER=Pattern.compile("\\buniform\\s+(\\w*sampler\\w*)\\s+(\\w+)\\s*;");

    /** A vanilla program family and the exact text that carries its sky-lit colour. */
    public enum Program {
        TERRAIN("terrain",MAIN,List.of(OUTPUTS,LIT_VERTEX),new Edit("1.0f / TextureSize)) * vertexColor;","1.0f / TextureSize)) * mix(overhaulShadowedColor, vertexColor, overhaulShadowVisibility());")),
        ENTITY("entity",ENTITY_HELPER,List.of(OUTPUTS,ENTITY_POSITION,ENTITY_SHADOWED),new Edit("color *= lightMapColor;","color *= mix(overhaulShadowedColor, lightMapColor, overhaulShadowVisibility());")),
        BLOCK("block",MAIN,List.of(OUTPUTS,LIT_VERTEX),new Edit("vec4 color = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;","vec4 color = texture(Sampler0, texCoord0) * mix(overhaulShadowedColor, vertexColor, overhaulShadowVisibility()) * ColorModulator;"));
        private final String name;
        private final Pattern receiverBefore;
        private final List<Edit> vertex;
        private final Edit fragment;
        Program(String name,Pattern receiverBefore,List<Edit> vertex,Edit fragment){this.name=name;this.receiverBefore=receiverBefore;this.vertex=vertex;this.fragment=fragment;}
    }
    private final Map<String,String> files;
    private final String receiver;

    private OwnedReceiverShaders(Map<String,String> files,String receiver){this.files=Map.copyOf(files);this.receiver=receiver;}
    public static OwnedReceiverShaders load(ResourceManager resources)throws IOException{
        Map<String,String> files=new HashMap<>();
        for(String manifestPath:MANIFESTS){
            Properties manifest=new Properties();
            try(InputStream input=packaged(manifestPath)){manifest.load(input);}
            for(String path:manifest.stringPropertyNames()){
                Identifier resource=Identifier.parse(path);
                try(InputStream input=resources.open(resource)){files.put(resource.getPath(),text(input));}
            }
        }
        for(String include:OWNED_INCLUDES)try(InputStream input=packaged("/assets/overhaul/shaders/include/"+include)){files.put(OWNED_NAMESPACE+include,text(input));}
        try(InputStream input=packaged(RECEIVER)){return new OwnedReceiverShaders(files,text(input));}
    }
    /** {@code uniforms} is the pipeline's dense set 0 order; a name outside it is not statically used by these sources. */
    public Compiled compile(Program program,boolean modelInstances,Map<String,String> defines,Set<String> flags,List<String> uniforms,ShadercCompiler compiler){
        Map<String,String> definitions=new HashMap<>(defines);for(String flag:flags)definitions.putIfAbsent(flag,"1");
        String vertexPath=CORE+program.name+".vsh",fragmentPath=CORE+program.name+".fsh";
        String vertex=file(vertexPath);
        // Certified against the pinned text first, so the receiver edits never reach the digest check.
        if(modelInstances)vertex=ModelVertexShaderSource.adapt(Identifier.parse(NAMESPACE+"core/"+program.name),vertex);
        for(Edit edit:program.vertex)vertex=replaceOnce(vertex,edit);
        String fragment=replaceOnce(file(fragmentPath),program.fragment);
        String boundVertex=bind(prepare(vertexPath,vertex,ShaderStage.VERTEX,definitions,compiler),uniforms);
        String boundFragment=withReceiver(bind(prepare(fragmentPath,fragment,ShaderStage.FRAGMENT,definitions,compiler),uniforms),program.receiverBefore);
        String label="overhaul-cascade-"+program.name;
        return new Compiled(compiler.compile(label+".vsh",boundVertex,ShaderStage.VERTEX),compiler.compile(label+".fsh",boundFragment,ShaderStage.FRAGMENT));
    }
    private String prepare(String name,String source,ShaderStage stage,Map<String,String> definitions,ShadercCompiler compiler){
        return compiler.preprocess(name,source,stage,definitions,(requested,requester,relative)->new ShadercCompiler.ResolvedInclude(requested,include(requested)))
          .replaceAll("(?m)^\\s*#line[^\\r\\n]*","")
          .replaceAll("(?m)^\\s*#extension\\s+GL_(?:GOOGLE_include_directive|GOOGLE_cpp_style_line_directive|ARB_separate_shader_objects)[^\\r\\n]*","");
    }
    private String include(String requested){
        if(requested.startsWith(NAMESPACE))return file(INCLUDES+requested.substring(NAMESPACE.length()));
        if(requested.startsWith(OWNED_NAMESPACE))return file(requested);
        throw new IllegalArgumentException("Unexpected receiver include: "+requested);
    }
    private static String bind(String source,List<String> uniforms){
        int[] unused={uniforms.size()};
        String blocks=replaceAll(BLOCK,source,match->"layout(std140, set = 0, binding = "+slot(match.group(1),uniforms,unused)+") uniform "+match.group(1));
        return replaceAll(SAMPLER,blocks,match->"layout(set = 0, binding = "+slot(match.group(2),uniforms,unused)+") uniform "+match.group(1)+" "+match.group(2)+";");
    }
    private static int slot(String name,List<String> uniforms,int[] unused){int slot=uniforms.indexOf(name);return slot>=0?slot:unused[0]++;}
    private String withReceiver(String source,Pattern before){
        Matcher anchor=before.matcher(source);
        if(!anchor.find())throw new IllegalStateException("Fragment shader has no receiver anchor: "+before);
        int start=anchor.start();
        if(anchor.find())throw new IllegalStateException("Fragment shader has several receiver anchors: "+before);
        return source.substring(0,start)+receiver+source.substring(start);
    }
    private static String replaceAll(Pattern pattern,String source,java.util.function.Function<Matcher,String> replacement){
        Matcher match=pattern.matcher(source);StringBuilder result=new StringBuilder();
        while(match.find())match.appendReplacement(result,Matcher.quoteReplacement(replacement.apply(match)));
        return match.appendTail(result).toString();
    }
    private static String replaceOnce(String source,Edit edit){
        int first=source.indexOf(edit.target());
        if(first<0||first!=source.lastIndexOf(edit.target()))throw new IllegalStateException("Receiver shader text differs from the pinned source: "+edit.target());
        return source.substring(0,first)+edit.replacement()+source.substring(first+edit.target().length());
    }
    private String file(String path){
        String text=files.get(path);if(text==null)throw new IllegalStateException("Receiver shader file is outside the pinned closure: "+path);return text;
    }
    private static InputStream packaged(String path)throws IOException{
        InputStream input=OwnedReceiverShaders.class.getResourceAsStream(path);
        if(input==null)throw new IOException("Missing packaged resource "+path);return input;
    }
    private static String text(InputStream input)throws IOException{return new String(input.readAllBytes(),StandardCharsets.UTF_8);}
}
