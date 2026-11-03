package dev.overhaul.minecraft.model;

import com.mojang.renderpearl.api.pipeline.ShaderType;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

@Tag("backend-native")
class ModelVertexShaderSourceTest {
    @Test void allCertifiedOriginalProgramsKeepTheirWholeEntryPointBodiesAndConditionalIncludes()throws Exception{
        for(String program:new String[]{"entity","glint","rendertype_outline","rendertype_crumbling"}){
            Identifier original=Identifier.parse("minecraft:core/"+program);String text=resource("/assets/minecraft/shaders/core/"+program+".vsh");
            String adapted=ModelVertexShaderSource.adapt(original,text);
            assertTrue(adapted.startsWith("#version 450"));assertTrue(adapted.contains("#include <overhaul:model_quad_inputs.glsl>"));
            assertTrue(adapted.contains("void overhaulOriginalMain() {"));assertTrue(adapted.endsWith("void main() { overhaulDecodeModelQuad(); overhaulOriginalMain(); }\n"));
            String body=text.substring(text.indexOf("void main() {")+"void main() {".length());
            assertTrue(adapted.contains("void overhaulOriginalMain() {"+body),"The original vertex entry point must remain verbatim: "+program);
            for(String line:text.split("\\R"))if(line.startsWith("#include")||line.startsWith("#if")||line.startsWith("#else")||line.startsWith("#end"))assertTrue(adapted.contains(line));
            assertFalse(adapted.contains(" in vec3 Position;"));assertFalse(adapted.contains(" in vec2 UV0;"));
            assertEquals("overhaul:model_instanced/minecraft/core/"+program,ModelVertexShaderSource.adaptedVertex(original).toString());
        }
    }
    @Test void changedOrUnknownInputsCannotBypassThePinnedSourceCertificate()throws Exception{
        Identifier entity=Identifier.parse("minecraft:core/entity");String original=resource("/assets/minecraft/shaders/core/entity.vsh");
        assertThrows(IllegalArgumentException.class,()->ModelVertexShaderSource.adapt(entity,original.replace("in vec3 Normal","in vec4 Normal")));
        assertThrows(IllegalArgumentException.class,()->ModelVertexShaderSource.adapt(entity,original+"\nvoid main() {}\n"));
        assertThrows(UnsupportedOperationException.class,()->ModelVertexShaderSource.adapt(Identifier.parse("other:core/entity"),original));
        assertFalse(ModelVertexShaderSource.supports(Identifier.parse("minecraft:core/particle")));
    }
    @Test void sourceAdapterUsesActiveOriginalFragmentAndOwnedDecoderIncludes()throws Exception{
        ResourceManager resources=(ResourceManager)Proxy.newProxyInstance(ResourceManager.class.getClassLoader(),new Class<?>[]{ResourceManager.class},(proxy,method,args)->{
            if(!method.getName().equals("open"))throw new AssertionError(method.getName());Identifier id=(Identifier)args[0];
            InputStream input=ModelVertexShaderSourceTest.class.getResourceAsStream("/assets/"+id.getNamespace()+"/"+id.getPath());
            if(input==null)throw new java.io.IOException(id.toString());return input;
        });
        try(ModelVertexShaderSource source=new ModelVertexShaderSource(resources)){
            Identifier original=Identifier.parse("minecraft:core/entity");
            assertTrue(source.getShader(ModelVertexShaderSource.adaptedVertex(original),ShaderType.VERTEX).contains("overhaulDecodeModelQuad"));
            assertEquals(resource("/assets/minecraft/shaders/core/entity.fsh"),source.getShader(original,ShaderType.FRAGMENT));
            Identifier include=Identifier.parse("overhaul:model_quad_inputs.glsl");assertSame(source.getInclude(include),source.getInclude(include));
        }
    }
    private static String resource(String path)throws Exception{try(InputStream input=ModelVertexShaderSourceTest.class.getResourceAsStream(path)){assertNotNull(input,path);return new String(input.readAllBytes(),StandardCharsets.UTF_8);}}
}
