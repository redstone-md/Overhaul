package dev.overhaul.integration;

import dev.overhaul.core.memory.DirectSlabPool;
import dev.overhaul.core.mesh.ChunkMesh;
import dev.overhaul.core.world.ChunkPipeline;
import dev.overhaul.core.world.ChunkSnapshot;
import dev.overhaul.core.world.MeshMailbox;
import dev.overhaul.vulkan.GpuMesh;
import dev.overhaul.vulkan.OffscreenRenderer;
import dev.overhaul.vulkan.VulkanContext;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Tag("native")
class ChunkGpuIntegrationTest {
    @Test void workerMeshSurvivesStagingRetirementAndDrawsThroughIndexedIndirectCommands() throws Exception {
        ChunkSnapshot snapshot = ChunkSnapshot.builder(2).revision(7)
                .set(0,0,0,1).emission(0,0,0,15).build();
        try (DirectSlabPool pool = new DirectSlabPool(64 * 1024, 2);
             ChunkPipeline chunks = new ChunkPipeline(2, 8, pool);
             MeshMailbox mailbox = new MeshMailbox();
             VulkanContext context = VulkanContext.openHeadless();
             OffscreenRenderer renderer = new OffscreenRenderer(context,128,128)) {
            mailbox.expect(7);
            mailbox.publish(chunks.submit(snapshot).get(10, TimeUnit.SECONDS));
            GpuMesh gpu;
            try (ChunkMesh mesh = mailbox.poll()) {
                assertNotNull(mesh);
                assertEquals(36, mesh.indexCount());
                gpu = renderer.upload(mesh.vertexBuffer(), mesh.indexBuffer(), mesh.indexCount());
            }
            // Upload completion makes the worker's slab immediately reusable.
            assertEquals(0, pool.activeLeases());
            try (DirectSlabPool.Lease recycled = pool.acquire(64 * 1024);
                 DirectSlabPool.Lease other = pool.acquire(64 * 1024)) {
                ByteBuffer data = recycled.buffer();
                while (data.hasRemaining()) data.put((byte) 0);
                data = other.buffer();
                while (data.hasRemaining()) data.put((byte) 0);
            }
            try (GpuMesh mesh = gpu) {
                float[] transform = {1.4f,0,0,0, 0,1.4f,0,0, 0,0,.6f,0, -.7f,-.7f,.2f,1};
                ByteBuffer first = renderer.render(mesh, transform);
                byte[] pixels = new byte[first.remaining()];
                first.get(pixels);
                ByteBuffer second = renderer.render(mesh, transform);
                byte[] repeated = new byte[second.remaining()];
                second.get(repeated);
                assertArrayEquals(pixels, repeated, "repeated submissions must preserve image layout and synchronization");
                int background = rgba(pixels,0), changed = 0;
                for (int i=0;i<pixels.length;i+=4) {
                    assertEquals(255, pixels[i+3] & 255);
                    if (rgba(pixels,i) != background) changed++;
                }
                assertTrue(changed > 4000 && changed < 12000, "projected voxel coverage: " + changed);
                assertNotEquals(background, rgba(pixels, (64*128+64)*4));
                assertTrue(renderer.pipelineCacheData().length > 0);
                Path directory = Path.of(System.getProperty("overhaul.verificationDir", "build/verification"));
                Files.createDirectories(directory);
                saveImage(directory.resolve("chunk-vulkan.png"), pixels,128,128);
                Files.writeString(directory.resolve("device.txt"), context.deviceName()+System.lineSeparator());
            }
        }
    }

    private static int rgba(byte[] bytes,int offset) {
        return ((bytes[offset]&255)<<24) | ((bytes[offset+1]&255)<<16)
                | ((bytes[offset+2]&255)<<8) | (bytes[offset+3]&255);
    }

    private static void saveImage(Path target,byte[] pixels,int width,int height) throws Exception {
        BufferedImage image = new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);
        for (int y=0;y<height;y++) for (int x=0;x<width;x++) {
            int offset=(y*width+x)*4;
            int argb=((pixels[offset+3]&255)<<24)|((pixels[offset]&255)<<16)
                    |((pixels[offset+1]&255)<<8)|(pixels[offset+2]&255);
            image.setRGB(x,y,argb);
        }
        assertTrue(ImageIO.write(image,"png",target.toFile()));
    }
}
