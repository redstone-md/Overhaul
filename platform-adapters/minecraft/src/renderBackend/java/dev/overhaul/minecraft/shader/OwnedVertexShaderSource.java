package dev.overhaul.minecraft.shader;

import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Owned kernels/includes are packaged code; other shader resources come from the certified active generation. */
public final class OwnedVertexShaderSource implements ShaderSource {
    private final ResourceManager resources;
    private final Map<Identifier,CachedIncludeSource> includes=new HashMap<>();
    private volatile boolean closed;
    public OwnedVertexShaderSource(ResourceManager resources){this.resources=Objects.requireNonNull(resources);}
    @Override public String getShader(Identifier id,ShaderType type){
        requireOpen();String path="shaders/"+id.getPath()+switch(type){case VERTEX->".vsh";case FRAGMENT->".fsh";};
        return resource(Identifier.fromNamespaceAndPath(id.getNamespace(),path));
    }
    @Override public synchronized CachedIncludeSource getInclude(Identifier id){
        requireOpen();return includes.computeIfAbsent(id,key->CachedIncludeSource.create(key,
                resource(Identifier.fromNamespaceAndPath(key.getNamespace(),"shaders/include/"+key.getPath()))));
    }
    private String resource(Identifier id){
        try(InputStream input=id.getNamespace().equals("overhaul")?OwnedVertexShaderSource.class.getResourceAsStream("/assets/overhaul/"+id.getPath()):resources.open(id)){
            if(input==null)throw new IOException("Missing shader resource "+id);
            byte[] bytes=input.readNBytes(2*1024*1024+1);if(bytes.length>2*1024*1024)throw new IOException("Shader resource exceeds bound: "+id);
            return new String(bytes,StandardCharsets.UTF_8);
        }catch(IOException failure){throw new IllegalStateException("Cannot read certified shader resource "+id,failure);}
    }
    private void requireOpen(){if(closed)throw new IllegalStateException("Owned shader source is closed");}
    @Override public synchronized void close(){if(closed)return;for(CachedIncludeSource include:includes.values())include.close();includes.clear();closed=true;}
}
