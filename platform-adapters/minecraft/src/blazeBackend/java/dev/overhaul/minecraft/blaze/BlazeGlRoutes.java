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
    private static final int DRAW_FRAMEBUFFER = 0x8CA9;

    private BlazeGlRoutes() {}

    private interface Route { void run(BlazeRenderer renderer, Arguments arguments); }

    static void install(Map<String, Answer> table, BlazeGlBuffers buffers) {
        state(table);
        textures(table, buffers);
        framebuffers(table);
    }

    /** Sodium and Veil present the main target with a raw blit, so an inert blit leaves the window black. Mods assemble their own framebuffers. */
    private static void framebuffers(Map<String, Answer> table) {
        put(table, "glBindFramebuffer", (renderer, a) -> renderer.bindFramebuffer(a.integer(0), a.integer(1)));
        put(table, "glBlitFramebuffer", (renderer, a) -> BlazeFramebufferBlit.copy(renderer, a.integer(0), a.integer(1), a.integer(2), a.integer(3),
                a.integer(4), a.integer(5), a.integer(6), a.integer(7), a.integer(8), a.integer(9)));
        put(table, "glDeleteFramebuffers", (renderer, a) -> each(a, address -> renderer.unregisterTarget(MemoryUtil.memGetInt(address))));
        put(table, "glFramebufferTexture2D", (renderer, a) -> {
            if (a.integer(2) == TEXTURE_2D) attach(renderer, renderer.boundFramebuffer(a.integer(0)), a.integer(1), a.integer(3), a.integer(4));
            else BlazeGlBoundary.reportOnce("raw cube attachment", "A mod attached a non-2D texture to a framebuffer; it receives no output");
        });
        put(table, "glFramebufferTexture", (renderer, a) -> attach(renderer, renderer.boundFramebuffer(a.integer(0)), a.integer(1), a.integer(2), a.integer(3)));
        put(table, "glNamedFramebufferTexture", (renderer, a) -> attach(renderer, a.integer(0), a.integer(1), a.integer(2), a.integer(3)));
        put(table, "glFramebufferRenderbuffer", (renderer, a) -> attachStorage(renderer, renderer.boundFramebuffer(a.integer(0)), a.integer(1), a.integer(3)));
        put(table, "glNamedFramebufferRenderbuffer", (renderer, a) -> attachStorage(renderer, a.integer(0), a.integer(1), a.integer(3)));
        put(table, "glBindRenderbuffer", (renderer, a) -> renderer.rawFramebuffers().bindRenderbuffer(a.integer(1)));
        put(table, "glDeleteRenderbuffers", (renderer, a) -> each(a, address -> renderer.rawFramebuffers().deleteRenderbuffer(MemoryUtil.memGetInt(address))));
        put(table, "glRenderbufferStorage", (renderer, a) -> renderer.rawFramebuffers().storage(0, a.integer(1), a.integer(2), a.integer(3), 0));
        put(table, "glNamedRenderbufferStorage", (renderer, a) -> renderer.rawFramebuffers().storage(a.integer(0), a.integer(1), a.integer(2), a.integer(3), 0));
        put(table, "glRenderbufferStorageMultisample", (renderer, a) -> renderer.rawFramebuffers().storage(0, a.integer(2), a.integer(3), a.integer(4), a.integer(1)));
        put(table, "glNamedRenderbufferStorageMultisample", (renderer, a) -> renderer.rawFramebuffers().storage(a.integer(0), a.integer(2), a.integer(3), a.integer(4), a.integer(1)));
        put(table, "glClear", (renderer, a) -> renderer.clear(a.integer(0)));
        put(table, "glClearColor", (renderer, a) -> renderer.drawState().clearColor(a.real(0), a.real(1), a.real(2), a.real(3)));
        put(table, "glClearDepth", (renderer, a) -> renderer.clearDepth(a.wide(0)));
        put(table, "glClearBufferfv", BlazeGlRoutes::clearBuffer);
        put(table, "glDrawBuffer", (renderer, a) -> drawBuffers(renderer, renderer.boundFramebuffer(DRAW_FRAMEBUFFER), new int[]{a.integer(0)}));
        put(table, "glDrawBuffers", (renderer, a) -> drawBuffers(renderer, renderer.boundFramebuffer(DRAW_FRAMEBUFFER), ints(a.pointer(1), a.integer(0))));
        put(table, "glNamedFramebufferDrawBuffer", (renderer, a) -> drawBuffers(renderer, a.integer(0), new int[]{a.integer(1)}));
        put(table, "glNamedFramebufferDrawBuffers", (renderer, a) -> drawBuffers(renderer, a.integer(0), ints(a.pointer(2), a.integer(1))));
    }

    /** Game framebuffers keep their own outputs; only a mod's framebuffer chooses which attachments its outputs write. */
    private static void drawBuffers(BlazeRenderer renderer, int framebuffer, int[] buffers) {
        if (framebuffer >= BlazeRenderer.FOREIGN_FRAMEBUFFERS) renderer.rawFramebuffers().drawBuffers(framebuffer, buffers);
    }

    private static int[] ints(long address, int count) {
        int[] values = new int[Math.max(count, 0)];
        for (int index = 0; index < values.length; index++) values[index] = MemoryUtil.memGetInt(address + index * (long) Integer.BYTES);
        return values;
    }

    private static void attach(BlazeRenderer renderer, int framebuffer, int attachment, int texture, int level) {
        if (framebuffer >= BlazeRenderer.FOREIGN_FRAMEBUFFERS) renderer.rawFramebuffers().attachTexture(framebuffer, attachment, texture, level);
        else reportRealTarget();
    }

    private static void attachStorage(BlazeRenderer renderer, int framebuffer, int attachment, int renderbuffer) {
        if (framebuffer >= BlazeRenderer.FOREIGN_FRAMEBUFFERS) renderer.rawFramebuffers().attachRenderbuffer(framebuffer, attachment, renderbuffer);
        else reportRealTarget();
    }

    private static void reportRealTarget() {
        BlazeGlBoundary.reportOnce("raw attachment on real target", "A mod changed the attachments of a game framebuffer; game framebuffers keep their own images");
    }

    /** glClearBufferfv ignores the clear values in state and clears one draw buffer. */
    private static void clearBuffer(BlazeRenderer renderer, Arguments a) {
        var kept = renderer.clearValues();
        long value = a.pointer(2);
        if (a.integer(0) == 0x1800) {
            renderer.clear(0x4000, new BlazeDrawState.Clear(MemoryUtil.memGetFloat(value), MemoryUtil.memGetFloat(value + 4), MemoryUtil.memGetFloat(value + 8), MemoryUtil.memGetFloat(value + 12), kept.depth()), a.integer(1));
        } else if (a.integer(0) == 0x1801) {
            renderer.clear(0x100, new BlazeDrawState.Clear(kept.r(), kept.g(), kept.b(), kept.a(), MemoryUtil.memGetFloat(value)));
        }
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
        put(table, "glDeleteTextures", (renderer, a) -> each(a, address -> {
            int name = MemoryUtil.memGetInt(address);
            renderer.texelBuffers().delete(name);
            renderer.textures().delete(name);
        }));
        put(table, "glActiveTexture", (renderer, a) -> renderer.textures().active(a.integer(0)));
        put(table, "glBindTexture", (renderer, a) -> {
            if (a.integer(0) == BlazeTexelBuffers.TARGET) renderer.texelBuffers().bind(activeUnit(renderer), a.integer(1));
            else if (BlazeTextureTargets.supported(a.integer(0))) renderer.textures().bind(a.integer(0), a.integer(1));
            else BlazeGlBoundary.reportOnce("texture target " + a.integer(0), "A mod bound a texture target with no owned storage: " + a.integer(0));
        });
        put(table, "glTexBuffer", (renderer, a) -> renderer.texelBuffers().define(activeUnit(renderer), a.integer(1), a.integer(2), 0, -1));
        put(table, "glTexBufferRange", (renderer, a) -> renderer.texelBuffers().define(activeUnit(renderer), a.integer(1), a.integer(2), a.pointer(3), a.pointer(4)));
        put(table, "glGenerateMipmap", (renderer, a) -> renderer.textures().generateMipmap(a.integer(0)));
        put(table, "glPixelStorei", (renderer, a) -> renderer.textures().pixelStore(a.integer(0), a.integer(1)));
        put(table, "glTexParameteri", (renderer, a) -> renderer.textures().parameter(a.integer(0), a.integer(1), a.integer(2)));
        put(table, "glTexImage2D", (renderer, a) -> {
            renderer.textures().image(a.integer(0), a.integer(1), a.integer(2), a.integer(3), a.integer(4), a.integer(5), a.integer(6), a.integer(7), null);
            long source = buffers.pixels(PIXEL_UNPACK_BUFFER, a.pointer(8));
            if (source != 0) renderer.textures().upload(a.integer(0), a.integer(1), 0, 0, a.integer(3), a.integer(4), a.integer(6), a.integer(7), source);
        });
        put(table, "glTexStorage2D", (renderer, a) -> renderer.textures().storage(a.integer(0), a.integer(1), a.integer(2), a.integer(3), a.integer(4)));
        put(table, "glTexImage3D", (renderer, a) -> {
            if (!layered(a.integer(0))) return;
            renderer.textures().image(a.integer(0), a.integer(1), a.integer(2), a.integer(3), a.integer(4), a.integer(5), a.integer(6), a.integer(7), a.integer(8));
            long source = buffers.pixels(PIXEL_UNPACK_BUFFER, a.pointer(9));
            if (source != 0) renderer.textures().upload(a.integer(0), a.integer(1), 0, 0, 0, a.integer(3), a.integer(4), a.integer(5), a.integer(7), a.integer(8), source);
        });
        put(table, "glTexStorage3D", (renderer, a) -> {
            if (layered(a.integer(0))) renderer.textures().storage(a.integer(0), a.integer(1), a.integer(2), a.integer(3), a.integer(4), a.integer(5));
        });
        put(table, "glTexSubImage3D", (renderer, a) -> {
            if (layered(a.integer(0))) renderer.textures().upload(a.integer(0), a.integer(1), a.integer(2), a.integer(3), a.integer(4), a.integer(5), a.integer(6), a.integer(7),
                    a.integer(8), a.integer(9), buffers.pixels(PIXEL_UNPACK_BUFFER, a.pointer(10)));
        });
        put(table, "glTexSubImage2D", (renderer, a) -> renderer.textures().upload(a.integer(0), a.integer(1), a.integer(2), a.integer(3), a.integer(4), a.integer(5), a.integer(6), a.integer(7), buffers.pixels(PIXEL_UNPACK_BUFFER, a.pointer(8))));
        put(table, "glGetTexImage", (renderer, a) -> renderer.textures().download(a.integer(0), a.integer(1), a.integer(2), a.integer(3), buffers.pixels(PIXEL_PACK_BUFFER, a.pointer(4))));
        put(table, "glGetTexLevelParameteriv", (renderer, a) -> MemoryUtil.memPutInt(a.pointer(3), renderer.textures().levelParameter(a.integer(0), a.integer(1), a.integer(2))));
        put(table, "glReadPixels", (renderer, a) -> renderer.textures().read(renderer.readTarget(), a.integer(0), a.integer(1), a.integer(2), a.integer(3), a.integer(4), a.integer(5), buffers.pixels(PIXEL_PACK_BUFFER, a.pointer(6))));
    }

    private static int activeUnit(BlazeRenderer renderer) { return renderer.textures().integer(0x84E0) - 0x84C0; }

    /** 3D image calls store 2D arrays and 3D textures; other targets have no owned storage for them. */
    private static boolean layered(int target) {
        if (BlazeTextureTargets.deep(target)) return true;
        BlazeGlBoundary.reportOnce("Deep texture " + target, "A mod specified a deep texture target with no owned storage: " + target);
        return false;
    }

    private static void each(Arguments arguments, LongConsumer action) {
        long base = arguments.pointer(1);
        for (int index = 0; index < arguments.integer(0); index++) action.accept(base + index * (long) Integer.BYTES);
    }
}
