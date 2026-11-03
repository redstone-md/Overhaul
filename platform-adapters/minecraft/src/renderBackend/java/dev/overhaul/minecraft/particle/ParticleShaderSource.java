package dev.overhaul.minecraft.particle;

import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** The owned vertex kernel is packaged code; fragment shaders and includes use the certified active resources. */
final class ParticleShaderSource implements ShaderSource {
    private final ResourceManager resources;
    private final Map<Identifier,CachedIncludeSource> includes=new HashMap<>();
    private boolean closed;
    ParticleShaderSource(ResourceManager resources){this.resources=resources;}
    @Override public String getShader(Identifier id,ShaderType type) {
        String path="shaders/"+id.getPath()+switch(type){case VERTEX->".vsh";case FRAGMENT->".fsh";};
        if(type==ShaderType.VERTEX&&id.equals(InstancedParticlePipelines.VERTEX_SHADER)) {
            try(var input=ParticleShaderSource.class.getResourceAsStream("/assets/overhaul/"+path)){return read(input,path);}
            catch(IOException failure){throw new IllegalStateException("Cannot read owned particle vertex kernel",failure);}
        }
        return resource(Identifier.fromNamespaceAndPath(id.getNamespace(),path));
    }
    @Override public synchronized CachedIncludeSource getInclude(Identifier id) {
        if(closed)throw new IllegalStateException("Particle shader source is closed");
        return includes.computeIfAbsent(id,key->CachedIncludeSource.create(key,
                resource(Identifier.fromNamespaceAndPath(key.getNamespace(),"shaders/include/"+key.getPath()))));
    }
    private String resource(Identifier id) {
        try(var input=resources.open(id)){return read(input,id.toString());}
        catch(IOException failure){throw new IllegalStateException("Cannot read certified particle shader resource "+id,failure);}
    }
    private static String read(InputStream input,String label)throws IOException {
        if(input==null)throw new IOException("Missing shader resource "+label);
        byte[] bytes=input.readNBytes(2*1024*1024+1);if(bytes.length>2*1024*1024)throw new IOException("Shader resource exceeds bound: "+label);
        return new String(bytes,StandardCharsets.UTF_8);
    }
    @Override public synchronized void close(){if(closed)return;for(var include:includes.values())include.close();includes.clear();closed=true;}
}
