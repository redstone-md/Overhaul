package dev.overhaul.minecraft.backend;

import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import dev.overhaul.vulkan.VulkanContext;
import org.joml.Vector4f;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.sdl.SDLError.SDL_GetError;
import static org.lwjgl.sdl.SDLEvents.SDL_PumpEvents;
import static org.lwjgl.sdl.SDLInit.*;
import static org.lwjgl.sdl.SDLVideo.*;

/** Exercises the public frontend API through our own deferred SDL/Vulkan device and swapchain. */
@Tag("backend-native")
class SurfaceNativeTest {
    @Test void deviceBeforeWindowAcquiresBlitsFencesPresentsAndResizesWithoutOpenGl() throws Exception {
        assertTrue(SDL_Init(SDL_INIT_VIDEO),SDL_GetError());
        OverhaulBackend factory=new OverhaulBackend();
        GpuDevice device=null;
        long window=0;
        try {
            factory.loadLibrary();
            device=factory.createDevice(new GpuDebugOptions(0,false,false,VulkanContext.validationAvailable()));
            // This order matches Minecraft26.3: device creation precedes creation of the native window.
            window=factory.createWindow("Overhaul native surface verification",64,64,SDL_WINDOW_HIDDEN|SDL_WINDOW_RESIZABLE);
            assertNotEquals(0,window,SDL_GetError());
            assertEquals(0,SDL_GetWindowFlags(window)&SDL_WINDOW_OPENGL);
            assertNotEquals(0,SDL_GetWindowFlags(window)&SDL_WINDOW_VULKAN);
            try (var surface=device.createSurface(window,()->false);
                 var color=device.createTexture("surface source",GpuTexture.USAGE_COPY_SRC|GpuTexture.USAGE_COPY_DST|GpuTexture.USAGE_RENDER_ATTACHMENT,
                         GpuFormat.RGBA8_UNORM,64,64,1,1);
                 var view=device.createTextureView(color)) {
                assertEquals(java.util.List.of(GpuSurface.PresentMode.FIFO),surface.supportedPresentModes());
                surface.configure(new GpuSurface.Configuration(64,64,GpuSurface.PresentMode.FIFO));
                var encoder=device.createCommandEncoder();
                encoder.clearColorTexture(color,new Vector4f(.2f,.4f,.8f,1));
                for (int frame=0;frame<4;frame++) {
                    surface.acquireNextTexture();
                    assertTrue(surface.isAcquired());
                    surface.blitFromTexture(encoder,view);
                    // The fence covers the actual asynchronous surface copy, too.
                    try (var fence=encoder.createFence()) { assertTrue(fence.awaitCompletion(-1L)); }
                    surface.present();
                    assertFalse(surface.isAcquired());
                    encoder.submit();
                }
                assertTrue(SDL_SetWindowSize(window,96,80),SDL_GetError());
                SDL_PumpEvents();
                surface.configure(new GpuSurface.Configuration(96,80,GpuSurface.PresentMode.FIFO));
                surface.acquireNextTexture();
                surface.blitFromTexture(encoder,view);
                surface.present();
                assertEquals(96,surface.currentConfiguration().orElseThrow().width());
                assertTrue(device.getLastDebugMessages().isEmpty(),device.getLastDebugMessages().toString());
            }
            device.close(); device=null;
            assertTrue(SDL_SetWindowTitle(window,"borrowed window remains alive"),SDL_GetError());
        } finally {
            if (device!=null) device.close();
            if (window!=0) SDL_DestroyWindow(window);
            factory.unloadLibrary(); SDL_Quit();
        }
    }
}
