package dev.overhaul.minecraft.shader;

import com.mojang.renderpearl.api.pipeline.ShaderType;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@Tag("backend-native")
class OwnedVertexShaderSourceTest {
    @Test void packagedKernelsAndIncludesCannotBeReplacedByTheActiveResourcePack(){
        AtomicInteger activeReads=new AtomicInteger();
        ResourceManager resources=(ResourceManager)Proxy.newProxyInstance(ResourceManager.class.getClassLoader(),new Class<?>[]{ResourceManager.class},(proxy,method,arguments)->{
            if(method.getName().equals("open")){
                Identifier id=(Identifier)arguments[0];assertNotEquals("overhaul",id.getNamespace(),"Owned code must bypass pack lookup");activeReads.incrementAndGet();
                return new ByteArrayInputStream("certified active fragment".getBytes(StandardCharsets.UTF_8));
            }
            throw new AssertionError("Unexpected resource operation "+method.getName());
        });
        OwnedVertexShaderSource source=new OwnedVertexShaderSource(resources);
        try{
            String particle=source.getShader(Identifier.parse("overhaul:core/particle_instanced"),ShaderType.VERTEX);
            assertTrue(particle.contains("rotateCorner"));
            Identifier include=Identifier.parse("overhaul:instanced_particle_vertex.glsl");assertSame(source.getInclude(include),source.getInclude(include));
            assertEquals(0,activeReads.get());
            assertEquals("certified active fragment",source.getShader(Identifier.parse("minecraft:core/particle"),ShaderType.FRAGMENT));assertEquals(1,activeReads.get());
        }finally{source.close();}
        assertThrows(IllegalStateException.class,()->source.getInclude(Identifier.parse("overhaul:instanced_particle_vertex.glsl")));
        assertThrows(IllegalStateException.class,()->source.getShader(Identifier.parse("overhaul:core/weather_instanced"),ShaderType.VERTEX));
    }
}
