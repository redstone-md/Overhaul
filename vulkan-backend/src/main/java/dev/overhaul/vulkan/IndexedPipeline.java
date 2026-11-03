package dev.overhaul.vulkan;

import dev.overhaul.shader.compile.ShadercCompiler;
import dev.overhaul.shader.compile.ShaderStage;

import static org.lwjgl.vulkan.VK10.*;

/** Compact xyz/material vertex layout, descriptor camera, and driver-owned pipeline cache. */
final class IndexedPipeline implements AutoCloseable {
    private static final String VERTEX = """
        #version 450
        layout(location=0) in vec3 position;
        layout(location=1) in uint material;
        layout(location=2) in vec3 instanceOffset;
        layout(set=0,binding=0) uniform Camera { mat4 transform; } camera;
        layout(location=0) flat out uint surface;
        void main() { gl_Position=camera.transform*vec4(position+instanceOffset,1.0); surface=material; }
        """;
    private static final String FRAGMENT = """
        #version 450
        layout(location=0) flat in uint surface;
        layout(location=0) out vec4 color;
        void main() {
            uint m=surface & 65535u;
            color=vec4(0.2+float((m*73u)%181u)/255.0,
                       0.3+float((m*37u)%151u)/255.0,
                       0.2+float((m*19u)%181u)/255.0,1.0);
        }
        """;
    private final GraphicsPipeline shared;
    long pipeline;
    long layout;

    IndexedPipeline(VulkanContext context,long renderPass,long descriptorLayout,int width,int height) {
        try (ShadercCompiler compiler=new ShadercCompiler()) {
            shared=new GraphicsPipeline(context,renderPass,descriptorLayout,width,height,
                    compiler.compile("overhaul-builtin.vert",VERTEX,ShaderStage.VERTEX),
                    compiler.compile("overhaul-builtin.frag",FRAGMENT,ShaderStage.FRAGMENT),
                    java.util.List.of(new GraphicsPipeline.Binding(0,16,VK_VERTEX_INPUT_RATE_VERTEX),
                            new GraphicsPipeline.Binding(1,16,VK_VERTEX_INPUT_RATE_INSTANCE)),
                    java.util.List.of(new GraphicsPipeline.Attribute(0,0,VK_FORMAT_R32G32B32_SFLOAT,0),
                            new GraphicsPipeline.Attribute(1,0,VK_FORMAT_R32_UINT,12),
                            new GraphicsPipeline.Attribute(2,1,VK_FORMAT_R32G32B32_SFLOAT,0)),
                    GraphicsPipeline.Blend.disabled());
            pipeline=shared.pipeline; layout=shared.layout;
        }
    }
    byte[] cacheData() { return shared.cacheData(); }
    @Override public void close() { shared.close(); pipeline=layout=0; }
}
