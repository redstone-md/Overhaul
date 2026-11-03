package dev.overhaul.minecraft.smoke;

import dev.overhaul.adapter.SectionKey;
import dev.overhaul.core.concurrent.WorkerScheduler;
import dev.overhaul.core.memory.DirectSlabPool;
import dev.overhaul.core.model.ModelMesh;
import dev.overhaul.core.model.ModelMesher;
import dev.overhaul.minecraft.MinecraftModelCapture;
import dev.overhaul.vulkan.*;
import net.minecraft.client.Minecraft;
import net.minecraft.data.AtlasIds;
import org.joml.Matrix4f;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/** Real game assets -> immutable snapshot -> bounded worker -> independent Vulkan draw. */
public final class NativeModelProbe implements WorldSmokeProbe {
    private final WorkerScheduler workers=new WorkerScheduler(1,4,"overhaul-model-smoke");

    @Override public CompletableFuture<String> capture(Minecraft minecraft,SectionKey section) {
        MinecraftModelCapture.Capture capture=new MinecraftModelCapture(minecraft).capture(section,1);
        if (capture.cutoutQuads()==0 || capture.translucentQuads()==0 || capture.nonCubeQuads()==0) {
            throw new IllegalStateException("Live model fixture did not produce cutout, translucent and non-cube geometry");
        }
        var atlas=minecraft.getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS).getTexture();
        var lightmap=minecraft.gameRenderer.levelLightmap().texture();
        var atlasCopy=MinecraftTextureReadback.capture(atlas,PackTextureData.Filter.NEAREST);
        var lightCopy=MinecraftTextureReadback.capture(lightmap,PackTextureData.Filter.LINEAR);
        Path output=minecraft.gameDirectory.toPath().resolve("overhaul-model-smoke");
        Logger.getLogger("overhaul").info("Overhaul live model capture: quads="+capture.snapshot().quads().size()
                +" cutout="+capture.cutoutQuads()+" translucent="+capture.translucentQuads()+" nonCube="+capture.nonCubeQuads());
        return atlasCopy.thenCombine(lightCopy,Assets::new).thenCompose(assets->workers.submit(()->render(capture,assets,output)));
    }

    private String render(MinecraftModelCapture.Capture capture,Assets assets,Path output) throws IOException {
        Files.createDirectories(output);
        try (DirectSlabPool pool=new DirectSlabPool(16*1024*1024,1);
             VulkanContext context=VulkanContext.openHeadless();
             ModelRenderer renderer=new ModelRenderer(context,512,512,assets.atlas,assets.lightmap)) {
            GpuModelMesh gpu;
            int indices;
            try (ModelMesh mesh=new ModelMesher().mesh(capture.snapshot(),pool)) {
                indices=mesh.indexCount();
                gpu=renderer.upload(mesh);
            }
            if (pool.activeLeases()!=0) throw new IllegalStateException("Model staging retained a source mesh lease");
            try (var overwrite=pool.acquire(pool.slabBytes())) {
                ByteBuffer bytes=overwrite.buffer();
                while (bytes.hasRemaining()) bytes.put((byte)0);
            }
            try (gpu) {
                float[] rotationProjection=new Matrix4f().perspective((float)Math.toRadians(55),1f,.1f,100f)
                        .lookAt(0,0,0,0,-9,-19,0,1,0).get(new float[16]);
                ModelCamera camera=ModelCamera.openGl(rotationProjection,8,13,23);
                ByteBuffer pixels=renderer.render(gpu,camera);
                byte[] first=new byte[pixels.remaining()]; pixels.get(first);
                ByteBuffer again=renderer.render(gpu,camera);
                for (int i=0;i<first.length;i++) if (first[i]!=again.get(i)) {
                    throw new IllegalStateException("Repeated live-model draw changed at byte "+i);
                }
                int changed=0;
                for (int i=0;i<first.length;i+=4) if (!samePixel(first,0,i)) changed++;
                if (changed<10_000 || changed>250_000) throw new IllegalStateException("Unexpected live-model coverage: "+changed);
                save(output.resolve("world-model-vulkan.png"),first,512,512);
                save(output.resolve("actual-block-atlas.png"),assets.atlas.rgba(),assets.atlas.width(),assets.atlas.height());
                save(output.resolve("actual-lightmap.png"),assets.lightmap.rgba(),assets.lightmap.width(),assets.lightmap.height());
                String evidence="source=ClientLevel,bakedModels=true,quads="+capture.snapshot().quads().size()
                        +",cutout="+capture.cutoutQuads()+",translucent="+capture.translucentQuads()
                        +",nonCube="+capture.nonCubeQuads()+",indices="+indices+",coveredPixels="+changed
                        +",worker="+Thread.currentThread().getName()+",VulkanDevice="+context.deviceName()
                        +",sourceLeases="+pool.activeLeases()+",atlas="+assets.atlas.width()+"x"+assets.atlas.height()
                        +",lightmap="+assets.lightmap.width()+"x"+assets.lightmap.height()
                        +",validationLayers=false,gameRendererReplaced=false";
                Files.writeString(output.resolve("evidence.txt"),evidence+System.lineSeparator());
                return evidence;
            }
        }
    }

    private static boolean samePixel(byte[] bytes,int a,int b) {
        for (int channel=0;channel<4;channel++) if (bytes[a+channel]!=bytes[b+channel]) return false;
        return true;
    }

    private static void save(Path path,byte[] rgba,int width,int height) throws IOException {
        BufferedImage image=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);
        for (int y=0;y<height;y++) for (int x=0;x<width;x++) {
            int offset=(y*width+x)*4;
            image.setRGB(x,y,((rgba[offset+3]&255)<<24)|((rgba[offset]&255)<<16)
                    |((rgba[offset+1]&255)<<8)|(rgba[offset+2]&255));
        }
        if (!ImageIO.write(image,"png",path.toFile())) throw new IOException("No PNG encoder");
    }

    @Override public void close() { workers.close(); }
    private record Assets(PackTextureData atlas,PackTextureData lightmap) {}
}
