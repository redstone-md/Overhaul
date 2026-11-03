package dev.overhaul.minecraft.backend;

import com.mojang.renderpearl.api.device.*;
import dev.overhaul.vulkan.SdlSurfaceProvider;
import dev.overhaul.vulkan.VulkanContext;
import static org.lwjgl.sdl.SDLError.SDL_GetError;
import static org.lwjgl.sdl.SDLVideo.*;
import static org.lwjgl.sdl.SDLVulkan.*;

/** Experimental game backend factory; SDL owns windows, Overhaul owns all Vulkan execution. */
public final class OverhaulBackend implements GpuBackend {
    private boolean loaded;
    @Override public String getName() { return "Overhaul Vulkan"; }
    @Override public void loadLibrary() throws BackendCreationException {
        if (loaded) return;
        if (!SDL_Vulkan_LoadLibrary((CharSequence)null)) throw new BackendCreationException(
                "SDL could not load Vulkan: "+SDL_GetError(),BackendCreationException.Reason.VULKAN_LOADER_MISSING);
        loaded=true;
    }
    @Override public void unloadLibrary() {
        if (loaded) { SDL_Vulkan_UnloadLibrary(); loaded=false; }
    }
    @Override public long createWindow(String title,int width,int height,long flags) {
        if (!loaded) throw new IllegalStateException("Load the Vulkan library before creating a window");
        return SDL_CreateWindow(title,width,height,(flags|SDL_WINDOW_VULKAN)&~SDL_WINDOW_OPENGL);
    }
    @Override public GpuDevice createDevice(GpuDebugOptions options) throws BackendCreationException {
        if (!loaded) throw new IllegalStateException("Load the Vulkan library before creating a device");
        try {
            var context=VulkanContext.openForPresentation(SdlSurfaceProvider.presentationExtensions(),
                    options.useValidationLayers()&&VulkanContext.validationAvailable());
            var backend=new OverhaulDeviceBackend(context,options);
            try { return new OverhaulGpuDevice(backend); }
            catch (RuntimeException|Error failure) { backend.close(); throw failure; }
        } catch (RuntimeException failure) {
            var error=new BackendCreationException("Overhaul device creation failed: "+failure.getMessage(),
                    BackendCreationException.Reason.OTHER);
            error.initCause(failure); throw error;
        }
    }
}
