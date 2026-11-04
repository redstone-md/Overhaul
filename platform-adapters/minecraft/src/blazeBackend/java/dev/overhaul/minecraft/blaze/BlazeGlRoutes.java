package dev.overhaul.minecraft.blaze;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.overhaul.minecraft.blaze.BlazeGlBoundary.Answer;
import dev.overhaul.minecraft.blaze.BlazeGlBoundary.Arguments;
import java.util.Map;
import java.util.function.LongConsumer;
import org.lwjgl.system.MemoryUtil;

/** Routes the OpenGL state and texture entry points that mods call directly into the owned frontend. */
final class BlazeGlRoutes {
    private static final int TEXTURE_2D = 0x0DE1;
    private static final int PIXEL_PACK_BUFFER = 0x88EB;
    private static final int PIXEL_UNPACK_BUFFER = 0x88EC;

    private BlazeGlRoutes() {}

    private interface Route { void run(BlazeRenderer renderer, Arguments arguments); }

    static void install(Map<String, Answer> table, BlazeGlBuffers buffers) {
        state(table);
        textures(table, buffers);
        framebuffers(table);
    }

    /** Sodium and Veil present the main target with a raw blit, so an inert blit leaves the window black. */
    private static void framebuffers(Map<String, Answer> table) {
        put(table, "glBindFramebuffer", (renderer, a) -> renderer.bindFramebuffer(a.integer(0), a.integer(1)));
        put(table, "glBlitFramebuffer", (renderer, a) -> BlazeFramebufferBlit.copy(renderer, a.integer(0), a.integer(1), a.integer(2), a.integer(3),
                a.integer(4), a.integer(5), a.integer(6), a.integer(7), a.integer(8), a.integer(9)));
    }

    private static void put(Map<String, Answer> table, String name, Route route) {
        table.put(name, arguments -> {
            var renderer = BlazeFrontend.current();
            if (renderer != null && RenderSystem.isOnRenderThreadOrInit()) route.run(renderer, arguments);
            return 0;
        });
    }

    private static void state(Map<String, Answer> table) {
        put(table, "glEnable", (renderer, a) -> capability(renderer, a.integer(0), true));
        put(table, "glDisable", (renderer, a) -> capability(renderer, a.integer(0), false));
        put(table, "glScissor", (renderer, a) -> renderer.drawState().scissorBox(a.integer(0), a.integer(1), a.integer(2), a.integer(3)));
        put(table, "glViewport", (renderer, a) -> renderer.drawState().viewport(a.integer(0), a.integer(1), a.integer(2), a.integer(3)));
        put(table, "glDepthFunc", (renderer, a) -> renderer.drawState().depthFunction(a.integer(0)));
        put(table, "glDepthMask", (renderer, a) -> renderer.drawState().depthWrite(a.flag(0)));
        put(table, "glLogicOp", (renderer, a) -> renderer.drawState().logicOperation(a.integer(0)));
        put(table, "glColorMask", (renderer, a) -> renderer.drawState().colorMask(a.flag(0), a.flag(1), a.flag(2), a.flag(3)));
    }

    private static void capability(BlazeRenderer renderer, int capability, boolean enabled) {
        var state = renderer.drawState();
        switch (capability) {
            case 0x0C11 -> state.scissor(enabled);
            case 0x0BE2 -> state.blend(enabled);
            case 0x0B71 -> state.depth(enabled);
            case 0x0B44 -> state.cull(enabled);
            case 0x0BF2 -> state.logic(enabled);
            case 0x8037 -> state.polygonOffset(enabled);
            default -> { }
        }
    }

    private static void textures(Map<String, Answer> table, BlazeGlBuffers buffers) {
        put(table, "glGenTextures", (renderer, a) -> each(a, address -> MemoryUtil.memPutInt(address, renderer.textures().generate())));
        put(table, "glDeleteTextures", (renderer, a) -> each(a, address -> renderer.textures().delete(MemoryUtil.memGetInt(address))));
        put(table, "glActiveTexture", (renderer, a) -> renderer.textures().active(a.integer(0)));
        put(table, "glBindTexture", (renderer, a) -> { if (a.integer(0) == TEXTURE_2D) renderer.textures().bind(a.integer(1)); });
        put(table, "glPixelStorei", (renderer, a) -> renderer.textures().pixelStore(a.integer(0), a.integer(1)));
        put(table, "glTexParameteri", (renderer, a) -> renderer.textures().parameter(a.integer(0), a.integer(1), a.integer(2)));
        put(table, "glTexImage2D", (renderer, a) -> {
            renderer.textures().image(a.integer(0), a.integer(1), a.integer(2), a.integer(3), a.integer(4), a.integer(5), a.integer(6), a.integer(7), null);
            long source = buffers.pixels(PIXEL_UNPACK_BUFFER, a.pointer(8));
            if (source != 0) renderer.textures().upload(a.integer(0), a.integer(1), 0, 0, a.integer(3), a.integer(4), a.integer(6), a.integer(7), source);
        });
        put(table, "glTexSubImage2D", (renderer, a) -> renderer.textures().upload(a.integer(0), a.integer(1), a.integer(2), a.integer(3), a.integer(4), a.integer(5), a.integer(6), a.integer(7), buffers.pixels(PIXEL_UNPACK_BUFFER, a.pointer(8))));
        put(table, "glGetTexImage", (renderer, a) -> renderer.textures().download(a.integer(0), a.integer(1), a.integer(2), a.integer(3), buffers.pixels(PIXEL_PACK_BUFFER, a.pointer(4))));
        put(table, "glGetTexLevelParameteriv", (renderer, a) -> MemoryUtil.memPutInt(a.pointer(3), renderer.textures().levelParameter(a.integer(0), a.integer(1), a.integer(2))));
        put(table, "glReadPixels", (renderer, a) -> renderer.textures().read(renderer.readTarget(), a.integer(0), a.integer(1), a.integer(2), a.integer(3), a.integer(4), a.integer(5), buffers.pixels(PIXEL_PACK_BUFFER, a.pointer(6))));
    }

    private static void each(Arguments arguments, LongConsumer action) {
        long base = arguments.pointer(1);
        for (int index = 0; index < arguments.integer(0); index++) action.accept(base + index * (long) Integer.BYTES);
    }
}
