package dev.overhaul.integration;

import dev.overhaul.shader.compat.ProgramBindings;
import dev.overhaul.shader.compat.UniformBlock;
import dev.overhaul.shader.compat.UnsupportedShaderException;
import dev.overhaul.shader.compile.ShadercCompiler;
import dev.overhaul.shader.pack.*;
import dev.overhaul.vulkan.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

@Tag("native")
class NativeShaderPackTest {
    private static final int SIZE=64;
    private static final float[] IDENTITY={1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1};

    @Test void unchangedIrisBasicProgramBindsLegacyInputsUniformsCoordinatesAndBlendState() throws Exception {
        try (var source=ShaderPackSource.open(fixture()); var compiler=new ShadercCompiler();
             var context=VulkanContext.openHeadless(VulkanContext.validationAvailable())) {
            var program=ShaderPackProgram.compile(source,"gbuffers_basic",compiler);
            report(context,"gbuffers_basic",program);
            var uniforms=uniforms(program.descriptors());
            var geometry=geometry(program.descriptors(),.25f,1);
            try (var renderer=new PackRenderer(context,SIZE,SIZE,program,ShaderPackProperties.parse("").program("gbuffers_basic",Map.of()),Map.of())) {
                ByteBuffer first=renderer.render(geometry,uniforms);
                assertPixel(first,32,32,153,77,38,255);
                // Original clip Z=-0.5 requires remapping to pass Vulkan's near plane;
                // the asymmetric triangle's lower-left sample proves Y conversion.
                assertPixel(first,12,54,153,77,38,255);
                assertPixel(first,12,10,5,8,10,255);
                save("iris-basic.png",first);
                uniforms.setFloat("blindness",.5f);
                assertPixel(renderer.render(geometry,uniforms),32,32,102,51,26,255);
            }
            var blended=ShaderPackProperties.parse("blend.gbuffers_basic=SRC_ALPHA ONE_MINUS_SRC_ALPHA ONE ZERO\n").program("gbuffers_basic",Map.of());
            uniforms.setFloat("blindness",0);
            try (var renderer=new PackRenderer(context,SIZE,SIZE,program,blended,Map.of())) {
                ByteBuffer pixels=renderer.render(geometry(program.descriptors(),.25f,.5f),uniforms);
                assertPixel(pixels,32,32,105,55,31,128);
                save("iris-basic-blended.png",pixels);
            }
            var alpha=ShaderPackProperties.parse("alphaTest.gbuffers_basic=GEQUAL 0.1").program("gbuffers_basic",Map.of());
            assertThrows(UnsupportedShaderException.class,()->new PackRenderer(context,SIZE,SIZE,program,alpha,Map.of()));
            var flip=ShaderPackProperties.parse("flip.gbuffers_basic.colortex0=false").program("gbuffers_basic",Map.of());
            assertThrows(UnsupportedShaderException.class,()->new PackRenderer(context,SIZE,SIZE,program,flip,Map.of()));
            context.assertValidationClean();
        }
    }

    @Test void unchangedIrisTexturedProgramSamplesAtlasAndLightmapThroughGeneratedDescriptors() throws Exception {
        try (var source=ShaderPackSource.open(fixture()); var compiler=new ShadercCompiler();
             var context=VulkanContext.openHeadless(VulkanContext.validationAvailable())) {
            var program=ShaderPackProgram.compile(source,"gbuffers_textured",compiler);
            report(context,"gbuffers_textured",program);
            var state=ShaderPackProperties.parse("").program("gbuffers_textured",Map.of());
            var atlas=new PackTextureData(2,2,new byte[]{(byte)128,64,32,(byte)255, 16,(byte)255,16,(byte)255,
                    16,16,(byte)255,(byte)255, (byte)255,(byte)255,(byte)255,(byte)255},PackTextureData.Filter.NEAREST,PackTextureData.Wrap.CLAMP);
            var lightmap=new PackTextureData(1,1,new byte[]{(byte)128,(byte)192,(byte)255,(byte)255},PackTextureData.Filter.NEAREST,PackTextureData.Wrap.CLAMP);
            try (var renderer=new PackRenderer(context,SIZE,SIZE,program,state,Map.of("texture",atlas,"lightmap",lightmap))) {
                var uniforms=uniforms(program.descriptors());
                ByteBuffer bottom=renderer.render(geometry(program.descriptors(),.25f,1),uniforms);
                assertPixel(bottom,32,32,39,14,5,255);
                save("iris-textured.png",bottom);
                ByteBuffer top=renderer.render(geometry(program.descriptors(),.75f,1),uniforms);
                assertPixel(top,32,32,5,4,38,255);
                save("iris-textured-toprow.png",top);
            }
            context.assertValidationClean();
        }
    }

