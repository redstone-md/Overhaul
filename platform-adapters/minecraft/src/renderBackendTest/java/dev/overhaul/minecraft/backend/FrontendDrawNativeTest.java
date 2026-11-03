package dev.overhaul.minecraft.backend;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.overhaul.vulkan.VulkanContext;
import net.minecraft.resources.Identifier;
import org.joml.Vector4f;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises Minecraft's shader/frontend ABI through our native pipeline, pass and readback. */
@Tag("backend-native")
class FrontendDrawNativeTest {
    private static final int SIZE=32;
    @Test void frontendCompilesDrawsScissorsAndReadsOwnedVulkanImage() throws Exception {
        var backend=new OverhaulDeviceBackend(VulkanContext.openHeadless(),new GpuDebugOptions(0,false,false,false));
        var device=new FrontendGpuDevice(backend);
        try {
            RenderSystem.initRenderThread();
            RenderSystem.initRenderer(device);
            var specification=RenderPipeline.builder().withLocation(Identifier.parse("overhaul:native_triangle"))
                    .withVertexShader(Identifier.parse("overhaul:triangle")).withFragmentShader(Identifier.parse("overhaul:triangle"))
                    .withCull(false).withColorTargetState(ColorTargetState.DEFAULT)
                    .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withPushConstantSize(16).build();
            try (var source=new TriangleShaders()) {
                // Pending native creation returns to the device owner after frontend worker compilation.
                var pending=device.compilePipeline(specification,source,CompletableFuture.delayedExecutor(0,java.util.concurrent.TimeUnit.MILLISECONDS)).get();
                try (var pipeline=pending.finishCompile();
                     var target=device.createTexture("draw target",GpuTexture.USAGE_RENDER_ATTACHMENT|GpuTexture.USAGE_COPY_SRC,
                             GpuFormat.RGBA8_UNORM,SIZE,SIZE,1,1);
                     var view=device.createTextureView(target);
                     var readback=device.createBuffer(()->"draw readback",GpuBuffer.USAGE_MAP_READ|GpuBuffer.USAGE_COPY_DST,SIZE*SIZE*4)) {
                    var encoder=device.createCommandEncoder();
                    try (var pass=encoder.createRenderPass(()->"native triangle",view,Optional.of(new Vector4f(0,0,0,1)))) {
                        pass.setPipeline(pipeline);
                        var color=ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());
                        color.putFloat(1).putFloat(0).putFloat(0).putFloat(1).flip();
                        pass.pushConstants(color);
                        pass.enableScissor(8,8,16,16);
                        pass.draw(3,1,0,0);
                    }
                    encoder.copyTextureToBuffer(target,readback,0,()->{},0);
                    try (var mapped=readback.map(0,readback.size(),true,false)) {
                        var pixels=mapped.data();
                        assertEquals(255,pixels.get((16*SIZE+16)*4)&255,"triangle center must be red");
                        assertEquals(0,pixels.get((4*SIZE+4)*4)&255,"scissor must preserve clear outside");
                        assertEquals(255,pixels.get((16*SIZE+16)*4+3)&255);
                    }
                    encoder.submit();
                }
            }
        } finally {
            // RenderSystem owns the frontend after initRenderer. Its compiler callbacks
            // are not idempotently closeable; shutdown must be their single owner.
            RenderSystem.shutdownRenderer();
        }
    }

    private static final class TriangleShaders implements ShaderSource {
        @Override public String getShader(Identifier id,ShaderType type) {
            return type==ShaderType.VERTEX?"""
                    #version 450
                    void main() {
                        vec2 p[3]=vec2[](vec2(-1,-1),vec2(3,-1),vec2(-1,3));
                        gl_Position=vec4(p[gl_VertexIndex],0.5,1.0);
                    }
                    """:"""
                    #version 450
                    layout(push_constant) uniform Color { vec4 color; };
                    layout(location=0) out vec4 result;
                    void main() { result=color; }
                    """;
        }
        @Override public CachedIncludeSource getInclude(Identifier id) { throw new IllegalArgumentException("Unexpected shader include: "+id); }
        @Override public void close() {}
    }
}
