package dev.overhaul.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkInstance;
import java.nio.LongBuffer;
import java.util.List;
import static org.lwjgl.sdl.SDLError.SDL_GetError;
import static org.lwjgl.sdl.SDLVideo.*;
import static org.lwjgl.sdl.SDLVulkan.*;

/** Optional SDL3 binding. Borrows an SDL_WINDOW_VULKAN window and never initializes or quits SDL. */
public final class SdlSurfaceProvider implements SurfaceProvider {
    private final long window;

    public SdlSurfaceProvider(long window) {
        if (window == 0) throw new IllegalArgumentException("window is null");
        this.window = window;
    }

    @Override public List<String> requiredInstanceExtensions() {
        long flags = SDL_GetWindowFlags(window);
        if ((flags & SDL_WINDOW_VULKAN) == 0 || (flags & SDL_WINDOW_OPENGL) != 0)
            throw new IllegalArgumentException("SDL surface requires SDL_WINDOW_VULKAN without SDL_WINDOW_OPENGL");
        org.lwjgl.PointerBuffer extensions = SDL_Vulkan_GetInstanceExtensions();
        if (extensions == null) throw new IllegalStateException("SDL Vulkan extensions unavailable: " + SDL_GetError());
        return SurfaceExtensions.copy(extensions);
    }

    @Override public long createSurface(VkInstance instance) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer surface = stack.mallocLong(1);
            if (!SDL_Vulkan_CreateSurface(window, instance, null, surface))
                throw new IllegalStateException("SDL_Vulkan_CreateSurface failed: " + SDL_GetError());
            return surface.get(0);
        }
    }

    @Override public void destroySurface(VkInstance instance, long surface) {
        SDL_Vulkan_DestroySurface(instance, surface, null);
    }
}