    private static UniformBlock uniforms(ProgramBindings bindings) {
        UniformBlock result=new UniformBlock(bindings);
        for (var uniform:bindings.uniforms().entrySet()) {
            String name=uniform.getKey();
            switch (name) {
                case "gbufferModelView","gbufferModelViewInverse","overhaulModelView","overhaulProjection","overhaulTextureMatrix0","overhaulTextureMatrix1" -> result.setMatrix(name,4,IDENTITY);
                case "overhaulNormalMatrix" -> result.setMatrix(name,3,new float[]{1,0,0,0,1,0,0,0,1});
                case "overhaulFogColor" -> result.setVector(name,0,0,0,1);
                case "entityColor" -> result.setVector(name,0,0,0,0);
                case "blindness" -> result.setFloat(name,.25f);
                case "overhaulFogDensity" -> result.setFloat(name,0);
                case "overhaulFogStart" -> result.setFloat(name,10);
                case "overhaulFogScale" -> result.setFloat(name,.01f);
                case "isEyeInWater" -> result.setInt(name,0);
                default -> throw new AssertionError("Unexpected fixture uniform: "+name);
            }
        }
        return result;
    }

    private static PackGeometry geometry(ProgramBindings bindings,float textureV,float alpha) {
        Map<String,float[]> data=new LinkedHashMap<>();
        for (String name:bindings.attributes().keySet()) {
            float[] values=switch (name) {
                case "overhaulLegacyVertex" -> new float[]{-.8f,-.8f,-.5f,1, .8f,-.8f,-.5f,1, 0,.8f,-.5f,1};
                case "overhaulLegacyColor" -> repeat(.8f,.4f,.2f,alpha);
                case "overhaulLegacyNormal" -> repeat(0,1,0);
                case "overhaulLegacyTexCoord0" -> repeat(.25f,textureV,0,1);
                case "overhaulLegacyTexCoord1" -> repeat(.5f,.5f,0,1);
                case "mc_Entity" -> repeat(1);
                default -> throw new AssertionError("Unexpected fixture attribute: "+name);
            };
            data.put(name,values);
        }
        return new PackGeometry(bindings,3,data,new int[]{0,1,2});
    }
    private static float[] repeat(float... components) {
        float[] result=new float[3*components.length];
        for (int vertex=0;vertex<3;vertex++) System.arraycopy(components,0,result,vertex*components.length,components.length);
        return result;
    }
    private static void assertPixel(ByteBuffer pixels,int x,int y,int... expected) {
        int offset=(y*SIZE+x)*4;
        for (int channel=0;channel<4;channel++) assertEquals(expected[channel],pixels.get(offset+channel)&255,1,"pixel("+x+","+y+") channel "+channel);
    }
    private static Path fixture() throws Exception { return Path.of(NativeShaderPackTest.class.getResource("/packs/iris-example").toURI()); }
    private static void report(VulkanContext context,String name,ShaderPackProgram program) throws Exception {
        Path directory=Path.of(System.getProperty("overhaul.verificationDir","build/verification")); Files.createDirectories(directory);
        Files.writeString(directory.resolve("iris-"+name+".txt"),"device="+context.deviceName()+"\nvalidation="+VulkanContext.validationAvailable()
                +"\nvertexSpirvBytes="+program.vertex().byteSize()+"\nfragmentSpirvBytes="+program.fragment().byteSize()+"\ncoordinates="+program.coordinates()+"\n");
    }
    private static void save(String name,ByteBuffer pixels) throws Exception {
        Path directory=Path.of(System.getProperty("overhaul.verificationDir","build/verification")); Files.createDirectories(directory);
        BufferedImage image=new BufferedImage(SIZE,SIZE,BufferedImage.TYPE_INT_ARGB);
        for (int y=0;y<SIZE;y++) for (int x=0;x<SIZE;x++) {
            int p=(y*SIZE+x)*4;
            image.setRGB(x,y,((pixels.get(p+3)&255)<<24)|((pixels.get(p)&255)<<16)|((pixels.get(p+1)&255)<<8)|(pixels.get(p+2)&255));
        }
        assertTrue(ImageIO.write(image,"png",directory.resolve(name).toFile()));
    }
}
