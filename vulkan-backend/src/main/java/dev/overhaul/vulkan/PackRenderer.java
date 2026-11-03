package dev.overhaul.vulkan;

import dev.overhaul.shader.compat.UniformBlock;
import dev.overhaul.shader.pack.ShaderPackProgram;
import dev.overhaul.shader.pack.ShaderPackProperties;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Synchronous offscreen pack execution with named shader ABI, sampled textures and
 * applied blend state. This is a single-pass validation lane, not full pack/gameplay
 * integration. Unsupported pack state fails before native resources are allocated.
 */
public final class PackRenderer implements AutoCloseable {
    private final VulkanContext context;
    private final ShaderPackProgram program;
    private final Map<String,SampledTexture> textures=new LinkedHashMap<>();
    private GpuAllocator allocator;
    private ImmediateCommands commands;
    private ColorTarget target;
    private PackDescriptors descriptors;
    private GraphicsPipeline pipeline;
    private GpuBuffer readback;
    private boolean closed;

    public PackRenderer(VulkanContext context,int width,int height,ShaderPackProgram program,
                        ShaderPackProperties.ProgramState state,Map<String,PackTextureData> textureData) {
        this.context=context; this.program=program;
        context.requireOwner();
        GraphicsPipeline.Blend blend=PackPipelineState.validate(program,state);
        if (!textureData.keySet().equals(program.descriptors().samplers().keySet())) throw new IllegalArgumentException("Texture names must match shader bindings");
        if (textureData.size()>16) throw new IllegalArgumentException("Diagnostic pack renderer supports at most 16 samplers");
        PackVertexLayout layout=new PackVertexLayout(program.descriptors());
        try {
            allocator=new GpuAllocator(context); commands=new ImmediateCommands(context);
            target=new ColorTarget(context,width,height);
            for (var texture:textureData.entrySet()) textures.put(texture.getKey(),new SampledTexture(context,allocator,commands,texture.getValue()));
            descriptors=new PackDescriptors(context,allocator,program.descriptors(),textures);
            pipeline=new GraphicsPipeline(context,target.renderPass,descriptors.layout,width,height,program.vertex(),program.fragment(),
                    List.of(new GraphicsPipeline.Binding(0,layout.stride,VK_VERTEX_INPUT_RATE_VERTEX)),layout.attributes(),blend);
            readback=allocator.hostBuffer((long)width*height*4,VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        } catch (RuntimeException|Error failure) { close(); throw failure; }
    }

    /** Complete ABI matching prevents uploading a same-sized but differently packed UBO. */
    public ByteBuffer render(PackGeometry geometry,UniformBlock uniforms) {
        requireOpen();
        if (!program.descriptors().equals(geometry.bindings()) || !program.descriptors().equals(uniforms.bindings())) {
            throw new IllegalArgumentException("Geometry and uniforms must match this program's complete ABI");
        }
        descriptors.write(uniforms.snapshot());
        try (GpuBuffer vertices=allocator.hostBuffer(geometry.vertices().remaining(),VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);
             GpuBuffer indices=allocator.hostBuffer(geometry.indices().remaining(),VK_BUFFER_USAGE_INDEX_BUFFER_BIT);
             GpuBuffer indirect=allocator.hostBuffer(20,VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT)) {
            vertices.write(geometry.vertices()); indices.write(geometry.indices());
            ByteBuffer draw=ByteBuffer.allocate(20).order(ByteOrder.nativeOrder());
            draw.putInt(geometry.indexCount()).putInt(1).putInt(0).putInt(0).putInt(0).flip(); indirect.write(draw);
            commands.submit(command->{
                try (MemoryStack stack=MemoryStack.stackPush()) {
                    RenderPassCommands.begin(command,target,pipeline.pipeline,pipeline.layout,descriptors.set);
                    vkCmdBindVertexBuffers(command,0,stack.longs(vertices.handle()),stack.longs(0));
                    vkCmdBindIndexBuffer(command,indices.handle(),0,VK_INDEX_TYPE_UINT32);
                    vkCmdDrawIndexedIndirect(command,indirect.handle(),0,1,20);
                    vkCmdEndRenderPass(command); target.copyTo(command,readback);
                }
            });
            return readback.read();
        }
    }
    private void requireOpen() { context.requireOwner(); if (closed) throw new IllegalStateException("Pack renderer closed"); }
    @Override public void close() {
        if (closed) return;
        context.requireOwner(); context.waitIdle();
        if (readback!=null) readback.close();
        if (pipeline!=null) pipeline.close();
        if (descriptors!=null) descriptors.close();
        for (SampledTexture texture:textures.values()) texture.close(); textures.clear();
        if (target!=null) target.close();
        if (commands!=null) commands.close();
        if (allocator!=null) allocator.close();
        closed=true;
    }
}
