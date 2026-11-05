package dev.overhaul.minecraft.blaze;

import dev.overhaul.shader.blaze.BlazeProgramCompiler;
import dev.overhaul.vulkan.GpuBuffer;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;
import static org.lwjgl.vulkan.VK10.*;

/**
 * One terrain layer as a single pass with one uniform capture and one indirect multi-draw per run of sections that
 * share buffers, each section's offset read per instance. Sections hold no pin: a replaced mesh retires through the
 * queue after this pass, so its fence covers the draw. Owner-thread only, and reused between layers.
 */
public final class BlazeSectionBatch implements AutoCloseable {
    private static final int COMMAND_BYTES = 20;

    private record Run(GpuBuffer vertices, int remainder, GpuBuffer indices, int indexBytes) {
        boolean holds(GpuBuffer vertices, int remainder, GpuBuffer indices, int indexBytes) {
            return this.vertices == vertices && this.remainder == remainder && this.indices == indices && this.indexBytes == indexBytes;
        }
    }

    private final BlazeRenderer owner;
    private final List<Run> runs = new ArrayList<>();
    private int[] sections = new int[4 * 1024];
    private float[] offsets = new float[3 * 1024];
    private int count;
    private BlazeTarget target;
    private BlazeShaderSnapshot shader;
    private BlazeProgramCompiler.Program compact;
    private BlazeDrawState.Snapshot state;
    private BlazeMeshState first;
    private boolean ordered;
    /** Written out of order and then copied whole, since the mapped memory behind a slice is slow to touch piecemeal. */
    private ByteBuffer commandBytes, positionBytes;

    BlazeSectionBatch(BlazeRenderer owner) {
        this.owner = owner;
    }

    /**
     * @param compact the shader's program for compact meshes, null when there is none and such meshes wait for their rebuild
     * @param ordered keep the given draw order exactly, as blending needs; otherwise sections group by buffer
     */
    void begin(BlazeShaderSnapshot shader, BlazeProgramCompiler.Program compact, boolean ordered) {
        target = owner.currentTarget();
        state = owner.drawState().snapshot();
        this.shader = shader;
        this.compact = compact;
        this.ordered = ordered;
    }

    public void add(BlazeMesh mesh, float x, float y, float z) {
        BlazeMeshState next = mesh.stateOrNull();
        if (next == null || next.indexCount() == 0 || compact == null && BlazeCompactSection.compact(next)) return;
        if (first != null && (next.topology() != first.topology() || !next.layout().equals(first.layout()))) finish();
        if (first == null) first = next;
        BlazeGeometryRange vertices = mesh.vertexRange(), indices = mesh.indexRange();
        int stride = next.layout().stride(), remainder = vertices.offset() % stride;
        if (count * 4 == sections.length) {
            sections = Arrays.copyOf(sections, sections.length * 2);
            offsets = Arrays.copyOf(offsets, offsets.length * 2);
        }
        int at = count * 4, position = count * 3;
        sections[at] = next.indexCount();
        sections[at + 1] = indices.offset() / next.indexBytes();
        sections[at + 2] = (vertices.offset() - remainder) / stride;
        sections[at + 3] = run(vertices.buffer(), remainder, indices.buffer(), next.indexBytes());
        offsets[position] = x;
        offsets[position + 1] = y;
        offsets[position + 2] = z;
        count++;
    }

    private int run(GpuBuffer vertices, int remainder, GpuBuffer indices, int indexBytes) {
        int last = runs.size() - 1;
        if (last >= 0 && runs.get(last).holds(vertices, remainder, indices, indexBytes)) return last;
        if (!ordered) for (int index = 0; index < last; index++) if (runs.get(index).holds(vertices, remainder, indices, indexBytes)) return index;
        runs.add(new Run(vertices, remainder, indices, indexBytes));
        return last + 1;
    }

    public void end() {
        finish();
        target = null;
        shader = null;
        compact = null;
        state = null;
    }

    private void finish() {
        BlazeMeshState mesh = first;
        first = null;
        try {
            if (count != 0) enqueue(mesh);
        } finally {
            count = 0;
            runs.clear();
        }
    }

