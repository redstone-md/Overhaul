package dev.overhaul.minecraft.blaze;

import dev.overhaul.vulkan.VulkanStack;

import dev.overhaul.shader.blaze.BlazeProgramCompiler;
import dev.overhaul.vulkan.FrameRing;
import dev.overhaul.vulkan.GpuBuffer;
import dev.overhaul.vulkan.GpuImageView;
import dev.overhaul.vulkan.NativeGraphicsPipeline;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import static org.lwjgl.vulkan.VK10.*;

/** One native draw list: a single render pass, one pipeline and one descriptor set, whose draws each add their own geometry. */
final class BlazeTerrainPass implements BlazeQueue.Operation {
    static final int PUSH_STAGES = VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT;
    private static final int COMMAND_BYTES = 20;

    /** A recorded draw inside the pass; it owns whatever it allocated for the frame. */
    interface Draw {
        void record(VkCommandBuffer command, long pipelineLayout, boolean multiDraw);
        default void close() {}
    }

    /** One Sodium region: an indirect multi-draw over its own buffers. */
    record Region(GpuBuffer vertices, GpuBuffer indices, float x, float y, float z, BlazeBufferPool.Slice commands, int count) implements Draw {
        @Override public void record(VkCommandBuffer command, long pipelineLayout, boolean multiDraw) {
            bindGeometry(command, pipelineLayout, vertices, indices, x, y, z);
            long indirect = commands.buffer().handle();
            long offset = commands.offset();
            if (multiDraw) {
                vkCmdDrawIndexedIndirect(command, indirect, offset, count, COMMAND_BYTES);
            } else {
                for (int index = 0; index < count; index++) vkCmdDrawIndexedIndirect(command, indirect, offset + (long) index * COMMAND_BYTES, 1, COMMAND_BYTES);
            }
        }
        @Override public void close() { commands.close(); }
    }

    interface Uniforms { void write(ByteBuffer target, float atlasWidth, float atlasHeight); }
    interface Pipelines { NativeGraphicsPipeline get(BlazeProgramCompiler.Program program, BlazeDrawState.Snapshot state, BlazeTarget target, long layout); }

    private final BlazeTarget target;
    private final BlazeDrawState.Snapshot state;
    private final boolean multiDraw;
    private final List<Draw> draws = new ArrayList<>();
    private final List<BlazeTextures.Sampled> samples = new ArrayList<>();
    private final BlazeImageUse images = new BlazeImageUse();
    private BlazeBufferPool.Slice uniforms;
    private BlazeDescriptors.Lease descriptors;
    private NativeGraphicsPipeline pipeline;
    private boolean closed;

    /**
     * @param samplers the texture behind each sampler of the program
     * @param atlas the sampler whose size the uniform writer receives
     */
    BlazeTerrainPass(BlazeRenderer owner, BlazeTarget target, BlazeDrawState.Snapshot state, Uniforms writer, BlazeProgramCompiler.Program program,
            Pipelines pipelines, Function<String, BlazeTextures.Sampled> samplers, String atlas) {
        this.target = target;
        this.state = state;
        multiDraw = owner.context().multiDrawIndirect();
        try {
            BlazeTextures.Sampled sized = null;
            for (String name : program.bindings().samplers().keySet()) {
                BlazeTextures.Sampled sample = samplers.apply(name);
                samples.add(sample);
                if (name.equals(atlas)) sized = sample;
            }
            var image = sized == null ? samples.isEmpty() || samples.get(0).view() == null ? null : samples.get(0).view().image() : sized.view().image();
            uniforms = owner.hostStorage().acquire(program.bindings().uniformBytes());
            try (MemoryStack stack = VulkanStack.push()) {
                ByteBuffer bytes = stack.calloc(uniforms.size());
                writer.write(bytes, image == null ? 1 : image.width(0), image == null ? 1 : image.height(0));
                bytes.clear();
                uniforms.write(bytes);
            }
            descriptors = owner.descriptors().acquire(program, uniforms, uniforms.size(), samples);
            pipeline = pipelines.get(program, state, target, descriptors.layout());
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    void add(Draw draw) { draws.add(draw); }

    /** Binds indexed geometry and the offset every native terrain vertex shader takes as its push constant. */
    static void bindGeometry(VkCommandBuffer command, long pipelineLayout, GpuBuffer vertices, GpuBuffer indices, float x, float y, float z) {
        try (MemoryStack stack = VulkanStack.push()) {
            vkCmdPushConstants(command, pipelineLayout, PUSH_STAGES, 0, stack.floats(x, y, z, 0));
            vkCmdBindVertexBuffers(command, 0, stack.longs(vertices.handle()), stack.longs(0));
            vkCmdBindIndexBuffer(command, indices.handle(), 0, VK_INDEX_TYPE_UINT32);
        }
    }

    @Override public void record(VkCommandBuffer command) {
        int[] clip = BlazePass.clip(state, target);
        if (clip == null || draws.isEmpty()) return;
        for (BlazeTextures.Sampled sample : samples) {
            if (sample.general()) continue;
            GpuImageView view = sample.view();
            images.transition(command, view, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }
        target.prepare(command, images);
        try (MemoryStack stack = VulkanStack.push()) {
            target.begin(command, stack);
            vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.handle());
            vkCmdBindDescriptorSets(command, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.layout(), 0, stack.longs(descriptors.set()), stack.ints(0));
            BlazePass.dynamicState(command, stack, state, clip);
            for (Draw draw : draws) draw.record(command, pipeline.layout(), multiDraw);
            vkCmdEndRenderPass(command);
        }
    }

    @Override public void submitted(FrameRing.Submission submission) { images.submitted(submission); }

    @Override public void close() {
        if (closed) return;
        closed = true;
        try {
            images.close();
        } finally {
            for (Draw draw : draws) draw.close();
            draws.clear();
            if (descriptors != null) descriptors.close();
            if (uniforms != null) uniforms.close();
        }
    }
}
