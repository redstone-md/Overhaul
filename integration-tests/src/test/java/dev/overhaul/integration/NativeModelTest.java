package dev.overhaul.integration;

import dev.overhaul.core.memory.DirectSlabPool;
import dev.overhaul.core.model.*;
import dev.overhaul.vulkan.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

@Tag("native")
class NativeModelTest {
    private static final int SIZE=64;
    private static final float[] IDENTITY={1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1};

    @Test void irregularBakedQuadPreservesAtlasTintLightAndCutoutHolesAfterPoolReuse() throws Exception {
        ModelQuad background=quad(RenderLayer.OPAQUE,.4f,0xff0000ff,.75f,.75f);
        ModelQuad irregular=new ModelQuad(RenderLayer.CUTOUT,99,.5f,
                vertex(-.9f,-.8f,-.4f,0,0,0xffcc6633),vertex(.8f,-.6f,-.4f,1,0,0xffcc6633),
                vertex(.6f,.8f,-.4f,1,1,0xffcc6633),vertex(-.7f,.5f,-.4f,0,1,0xffcc6633));
        var atlas=new PackTextureData(2,2,new byte[]{-1,-1,-1,0, -1,-1,-1,-1, -1,-1,-1,-1, -1,-1,-1,-1},PackTextureData.Filter.NEAREST,PackTextureData.Wrap.CLAMP);
        var light=new PackTextureData(2,2,new byte[]{0,0,0,-1, (byte)128,(byte)192,-1,-1, 0,0,0,-1, -1,-1,-1,-1},PackTextureData.Filter.NEAREST,PackTextureData.Wrap.CLAMP);
        try(DirectSlabPool pool=new DirectSlabPool(4096,1);VulkanContext context=VulkanContext.openHeadless(VulkanContext.validationAvailable());
            ModelRenderer renderer=new ModelRenderer(context,SIZE,SIZE,atlas,light)) {
            GpuModelMesh uploaded;
            try(ModelMesh mesh=new ModelMesher().mesh(new ModelSnapshot(71,0,0,0,Arrays.asList(irregular,background)),pool)){
                assertEquals(6,mesh.indexCount(RenderLayer.OPAQUE)); assertEquals(6,mesh.indexCount(RenderLayer.CUTOUT));
                uploaded=renderer.upload(mesh);
            }
            assertEquals(0,pool.activeLeases());
            try(DirectSlabPool.Lease recycled=pool.acquire(4096)){ByteBuffer data=recycled.buffer();while(data.hasRemaining())data.put((byte)0);}
            try(GpuModelMesh mesh=uploaded){
                ByteBuffer pixels=renderer.render(mesh,ModelCamera.openGl(IDENTITY,0,0,0));
                assertPixel(pixels,18,45,0,0,255,255); // Atlas alpha hole reveals opaque geometry.
                assertPixel(pixels,43,43,102,77,51,255); // Captured tint * selected lightmap texel.
                assertPixel(pixels,59,6,0,0,255,255); // Outside trapezoid: no invented cube face.
                save("model-cutout.png",pixels);
            }
            context.assertValidationClean();
        }
    }

    @Test void translucentIndexOrderChangesWithWorldCameraAndPreservesBackToFrontBlending() throws Exception {
        ModelSnapshot snapshot=new ModelSnapshot(4,0,0,0,Arrays.asList(
                quad(RenderLayer.TRANSLUCENT,-.2f,0x80ff0000,.5f,.5f),
                quad(RenderLayer.TRANSLUCENT,.2f,0x800000ff,.5f,.5f)));
        var white=new PackTextureData(1,1,new byte[]{-1,-1,-1,-1},PackTextureData.Filter.NEAREST,PackTextureData.Wrap.CLAMP);
        try(DirectSlabPool pool=new DirectSlabPool(4096,1);VulkanContext context=VulkanContext.openHeadless(VulkanContext.validationAvailable());
            ModelRenderer renderer=new ModelRenderer(context,SIZE,SIZE,white,white)) {
            GpuModelMesh uploaded;
            try(ModelMesh mesh=new ModelMesher().mesh(snapshot,pool)){uploaded=renderer.upload(mesh);}
            try(GpuModelMesh mesh=uploaded){
                float[] towardNegativeZ=IDENTITY.clone();towardNegativeZ[10]=-.2f;
                ByteBuffer front=renderer.render(mesh,ModelCamera.openGl(towardNegativeZ,0,0,2));
                assertPixel(front,32,32,65,2,131,255);save("model-translucent-front.png",front);
                float[] towardPositiveZ=IDENTITY.clone();towardPositiveZ[10]=.2f;
                ByteBuffer back=renderer.render(mesh,ModelCamera.openGl(towardPositiveZ,0,0,-2));
                assertPixel(back,32,32,129,2,66,255);save("model-translucent-back.png",back);
                assertFalse(Arrays.equals(toBytes(front),toBytes(back)),"Camera reversal must reorder the transparent index lane");
            }
            context.assertValidationClean();
        }
    }

    private static ModelQuad quad(RenderLayer layer,float z,int argb,float u,float v){
        return new ModelQuad(layer,0,.5f,vertex(-.98f,-.98f,z,u,v,argb),vertex(.98f,-.98f,z,u,v,argb),
                vertex(.98f,.98f,z,u,v,argb),vertex(-.98f,.98f,z,u,v,argb));
    }
    private static ModelVertex vertex(float x,float y,float z,float u,float v,int argb){
        return new ModelVertex(x,y,z,u,v,ModelVertex.rgbaFromArgb(argb),.75f,.25f,0,0,1);
    }
    private static byte[] toBytes(ByteBuffer pixels){byte[] data=new byte[pixels.remaining()];pixels.duplicate().get(data);return data;}
    private static void assertPixel(ByteBuffer pixels,int x,int y,int... expected){
        int offset=(y*SIZE+x)*4;
        for(int channel=0;channel<4;channel++)assertEquals(expected[channel],pixels.get(offset+channel)&255,1,"pixel("+x+","+y+") channel "+channel);
    }
    private static void save(String name,ByteBuffer pixels)throws Exception{
        Path directory=Path.of(System.getProperty("overhaul.verificationDir","build/verification"));Files.createDirectories(directory);
        BufferedImage image=new BufferedImage(SIZE,SIZE,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<SIZE;y++)for(int x=0;x<SIZE;x++){
            int p=(y*SIZE+x)*4;image.setRGB(x,y,((pixels.get(p+3)&255)<<24)|((pixels.get(p)&255)<<16)|((pixels.get(p+1)&255)<<8)|(pixels.get(p+2)&255));
        }
        assertTrue(ImageIO.write(image,"png",directory.resolve(name).toFile()));
    }
}