    private void enqueue(BlazeMeshState mesh) {
        BlazeBufferPool.Slice commands = owner.hostStorage().acquire(count * COMMAND_BYTES), positions = null;
        Draw draw;
        try {
            positions = owner.hostStorage().acquire(count * BlazeSectionShape.OFFSET_BYTES);
            draw = write(commands, positions);
        } catch (RuntimeException | Error failure) {
            commands.close();
            if (positions != null) positions.close();
            throw failure;
        }
        BlazeShaderSnapshot captured = BlazeCompactSection.compact(mesh) ? shader.withProgram(compact) : shader;
        BlazeTerrainPass pass;
        try {
            pass = new BlazeTerrainPass(owner, target, state, (bytes, width, height) -> bytes.put(captured.uniforms()), captured.program(),
                    (program, snapshot, into, layout) -> owner.pipeline(program, mesh, snapshot, into, layout),
                    name -> owner.textures().resolve(captured.samplers().get(name)), "Sampler0");
        } catch (RuntimeException | Error failure) {
            draw.close();
            throw failure;
        }
        pass.add(draw);
        owner.enqueue(pass);
    }

    private Draw write(BlazeBufferPool.Slice commands, BlazeBufferPool.Slice positions) {
        int[] starts = new int[runs.size()], lengths = new int[runs.size()];
        for (int index = 0; index < count; index++) lengths[sections[index * 4 + 3]]++;
        for (int index = 1; index < starts.length; index++) starts[index] = starts[index - 1] + lengths[index - 1];
        int[] cursors = starts.clone();
        commandBytes = scratch(commandBytes, count * COMMAND_BYTES);
        positionBytes = scratch(positionBytes, count * BlazeSectionShape.OFFSET_BYTES);
        long commandStart = MemoryUtil.memAddress(commandBytes), positionStart = MemoryUtil.memAddress(positionBytes);
        for (int index = 0; index < count; index++) {
            int at = index * 4, place = index * 3, slot = cursors[sections[at + 3]]++;
            long command = commandStart + (long) slot * COMMAND_BYTES, position = positionStart + (long) slot * BlazeSectionShape.OFFSET_BYTES;
            MemoryUtil.memPutInt(command, sections[at]);
            MemoryUtil.memPutInt(command + 4, 1);
            MemoryUtil.memPutInt(command + 8, sections[at + 1]);
            MemoryUtil.memPutInt(command + 12, sections[at + 2]);
            MemoryUtil.memPutInt(command + 16, slot);
            MemoryUtil.memPutFloat(position, offsets[place]);
            MemoryUtil.memPutFloat(position + 4, offsets[place + 1]);
            MemoryUtil.memPutFloat(position + 8, offsets[place + 2]);
        }
        commands.write(commandBytes);
        positions.write(positionBytes);
        BlazeStats.sections(count, runs.size());
        return new Draw(runs.toArray(new Run[0]), starts, lengths, commands, positions);
    }

    private static ByteBuffer scratch(ByteBuffer held, int bytes) {
        ByteBuffer buffer = held == null || held.capacity() < bytes ? MemoryUtil.memRealloc(held, bytes + bytes / 2) : held;
        return buffer.clear().limit(bytes);
    }

    @Override public void close() {
        MemoryUtil.memFree(commandBytes);
        MemoryUtil.memFree(positionBytes);
        commandBytes = positionBytes = null;
    }

    private record Draw(Run[] runs, int[] starts, int[] lengths, BlazeBufferPool.Slice commands, BlazeBufferPool.Slice positions) implements BlazeTerrainPass.Draw {
        @Override public void record(VkCommandBuffer command, long pipelineLayout, boolean multiDraw) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var buffers = stack.mallocLong(2);
                var bindOffsets = stack.mallocLong(2);
                long indirect = commands.buffer().handle();
                buffers.put(1, positions.buffer().handle());
                bindOffsets.put(1, positions.offset());
                for (int index = 0; index < runs.length; index++) {
                    Run run = runs[index];
                    buffers.put(0, run.vertices.handle());
                    bindOffsets.put(0, run.remainder);
                    vkCmdBindVertexBuffers(command, 0, buffers, bindOffsets);
                    vkCmdBindIndexBuffer(command, run.indices.handle(), 0, run.indexBytes == 2 ? VK_INDEX_TYPE_UINT16 : VK_INDEX_TYPE_UINT32);
                    long offset = commands.offset() + (long) starts[index] * COMMAND_BYTES;
                    if (multiDraw) {
                        vkCmdDrawIndexedIndirect(command, indirect, offset, lengths[index], COMMAND_BYTES);
                    } else {
                        for (int draw = 0; draw < lengths[index]; draw++) vkCmdDrawIndexedIndirect(command, indirect, offset + (long) draw * COMMAND_BYTES, 1, COMMAND_BYTES);
                    }
                }
            }
        }

        @Override public void close() {
            commands.close();
            positions.close();
        }
    }
}
