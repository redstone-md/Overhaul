package dev.overhaul.minecraft.backend;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.textures.GpuTexture;
import dev.overhaul.vulkan.VulkanContext;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;

@Tag("backend-native")
class ResourceLifetimeNativeTest {
    @Test void logicalCloseKeepsViewsAndMappingsAliveUntilTheirLastLeaseReturns() {
        var backend=device();
        try {
            var texture=(OverhaulTexture)backend.createTexture("owned",GpuTexture.USAGE_TEXTURE_BINDING,
                    GpuFormat.RGBA8_UNORM,4,4,1,1);
            var view=(OverhaulTextureView)backend.createTextureView(texture,0,1);
            long image=texture.image().handle();
            texture.close();
            assertTrue(texture.isClosed());
            assertEquals(image,view.nativeView().image().handle());
            assertThrows(IllegalStateException.class,()->backend.createTextureView(texture,0,1));
            view.close();
            assertThrows(IllegalStateException.class,texture::image);

            var buffer=(OverhaulBuffer)backend.createBuffer(()->"mapped",GpuBuffer.USAGE_MAP_READ|GpuBuffer.USAGE_MAP_WRITE,16);
            var mapping=buffer.map(0,16,true,true);
            buffer.close();
            assertTrue(buffer.isClosed());
            mapping.data().putInt(0,0x12345678);
            assertEquals(0x12345678,mapping.data().getInt(0));
            assertThrows(IllegalStateException.class,backend::close,"device cannot destroy a live mapped allocation");
            mapping.close(); mapping.close();
            backend.close(); backend.close();
        } finally { backend.close(); }
    }

    @Test void foreignResourcesAndWorkerNativeAccessFailWhileImmutableInfoIsReadable() {
        var first=device(); var second=device();
        try {
            var buffer=first.createBuffer(()->"foreign",GpuBuffer.USAGE_MAP_WRITE,8);
            assertThrows(IllegalArgumentException.class,()->second.buffer(buffer));
            assertThrows(IllegalArgumentException.class,()->second.retain(buffer));
            CompletableFuture.runAsync(()-> {
                assertEquals("Overhaul Vulkan",first.getDeviceInfo().backendName());
                assertThrows(IllegalStateException.class,first::context);
                assertThrows(IllegalStateException.class,buffer::close);
            }).join();
            buffer.close();
        } finally { second.close(); first.close(); }
    }

    private static OverhaulDeviceBackend device() {
        return new OverhaulDeviceBackend(VulkanContext.openHeadless(),new GpuDebugOptions(0,false,false,false));
    }
}
