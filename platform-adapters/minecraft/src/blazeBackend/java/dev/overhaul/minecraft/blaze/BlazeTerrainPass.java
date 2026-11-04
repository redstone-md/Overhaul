package dev.overhaul.minecraft.blaze;

import dev.overhaul.shader.blaze.BlazeProgramCompiler;
import dev.overhaul.vulkan.FrameRing;
import dev.overhaul.vulkan.GpuBuffer;
import dev.overhaul.vulkan.GpuImageView;
import dev.overhaul.vulkan.NativeGraphicsPipeline;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import static org.lwjgl.vulkan.VK10.*;

/** One terrain layer: a single render pass whose regions each issue an indirect multi-draw. */
final class BlazeTerrainPass implements BlazeQueue.Operation {
    private static final int COMMAND_BYTES = 20, PUSH_STAGES = VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT;

    record Region(GpuBuffer vertices, GpuBuffer indices, float x, float y, float z, BlazeBufferPool.Slice commands, int count) {}

    interface Uniforms { void write(ByteBuffer target, float atlasWidth, float atlasHeight); }
    interface Pipelines { NativeGraphicsPipeline get(BlazeProgramCompiler.Program program, BlazeDrawState.Snapshot state, BlazeTarget target, long layout); }

    private final BlazeTarget target;
    private final BlazeDrawState.Snapshot state;
    private final boolean multiDraw;
    private final List<Region> regions = new ArrayList<>();
    private final List<BlazeTextures.Sampled> samples = new ArrayList<>();
    private final BlazeImageUse images = new BlazeImageUse();
    private BlazeBufferPool.Slice uniforms;
    private BlazeDescriptors.Lease descriptors;
    private NativeGraphicsPipeline pipeline;
    private boolean closed;

    BlazeTerrainPass(BlazeRenderer owner, BlazeTarget target, BlazeDrawState.Snapshot state, Uniforms writer, BlazeProgramCompiler.Program program, Pipelines pipelines) {
        this.target = target;
        this.state = state;
        multiDraw = owner.context().multiDrawIndirect();
        try {
            int atlas = 0;
            for (String name : program.bindings().samplers().keySet()) {
                // Sodium's own setup binds the atlas to unit 0 and the lightmap to unit 1 before the first draw.
                boolean block = name.equals("u_BlockTex");
                samples.add(owner.textures().resolveBound(block ? 0 : 1));
                if (block) atlas = samples.size() - 1;
            }
            var image = samples.get(atlas).view().image();
            uniforms = owner.hostStorage().acquire(program.bindings().uniformBytes());
            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer bytes = stack.calloc(uniforms.size());
                writer.write(bytes, image.width(0), image.height(0));
                bytes.clear();
                uniforms.write(bytes);
            }
            descriptors = owner.descriptors().acquire(program, uniforms, samples);
            pipeline = pipelines.get(program, state, target, descriptors.layout());
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    void add(Region region) { regions.add(region); }

    @Override public void record(VkCommandBuffer command) {
        int[] clip = BlazePass.clip(state, target);
        if (clip == null || regions.isEmpty()) return;
        for (BlazeTextures.Sampled sample : samples) {
            GpuImageView view = sample.view();
            images.transition(command, view, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }
        target.prepare(command, images);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            target.begin(command, stack);
            vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.handle());
            vkCmdBindDescriptorSets(command, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.layout(), 0, stack.longs(descriptors.set()), null);
            BlazePass.dynamicState(command, stack, state, clip);
            for (Region region : regions) draw(command, region);
            vkCmdEndRenderPass(command);
        }
    }

    private void draw(VkCommandBuffer command, Region region) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkCmdPushConstants(command, pipeline.layout(), PUSH_STAGES, 0, stack.floats(region.x(), region.y(), region.z(), 0));
            vkCmdBindVertexBuffers(command, 0, stack.longs(region.vertices().handle()), stack.longs(0));
            vkCmdBindIndexBuffer(command, region.indices().handle(), 0, VK_INDEX_TYPE_UINT32);
            long indirect = region.commands().buffer().handle();
            long offset = region.commands().offset();
            if (multiDraw) {
                vkCmdDrawIndexedIndirect(command, indirect, offset, region.count(), COMMAND_BYTES);
            } else {
                for (int index = 0; index < region.count(); index++) vkCmdDrawIndexedIndirect(command, indirect, offset + (long) index * COMMAND_BYTES, 1, COMMAND_BYTES);
            }
        }
    }

    @Override public void submitted(FrameRing.Submission submission) { images.submitted(submission); }

    @Override public void close() {
        if (closed) return;
        closed = true;
        try {
            images.close();
        } finally {
            for (Region region : regions) region.commands().close();
            regions.clear();
            if (descriptors != null) descriptors.close();
            if (uniforms != null) uniforms.close();
        }
    }
}
