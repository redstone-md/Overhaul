package dev.overhaul.minecraft.shader;

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

/** Rewrites exact vanilla terrain text into a cascade receiver; any mismatch fails rather than guessing. */
public final class OwnedTerrainShaders {
    public static final String MANIFEST="/overhaul-terrain-shaders.properties";
    public record Compiled(SpirvBinary vertex,SpirvBinary fragment) {}
    private static final String RECEIVER="/assets/overhaul/shaderpacks/csm/terrain_receiver.glsl";
    private static final String VERTEX="shaders/core/terrain.vsh",FRAGMENT="shaders/core/terrain.fsh",INCLUDES="shaders/include/",NAMESPACE="minecraft:";
    private static final String VERTEX_MAIN="void main() {";
    private static final String VERTEX_OUTPUTS="layout(location = 5) out vec4 overhaulShadowedColor;\nlayout(location = 6) out vec3 overhaulPosition;\nlayout(location = 7) out float overhaulViewDepth;\n\n";
    private static final String VERTEX_COLOR="vertexColor = Color * sample_lightmap(Sampler2, UV2);";
    private static final String VERTEX_RECEIVER="\n    overhaulShadowedColor = Color * sample_lightmap(Sampler2, ivec2(UV2.x, 0));\n    overhaulPosition = pos;\n    overhaulViewDepth = -(ModelViewMat * vec4(pos, 1.0)).z;";
    private static final String FRAGMENT_COLOR="1.0f / TextureSize)) * vertexColor;";
    private static final String FRAGMENT_RECEIVER="1.0f / TextureSize)) * mix(overhaulShadowedColor, vertexColor, overhaulShadowVisibility());";
    private static final Pattern BLOCK=Pattern.compile("layout\\s*\\(\\s*std140\\s*\\)\\s*uniform\\s+(\\w+)");
    private static final Pattern SAMPLER=Pattern.compile("\\buniform\\s+(\\w*sampler\\w*)\\s+(\\w+)\\s*;");
    private static final Pattern MAIN=Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*\\)");
    private final Map<String,String> files;
    private final String receiver;

    private OwnedTerrainShaders(Map<String,String> files,String receiver){this.files=Map.copyOf(files);this.receiver=receiver;}
    public static OwnedTerrainShaders load(ResourceManager resources)throws IOException{
        Properties manifest=new Properties();
        try(InputStream input=packaged(MANIFEST)){manifest.load(input);}
        Map<String,String> files=new HashMap<>();
        for(String path:manifest.stringPropertyNames())try(InputStream input=resources.open(Identifier.parse(path))){files.put(path,text(input));}
        try(InputStream input=packaged(RECEIVER)){return new OwnedTerrainShaders(files,text(input));}
    }
    /** {@code uniforms} is the pipeline's dense set 0 order; a name outside it is not statically used by these sources. */
    public Compiled compile(Map<String,String> defines,Set<String> flags,List<String> uniforms,ShadercCompiler compiler){
        Map<String,String> definitions=new HashMap<>(defines);for(String flag:flags)definitions.putIfAbsent(flag,"1");
        String vertex=replaceOnce(replaceOnce(file(VERTEX),VERTEX_MAIN,VERTEX_OUTPUTS+VERTEX_MAIN),VERTEX_COLOR,VERTEX_COLOR+VERTEX_RECEIVER);
        String fragment=replaceOnce(file(FRAGMENT),FRAGMENT_COLOR,FRAGMENT_RECEIVER);
        String boundVertex=bind(prepare(VERTEX,vertex,ShaderStage.VERTEX,definitions,compiler),uniforms);
        String boundFragment=withReceiver(bind(prepare(FRAGMENT,fragment,ShaderStage.FRAGMENT,definitions,compiler),uniforms));
        return new Compiled(compiler.compile("overhaul-cascade-terrain.vsh",boundVertex,ShaderStage.VERTEX),compiler.compile("overhaul-cascade-terrain.fsh",boundFragment,ShaderStage.FRAGMENT));
    }
    private String prepare(String name,String source,ShaderStage stage,Map<String,String> definitions,ShadercCompiler compiler){
        return compiler.preprocess(name,source,stage,definitions,(requested,requester,relative)->{
            if(!requested.startsWith(NAMESPACE))throw new IllegalArgumentException("Unexpected terrain include: "+requested);
            String path=INCLUDES+requested.substring(NAMESPACE.length());
            return new ShadercCompiler.ResolvedInclude(requested,file(path));
        }).replaceAll("(?m)^\\s*#line[^\\r\\n]*","")
          .replaceAll("(?m)^\\s*#extension\\s+GL_(?:GOOGLE_include_directive|GOOGLE_cpp_style_line_directive|ARB_separate_shader_objects)[^\\r\\n]*","");
    }
    private static String bind(String source,List<String> uniforms){
        int[] unused={uniforms.size()};
        String blocks=replaceAll(BLOCK,source,match->"layout(std140, set = 0, binding = "+slot(match.group(1),uniforms,unused)+") uniform "+match.group(1));
        return replaceAll(SAMPLER,blocks,match->"layout(set = 0, binding = "+slot(match.group(2),uniforms,unused)+") uniform "+match.group(1)+" "+match.group(2)+";");
    }
    private static int slot(String name,List<String> uniforms,int[] unused){int slot=uniforms.indexOf(name);return slot>=0?slot:unused[0]++;}
    private String withReceiver(String source){
        Matcher main=MAIN.matcher(source);
        if(!main.find())throw new IllegalStateException("Terrain fragment shader has no main");
        int start=main.start();
        if(main.find())throw new IllegalStateException("Terrain fragment shader has several mains");
        return source.substring(0,start)+receiver+source.substring(start);
    }
    private static String replaceAll(Pattern pattern,String source,java.util.function.Function<Matcher,String> replacement){
        Matcher match=pattern.matcher(source);StringBuilder result=new StringBuilder();
        while(match.find())match.appendReplacement(result,Matcher.quoteReplacement(replacement.apply(match)));
        return match.appendTail(result).toString();
    }
    private static String replaceOnce(String source,String target,String replacement){
        int first=source.indexOf(target);
        if(first<0||first!=source.lastIndexOf(target))throw new IllegalStateException("Terrain shader text differs from the pinned source: "+target);
        return source.substring(0,first)+replacement+source.substring(first+target.length());
    }
    private String file(String path){
        String text=files.get(path);if(text==null)throw new IllegalStateException("Terrain shader file is outside the pinned closure: "+path);return text;
    }
    private static InputStream packaged(String path)throws IOException{
        InputStream input=OwnedTerrainShaders.class.getResourceAsStream(path);
        if(input==null)throw new IOException("Missing packaged resource "+path);return input;
    }
    private static String text(InputStream input)throws IOException{return new String(input.readAllBytes(),StandardCharsets.UTF_8);}
}
