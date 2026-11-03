package dev.overhaul.vulkan.sdl;

import dev.overhaul.vulkan.*;
import org.lwjgl.Version;
import org.lwjgl.system.MemoryStack;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import static org.lwjgl.sdl.SDLError.SDL_GetError;
import static org.lwjgl.sdl.SDLEvents.SDL_PumpEvents;
import static org.lwjgl.sdl.SDLInit.*;
import static org.lwjgl.sdl.SDLVideo.*;

/** Runs with matching LWJGL3.4.3 libraries only; SDL owns a real Vulkan-capable window. */
public final class SdlVulkanSmoke {
    private SdlVulkanSmoke() { }

    public static void main(String[] args) {
        if (!Version.getVersion().startsWith("3.4.3")) throw new IllegalStateException("SDL smoke requires isolated LWJGL3.4.3");
        if (SdlVulkanSmoke.class.getClassLoader().getResource("org/lwjgl/glfw/GLFW.class") != null)
            throw new IllegalStateException("SDL smoke classpath unexpectedly contains GLFW");
        if (!SDL_Init(SDL_INIT_VIDEO)) throw new IllegalStateException("SDL video init failed: " + SDL_GetError());
        long window = 0;
        try {
            window = SDL_CreateWindow("overhaul SDL Vulkan verification", 128, 128,
                SDL_WINDOW_VULKAN | SDL_WINDOW_HIDDEN | SDL_WINDOW_RESIZABLE);
            if (window == 0) throw new IllegalStateException("SDL Vulkan window failed: " + SDL_GetError());
            SdlSurfaceProvider provider = new SdlSurfaceProvider(window);
            VulkanContext context = VulkanContext.openWindowed(provider);
            try (context; SwapchainPresenter presenter = new SwapchainPresenter(context, 128, 128)) {
                verifyDraws(context, presenter, window);
                System.out.println("SDL3 Vulkan device: " + context.deviceName() + "; LWJGL=" + Version.getVersion());
            }
            context.assertValidationClean();
            // Context/provider own the surface only; the caller's window is still valid afterwards.
            if ((SDL_GetWindowFlags(window) & SDL_WINDOW_VULKAN) == 0 || !SDL_SetWindowTitle(window, "surface released"))
                throw new IllegalStateException("context destroyed its borrowed SDL window");
            verifyRejectsIncompatibleWindow();
            System.out.println("SDL3 native surface: borrowed ownership and no-OpenGL/no-GLFW isolation verified");
        } finally {
            if (window != 0) SDL_DestroyWindow(window);
            SDL_Quit();
        }
    }

    private static void verifyDraws(VulkanContext context, SwapchainPresenter presenter, long window) {
        ByteBuffer vertices = ByteBuffer.allocate(48).order(ByteOrder.nativeOrder());
        vertex(vertices, -.2f, -.3f); vertex(vertices, .2f, -.3f); vertex(vertices, 0, .3f);
        vertices.flip();
        ByteBuffer indices = ByteBuffer.allocate(12).order(ByteOrder.nativeOrder()).putInt(0).putInt(1).putInt(2).flip();
        float[] camera = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
        try (AsyncRenderer renderer = new AsyncRenderer(context, 128, 128, 3);
             MeshBatch packet = MeshBatch.builder().add(vertices, indices, 3,
                 new float[] {-.5f, 0, 0, .5f, 0, 0}).build();
             GpuBatch mesh = renderer.uploadBatch(packet)) {
            ByteBuffer rgba = renderer.submitForReadback(mesh, camera).pixels();
            if (Byte.toUnsignedInt(rgba.get((64 * 128 + 32) * 4)) <= 20
                || Byte.toUnsignedInt(rgba.get((64 * 128 + 96) * 4)) <= 20)
                throw new IllegalStateException("SDL runtime ABI did not render expected instanced pixels");
            presentFrames(renderer, mesh, camera, presenter, 35);
            if (!SDL_SetWindowSize(window, 192, 160)) throw new IllegalStateException("SDL resize failed: " + SDL_GetError());
            SDL_PumpEvents();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer width = stack.mallocInt(1), height = stack.mallocInt(1);
                if (!SDL_GetWindowSizeInPixels(window, width, height)) throw new IllegalStateException("SDL pixel dimensions unavailable: " + SDL_GetError());
                presenter.resize(width.get(0), height.get(0));
                presentFrames(renderer, mesh, camera, presenter, 17);
                System.out.println("SDL3 indexed/instanced readback + semaphore presentation: 35 frames +17 resized "
                    + presenter.width() + "x" + presenter.height());
            }
        }
    }

    private static void presentFrames(AsyncRenderer renderer, GpuBatch mesh, float[] camera, SwapchainPresenter presenter, int count) {
        for (int i = 0; i < count; i++) {
            SDL_PumpEvents();
            if (!renderer.submit(mesh, camera).presentTo(presenter)) throw new IllegalStateException("SDL geometry presentation failed");
        }
    }

    private static void verifyRejectsIncompatibleWindow() {
        long plain = SDL_CreateWindow("incompatible window", 16, 16, SDL_WINDOW_HIDDEN);
        if (plain == 0) throw new IllegalStateException("SDL test window failed: " + SDL_GetError());
        try {
            try {
                new SdlSurfaceProvider(plain).requiredInstanceExtensions();
                throw new IllegalStateException("non-Vulkan SDL window accepted");
            } catch (IllegalArgumentException expected) { }
        } finally { SDL_DestroyWindow(plain); }
    }

    private static void vertex(ByteBuffer target, float x, float y) { target.putFloat(x).putFloat(y).putFloat(.5f).putInt(3); }
}
