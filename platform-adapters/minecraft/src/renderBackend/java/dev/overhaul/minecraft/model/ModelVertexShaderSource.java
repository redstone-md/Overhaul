package dev.overhaul.minecraft.model;

import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import dev.overhaul.minecraft.shader.OwnedVertexShaderSource;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strict input adapter: certified original program bodies, fragments, definitions and includes remain intact. */
public final class ModelVertexShaderSource implements ShaderSource {
    private static final String PREFIX="model_instanced/";
    private static final Set<String> PROGRAMS=Set.of("core/entity","core/glint","core/rendertype_outline","core/rendertype_crumbling");
    private static final Pattern ATTRIBUTE=Pattern.compile("(?m)^layout\\(location = ([0-9]+)\\) in (vec[234]|ivec2) (Position|Color|UV0|UV1|UV2|Normal);[ \\t]*\\r?$");
    private static final Pattern MAIN=Pattern.compile("\\bvoid[ \\t]+main[ \\t]*\\([ \\t]*\\)[ \\t]*\\{");
    private static final Pattern VERSION=Pattern.compile("(?m)^#version[ \\t]+330[ \\t]*\\r?$");
    private static final Properties EXPECTED=manifest();
    private final OwnedVertexShaderSource source;
    public ModelVertexShaderSource(ResourceManager resources){source=new OwnedVertexShaderSource(resources,includeClosure());}
    private static List<Identifier> includeClosure(){
        List<Identifier> includes=new ArrayList<>();
        for(String key:EXPECTED.stringPropertyNames()){
            Identifier resource=Identifier.parse(key);String prefix="shaders/include/";
            if(resource.getPath().startsWith(prefix))includes.add(Identifier.fromNamespaceAndPath(resource.getNamespace(),resource.getPath().substring(prefix.length())));
        }
        includes.sort(java.util.Comparator.comparing(Identifier::toString));
        includes.add(Identifier.parse("overhaul:model_quad_inputs.glsl"));
        includes.add(Identifier.parse("overhaul:packed_vertex.glsl"));
        return includes;
    }
    public static boolean supports(Identifier original){return original!=null&&original.getNamespace().equals("minecraft")&&PROGRAMS.contains(original.getPath());}
    public static Identifier adaptedVertex(Identifier original){
        if(!supports(original))throw new UnsupportedOperationException("Uncertified model vertex program: "+original);
        return Identifier.fromNamespaceAndPath("overhaul",PREFIX+original.getNamespace()+"/"+original.getPath());
    }
    @Override public String getShader(Identifier id,ShaderType type){
        if(type==ShaderType.VERTEX&&id.getNamespace().equals("overhaul")&&id.getPath().startsWith(PREFIX)){
            String name=id.getPath().substring(PREFIX.length());int slash=name.indexOf('/');if(slash<1)throw new IllegalArgumentException("Malformed adapted vertex identifier");
            Identifier original=Identifier.fromNamespaceAndPath(name.substring(0,slash),name.substring(slash+1));
            return adapt(original,source.getShader(original,type));
        }
        return source.getShader(id,type);
    }
    public static String adapt(Identifier original,String text){
        if(!supports(original))throw new UnsupportedOperationException("Uncertified model vertex program: "+original);
        String expected=EXPECTED.getProperty(original.getNamespace()+":shaders/"+original.getPath()+".vsh");
        try{if(expected==null||!expected.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)))))throw new IllegalArgumentException("Model vertex source differs from the pinned program: "+original);}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
        Map<String,String> types=Map.of("Position","vec3","Color","vec4","UV0","vec2","UV1","ivec2","UV2","ivec2","Normal","vec3");
        Set<String> names=new HashSet<>();Matcher attributes=ATTRIBUTE.matcher(text);StringBuffer stripped=new StringBuffer();
        while(attributes.find()){
            if(!types.get(attributes.group(3)).equals(attributes.group(2))||!names.add(attributes.group(3)))throw new IllegalArgumentException("Unexpected model input declaration");
            attributes.appendReplacement(stripped,"");
        }
        attributes.appendTail(stripped);
        Set<String> required=switch(original.getPath()){
            case "core/entity"->Set.of("Position","Color","UV0","UV1","UV2","Normal");
            case "core/glint"->Set.of("Position","UV0");case "core/rendertype_outline"->Set.of("Position","Color","UV0");
            default->Set.of("Position","Color","UV0","UV2");
        };
        if(!names.equals(required))throw new IllegalArgumentException("Incomplete model input declarations");
        Matcher version=VERSION.matcher(stripped);if(!version.find())throw new IllegalArgumentException("Unexpected model shader version");
        String upgraded=version.replaceFirst("#version 450");Matcher main=MAIN.matcher(upgraded);
        if(!main.find()||main.find())throw new IllegalArgumentException("Expected one model shader entry point");
        return MAIN.matcher(upgraded).replaceFirst("#include <overhaul:model_quad_inputs.glsl>\nvoid overhaulOriginalMain() {")
                +"\nvoid main() { overhaulDecodeModelQuad(); overhaulOriginalMain(); }\n";
    }
    private static Properties manifest(){
        Properties result=new Properties();try(InputStream input=ModelVertexShaderSource.class.getResourceAsStream("/overhaul-model-shaders.properties")){
            if(input==null)throw new IllegalStateException("Missing model shader certificate");result.load(input);return result;
        }catch(java.io.IOException failure){throw new IllegalStateException("Cannot read model shader certificate",failure);}
    }
    @Override public CachedIncludeSource getInclude(Identifier id){return source.getInclude(id);}
    @Override public void close(){source.close();}
}
