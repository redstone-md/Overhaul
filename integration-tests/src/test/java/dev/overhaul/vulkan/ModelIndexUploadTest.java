package dev.overhaul.vulkan;

import dev.overhaul.core.memory.DirectSlabPool;
import dev.overhaul.core.model.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

@Tag("native")
class ModelIndexUploadTest {
    private static final float[] IDENTITY={1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1};

    @Test void cameraSortingUploadsOnlyTranslucentIndicesWhileStaticLanesAndRenderedBlendingPersist(){
        ModelSnapshot snapshot=new ModelSnapshot(1,0,0,0,Arrays.asList(
            quad(RenderLayer.OPAQUE,3,4,.8f,0xff00ff00),quad(RenderLayer.CUTOUT,3,4,.7f,0xff00ffff),
            quad(RenderLayer.TRANSLUCENT,-.98f,.98f,-.2f,0x80ff0000),quad(RenderLayer.TRANSLUCENT,-.98f,.98f,.2f,0x800000ff)));
        var white=new PackTextureData(1,1,new byte[]{-1,-1,-1,-1},PackTextureData.Filter.NEAREST,PackTextureData.Wrap.CLAMP);
        try(DirectSlabPool pool=new DirectSlabPool(4096,1);VulkanContext context=VulkanContext.openHeadless(VulkanContext.validationAvailable());
            ModelRenderer renderer=new ModelRenderer(context,64,64,white,white)) {
            GpuModelMesh uploaded;int translucentOffset,translucentBytes,totalBytes;
            try(ModelMesh source=new ModelMesher().mesh(snapshot,pool)){
                translucentOffset=source.firstIndex(RenderLayer.TRANSLUCENT)*4;
                translucentBytes=source.indexCount(RenderLayer.TRANSLUCENT)*4;
                totalBytes=source.indexCount()*4;
                uploaded=renderer.upload(source);
            }
            assertEquals(0,pool.activeLeases());
            try(GpuModelMesh mesh=uploaded){
                long written=mesh.indices.hostWrittenBytes();assertEquals(totalBytes,written);
                byte[] prefix=bytes(mesh.indices.read(),0,translucentOffset);
                float[] negative=IDENTITY.clone();negative[10]=-.2f;
                ByteBuffer front=renderer.render(mesh,ModelCamera.openGl(negative,0,0,2));
                assertEquals(translucentBytes,mesh.indices.hostWrittenBytes()-written,"Camera move must upload only the translucent lane");
                written=mesh.indices.hostWrittenBytes();
                byte[] frontIndices=bytes(mesh.indices.read(),translucentOffset,translucentBytes);
                assertArrayEquals(prefix,bytes(mesh.indices.read(),0,translucentOffset));
                assertPixel(front,65,2,131);
                assertEquals(front,renderer.render(mesh,ModelCamera.openGl(negative,0,0,2)));
                assertEquals(written,mesh.indices.hostWrittenBytes(),"Stationary camera must not upload index data");

                float[] positive=IDENTITY.clone();positive[10]=.2f;
                ByteBuffer back=renderer.render(mesh,ModelCamera.openGl(positive,0,0,-2));
                assertEquals(translucentBytes,mesh.indices.hostWrittenBytes()-written);
                assertPixel(back,129,2,66);
                assertFalse(Arrays.equals(frontIndices,bytes(mesh.indices.read(),translucentOffset,translucentBytes)));
                assertArrayEquals(prefix,bytes(mesh.indices.read(),0,translucentOffset),"Opaque/cutout indices must remain untouched");
            }
            context.assertValidationClean();
        }
    }

    private static byte[] bytes(ByteBuffer buffer,int offset,int length){
        byte[] result=new byte[length];buffer.get(offset,result);return result;
    }
    private static void assertPixel(ByteBuffer pixels,int r,int g,int b){
        int center=(32*64+32)*4;
        assertEquals(r,pixels.get(center)&255,1);assertEquals(g,pixels.get(center+1)&255,1);assertEquals(b,pixels.get(center+2)&255,1);
    }
    private static ModelQuad quad(RenderLayer layer,float left,float right,float z,int argb){
        return new ModelQuad(layer,0,.5f,vertex(left,-.98f,z,argb),vertex(right,-.98f,z,argb),
            vertex(right,.98f,z,argb),vertex(left,.98f,z,argb));
    }
    private static ModelVertex vertex(float x,float y,float z,int argb){
        return new ModelVertex(x,y,z,.5f,.5f,ModelVertex.rgbaFromArgb(argb),.75f,.25f,0,0,1);
    }
}
