package dev.overhaul.minecraft.blaze;

import dev.overhaul.vulkan.VulkanStack;

import dev.overhaul.shader.blaze.BlazeProgramCompiler;
import dev.overhaul.vulkan.GpuBuffer;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
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
    private static final boolean RUN_KEYS = BlazeOptimization.SECTION_RUN_KEYS.on();

    /** Unique across batches, so a run key stamped by another batch, or by this one before its last flush, reads as new. */
    private static final AtomicLong STAMPS = new AtomicLong();

    private final BlazeRenderer owner;
    private final List<BlazeSectionRun> runs = new ArrayList<>();
    private long stamp = STAMPS.incrementAndGet();
    /**
     * Run index plus one by all four run keys, open addressed, for keys meshes did not intern. Keyed by vertex buffer
     * alone, sections of one page with different remainders chained every run of that page.
     */
    private int[] slots = new int[1024];
    private int[] sections = new int[4 * 1024];
    private float[] offsets = new float[3 * 1024];
    private int count;
    private BlazeTarget target;
    private BlazeShaderSnapshot shader;
    private BlazeProgramCompiler.Program compact;
    private BlazeDrawState.Snapshot state;
    private BlazeMeshState first;
    private BlazeMeshState.Layout firstLayout;
    private int firstTopology;
    private boolean ordered;
    private Runnable failed;
    /** Tests this layer's sections on the GPU before they draw; null when the caller tested them or nothing may. */
    private BlazeSectionOcclusion cull;
    /** Written out of order and then copied whole, since the mapped memory behind a slice is slow to touch piecemeal. */
    private ByteBuffer commandBytes, positionBytes;

    BlazeSectionBatch(BlazeRenderer owner) {
        this.owner = owner;
    }

    /**
     * @param compact the shader's program for compact meshes, null when there is none and such meshes wait for their rebuild
     * @param ordered keep the given draw order exactly, as blending needs; otherwise sections group by buffer
     * @param failed told when the layer cannot draw, which then drops it; null lets the failure through
     */
    BlazeSectionBatch begin(BlazeShaderSnapshot shader, BlazeProgramCompiler.Program compact, boolean ordered, BlazeTarget target,
            BlazeDrawState.Snapshot state, Runnable failed) {
        this.target = target;
        this.state = state;
        this.shader = shader;
        this.compact = compact;
        this.ordered = ordered;
        this.failed = failed;
        cull = null;
        return this;
    }

    /** Lets the GPU drop this layer's sections that the occlusion's latest queued depth hides; until {@link #end}. */
    void cullBy(BlazeSectionOcclusion occlusion) {
        cull = occlusion;
    }

    public void add(BlazeMesh mesh, float x, float y, float z) {
        if (compact == null && BlazeCompactSection.compact(mesh.drawLayout)) return;
        append(mesh.stateOrNull(), mesh.drawLayout, mesh.drawTopology, mesh.drawIndexCount, mesh.drawFirstIndex, mesh.drawBaseVertex,
                mesh.drawRun, x, y, z);
    }

    /** A slot of a scan table that copied its mesh's draw fields; the mesh itself is read only to start a group. */
    public void add(BlazeVisibleSections.Layer table, int index, float x, float y, float z) {
        int indexCount = table.indexCount(index);
        if (indexCount == 0) return;
        BlazeMeshState.Layout layout = table.layout(index);
        if (compact == null && BlazeCompactSection.compact(layout)) return;
        int topology = table.topology(index);
        BlazeMeshState state = startsGroup(layout, topology) ? table.mesh(index).stateOrNull() : null;
        append(state, layout, topology, indexCount, table.firstIndex(index), table.baseVertex(index), table.run(index), x, y, z);
    }

    /** Whether a section with this layout and topology opens a new group, the only time its mesh state is read. */
    private boolean startsGroup(BlazeMeshState.Layout layout, int topology) {
        return first == null || topology != firstTopology || layout != firstLayout && !layout.equals(firstLayout);
    }

    /**
     * A mesh another renderer uploaded: whole buffers, indices from the start of theirs. Each such mesh owns its
     * buffers, so its key is not interned: an intern lookup per mesh costs more than the run it could never share.
     */
    void add(BlazeMeshState state, GpuBuffer vertices, GpuBuffer indices, int indexCount, float x, float y, float z) {
        if (indexCount == 0) return;
        append(state, state.layout(), state.topology(), indexCount, 0, 0, BlazeSectionRun.of(vertices, 0, indices, state.indexBytes(), false), x, y, z);
    }

    private void append(BlazeMeshState state, BlazeMeshState.Layout layout, int topology, int indexCount, int firstIndex, int baseVertex,
            BlazeSectionRun run, float x, float y, float z) {
        if (indexCount == 0) return;
        if (first != null && startsGroup(layout, topology)) finish();
        if (first == null) {
            first = state;
            firstLayout = layout;
            firstTopology = topology;
        }
        if (count * 4 == sections.length) {
            sections = Arrays.copyOf(sections, sections.length * 2);
            offsets = Arrays.copyOf(offsets, offsets.length * 2);
        }
        int at = count * 4, position = count * 3;
        sections[at] = indexCount;
        sections[at + 1] = firstIndex;
        sections[at + 2] = baseVertex;
        sections[at + 3] = run(run);
        offsets[position] = x;
        offsets[position + 1] = y;
        offsets[position + 2] = z;
        count++;
    }

    /** With shared keys the stamp alone answers, so the last run's buffers are not compared for every section. */
    private int run(BlazeSectionRun run) {
        if (!ordered && RUN_KEYS) {
            if (run.stamp == stamp) return run.index;
            int added = runs.size();
            run.stamp = stamp;
            run.index = added;
            runs.add(run);
            return added;
        }
        int last = runs.size() - 1;
        if (last >= 0 && runs.get(last).holds(run)) return last;
        int added = last + 1;
        if (ordered) {
            runs.add(run);
            return added;
        }
        return hashed(run, added);
    }

    private int hashed(BlazeSectionRun run, int added) {
        int mask = slots.length - 1, slot = run.hashCode() & mask;
        for (int held; (held = slots[slot]) != 0; slot = slot + 1 & mask) {
            if (runs.get(held - 1).holds(run)) return held - 1;
        }
        runs.add(run);
        slots[slot] = added + 1;
        if (runs.size() * 2 > slots.length) rehash();
        return added;
    }

    private void rehash() {
        slots = new int[slots.length * 2];
        int mask = slots.length - 1;
        for (int index = 0; index < runs.size(); index++) {
            int slot = runs.get(index).hashCode() & mask;
            while (slots[slot] != 0) slot = slot + 1 & mask;
            slots[slot] = index + 1;
        }
    }

    public void end() {
        finish();
        target = null;
        shader = null;
        compact = null;
        state = null;
        failed = null;
        cull = null;
    }

    private void finish() {
        BlazeMeshState mesh = first;
        first = null;
        firstLayout = null;
        try {
            if (count != 0) enqueue(mesh);
        } catch (RuntimeException failure) {
            if (failed == null) throw failure;
            BlazeGlBoundary.reportOnce("section batch " + failure.getMessage(), "A batched terrain layer was dropped and draws section by section from now on: " + failure);
            failed.run();
        } finally {
            count = 0;
            if (!ordered && !runs.isEmpty() && !RUN_KEYS) Arrays.fill(slots, 0);
            runs.clear();
            stamp = STAMPS.incrementAndGet();
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
        draw = draw.withConstants(owner.vertexConstants(captured.program()));
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
        if (cull != null) {
            try {
                cull.cull(draw.commands(), draw.positions(), count);
            } catch (RuntimeException | Error failure) {
                pass.close();
                throw failure;
            }
        }
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
        return new Draw(runs.toArray(new BlazeSectionRun[0]), starts, lengths, commands, positions, 0);
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

    /** @param constants the pack's fixed vertex inputs, bound third; 0 for a game program, which has none */
    private record Draw(BlazeSectionRun[] runs, int[] starts, int[] lengths, BlazeBufferPool.Slice commands, BlazeBufferPool.Slice positions, long constants) implements BlazeTerrainPass.Draw {
        Draw withConstants(long buffer) {
            return new Draw(runs, starts, lengths, commands, positions, buffer);
        }

        @Override public void record(VkCommandBuffer command, long pipelineLayout, boolean multiDraw) {
            try (MemoryStack stack = VulkanStack.push()) {
                int bound = constants == 0 ? 2 : 3;
                var buffers = stack.mallocLong(bound);
                var bindOffsets = stack.mallocLong(bound);
                long indirect = commands.buffer().handle();
                buffers.put(1, positions.buffer().handle());
                bindOffsets.put(1, positions.offset());
                if (constants != 0) {
                    buffers.put(2, constants);
                    bindOffsets.put(2, 0);
                }
                BlazeSectionRun previous = null;
                for (int index = 0; index < runs.length; index++) {
                    BlazeSectionRun run = runs[index];
                    buffers.put(0, run.vertices.handle());
                    bindOffsets.put(0, run.remainder);
                    if (previous == null) {
                        vkCmdBindVertexBuffers(command, 0, buffers, bindOffsets);
                    } else if (previous.vertices != run.vertices || previous.remainder != run.remainder) {
                        vkCmdBindVertexBuffers(command, 0, buffers.limit(1), bindOffsets.limit(1));
                    }
                    if (previous == null || previous.indices != run.indices || previous.indexBytes != run.indexBytes) {
                        vkCmdBindIndexBuffer(command, run.indices.handle(), 0, run.indexBytes == 2 ? VK_INDEX_TYPE_UINT16 : VK_INDEX_TYPE_UINT32);
                    }
                    previous = run;
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
