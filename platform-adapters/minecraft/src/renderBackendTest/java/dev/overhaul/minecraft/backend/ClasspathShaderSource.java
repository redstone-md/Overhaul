package dev.overhaul.minecraft.backend;

import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import net.minecraft.resources.Identifier;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Uses unchanged game/adapter shader resources, retaining native include callbacks until compilation ends. */
final class ClasspathShaderSource implements ShaderSource {
    private final Map<Identifier,CachedIncludeSource> includes=new HashMap<>();
    @Override public String getShader(Identifier id,ShaderType type){
        String extension=switch(type){case VERTEX->"vsh";case FRAGMENT->"fsh";};
        return read(id,"shaders/"+id.getPath()+"."+extension);
    }
    @Override public synchronized CachedIncludeSource getInclude(Identifier id){
        return includes.computeIfAbsent(id,key->CachedIncludeSource.create(key,read(key,"shaders/include/"+key.getPath())));
    }
    private static String read(Identifier id,String path){
        String resource="/assets/"+id.getNamespace()+"/"+path;
        try(var stream=ClasspathShaderSource.class.getResourceAsStream(resource)){
            if(stream==null)throw new IllegalArgumentException("Shader resource absent: "+resource);
            return new String(stream.readAllBytes(),StandardCharsets.UTF_8);
        }catch(IOException failure){throw new IllegalStateException("Cannot read shader resource "+resource,failure);}
    }
    @Override public synchronized void close(){for(var include:includes.values())include.close();includes.clear();}
}
