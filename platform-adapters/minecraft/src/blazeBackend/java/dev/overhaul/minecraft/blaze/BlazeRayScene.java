package dev.overhaul.minecraft.blaze;

import dev.overhaul.vulkan.FrameRing;
import dev.overhaul.vulkan.GpuBuffer;
import dev.overhaul.vulkan.VulkanStack;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ViewArea;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * The terrain near the camera as acceleration structures. Each section layer's mesh gets a bottom level structure,
 * built a few per frame, nearest first, and kept while its mesh holds the same vertex and index ranges; a mesh that
 * moved to new ranges leaves the scene until it is built again. The top level structure is built every frame over
 * camera-relative instances, with a table that tells a ray which vertices and indices it hit.
 */
final class BlazeRayScene implements AutoCloseable {
    static final int MASK_SOLID = 1, MASK_CUTOUT = 2, MASK_TRANSLUCENT = 4;
    private static final RenderType[] LAYERS = {RenderType.solid(), RenderType.cutoutMipped(), RenderType.cutout(), RenderType.translucent()};
    private static final int[] LAYER_IDS = new int[LAYERS.length];
    private static final int[] MASKS = {MASK_SOLID, MASK_CUTOUT, MASK_CUTOUT, MASK_TRANSLUCENT};
    private static final long TRACED;
    static {
        long traced = 0;
        for (int layer = 0; layer < LAYERS.length; layer++) traced |= 1L << (LAYER_IDS[layer] = BlazeChunkLayers.id(LAYERS[layer]));
        TRACED = traced;
    }
    private static final int BUILDS_PER_FRAME = 48, TRIANGLES_PER_FRAME = 400_000;
    private static final int INSTANCE_BYTES = 64, ENTRY_BYTES = 32, COMPACT_STRIDE = 20, FULL_STRIDE = 32;
    /** Frames a structure outside the traced distance is kept, so turning around or stepping back does not rebuild it. */
    private static final int KEEP_FRAMES = 600;
    private static final float COMPACT_SCALE = 65535f / 2048f;

    private final BlazeRenderer owner;
    private final BlazeRayStructures structures;
    private final IdentityHashMap<BlazeMesh, Blas> built = new IdentityHashMap<>();
    private final HostRing instanceBuffers, tableBuffers;
    private final List<Candidate> waiting = new ArrayList<>();
    private final List<Blas> placed = new ArrayList<>();
    private final List<float[]> offsets = new ArrayList<>();
    private BlazeRayStructures.Structure top;
    private int topCapacity, frame;

    BlazeRayScene(BlazeRenderer owner) {
        this.owner = owner;
        structures = new BlazeRayStructures(owner);
        int usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
        instanceBuffers = new HostRing(usage);
        tableBuffers = new HostRing(usage);
    }

    /** What one frame's rays trace: the top level structure, the hit table's address and how many instances it holds. */
    record Frame(BlazeRayStructures.Structure top, long table, int instances) {}

    /**
     * Gathers the sections within {@code distance} blocks of the camera, queues this frame's builds and returns what
     * the rays read, or null when nothing near holds geometry yet.
     */
    /**
     * Gathers the terrain within {@code distance} of the camera. {@code trailer} follows the hit table in the same
     * buffer, sixteen byte aligned, for the pass's own per-frame values.
     */
    Frame prepare(ViewArea area, double x, double y, double z, int distance, float[] trailer) {
        frame++;
        placed.clear();
        offsets.clear();
        waiting.clear();
        long reach = (long) (distance + 14) * (distance + 14);
        for (Object entry : BlazeViewSections.all(area)) {
            BlazeSectionBuffers section = (BlazeSectionBuffers) entry;
            long contents = section.overhaul$contents() & TRACED;
            if (contents == 0) continue;
            int originX = section.overhaul$originX(), originY = section.overhaul$originY(), originZ = section.overhaul$originZ();
            double dx = originX + 8 - x, dy = originY + 8 - y, dz = originZ + 8 - z, near = dx * dx + dy * dy + dz * dz;
            if (near > reach) continue;
            for (int layer = 0; layer < LAYERS.length; layer++) {
                if ((contents & 1L << LAYER_IDS[layer]) == 0) continue;
                BlazeMesh mesh = section.overhaul$mesh(LAYER_IDS[layer]);
                if (!traceable(mesh)) continue;
                float[] offset = offset(mesh, originX - x, originY - y, originZ - z);
                Blas blas = built.get(mesh);
                if (blas != null && !blas.holds(mesh)) {
                    built.remove(mesh);
                    owner.retire(blas);
                    blas = null;
                }
                if (blas == null) waiting.add(new Candidate(mesh, layer, near, offset));
                else place(blas, offset);
            }
        }
        List<Blas> builds = startBuilds();
        evict();
        return placed.isEmpty() ? null : queue(builds, trailer);
    }

    private boolean traceable(BlazeMesh mesh) {
        if (mesh == null || mesh.drawIndexCount < 3 || mesh.drawTopology != VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST || mesh.drawLayout == null) return false;
        if (mesh.drawIndexBytes != 2 && mesh.drawIndexBytes != 4 || mesh.drawVertices == null || mesh.drawIndices == null) return false;
        int stride = mesh.drawLayout.stride();
        return stride == FULL_STRIDE || stride == COMPACT_STRIDE && structures.compactVertices();
    }

    /** Scale, then the camera-relative translation, of a section's vertices. */
    private static float[] offset(BlazeMesh mesh, double x, double y, double z) {
        if (mesh.drawLayout.stride() == COMPACT_STRIDE) return new float[]{COMPACT_SCALE, (float) (x - 8), (float) (y - 8), (float) (z - 8)};
        return new float[]{1, (float) x, (float) y, (float) z};
    }

    private void place(Blas blas, float[] offset) {
        blas.used = frame;
        placed.add(blas);
        offsets.add(offset);
    }

    /** Creates the nearest waiting structures the frame's budget allows; their builds are recorded with the frame. */
    private List<Blas> startBuilds() {
        List<Blas> builds = new ArrayList<>();
        if (waiting.isEmpty()) return builds;
        waiting.sort((a, b) -> Double.compare(a.distance, b.distance));
        int triangles = 0;
        try (MemoryStack stack = VulkanStack.push()) {
            for (Candidate candidate : waiting) {
                if (builds.size() == BUILDS_PER_FRAME || triangles >= TRIANGLES_PER_FRAME) break;
                Blas blas = new Blas(candidate.mesh, MASKS[candidate.layer], stack);
                built.put(candidate.mesh, blas);
                builds.add(blas);
                triangles += blas.triangles;
                place(blas, candidate.offset);
            }
        }
        return builds;
    }

    private void evict() {
        if (frame % 60 != 0) return;
        var iterator = built.values().iterator();
        while (iterator.hasNext()) {
            Blas blas = iterator.next();
            if (frame - blas.used <= KEEP_FRAMES) continue;
            iterator.remove();
            owner.retire(blas);
        }
    }

    private Frame queue(List<Blas> builds, float[] trailer) {
        int count = placed.size();
        if (top == null || topCapacity < count) {
            if (top != null) owner.retire(top);
            top = null;
            topCapacity = Math.max(1024, count + count / 2);
            try (MemoryStack stack = VulkanStack.push()) {
                var sizes = topSizes(stack, 0);
                top = structures.create(VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR, sizes.accelerationStructureSize());
                topScratch = sizes.buildScratchSize();
            }
        }
        GpuBuffer instances = instanceBuffers.take(count * INSTANCE_BYTES), table = tableBuffers.take(count * ENTRY_BYTES + trailer.length * 4);
        try (var instanceRange = instances.mapRange(0, count * INSTANCE_BYTES, false, true);
             var tableRange = table.mapRange(0, count * ENTRY_BYTES + trailer.length * 4, false, true)) {
            ByteBuffer instanceData = instanceRange.buffer(), tableData = tableRange.buffer();
            for (int index = 0; index < count; index++) {
                Blas blas = placed.get(index);
                float[] offset = offsets.get(index);
                int at = index * INSTANCE_BYTES;
                for (int row = 0; row < 3; row++) {
                    for (int column = 0; column < 3; column++) instanceData.putFloat(at + (row * 4 + column) * 4, row == column ? offset[0] : 0);
                    instanceData.putFloat(at + (row * 4 + 3) * 4, offset[row + 1]);
                }
                instanceData.putInt(at + 48, index & 0xFFFFFF | blas.mask << 24);
                instanceData.putInt(at + 52, VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR << 24);
                instanceData.putLong(at + 56, blas.structure.address());
                int entry = index * ENTRY_BYTES;
                tableData.putLong(entry, blas.vertexAddress).putLong(entry + 8, blas.indexAddress)
                        .putInt(entry + 16, blas.indexBytes).putInt(entry + 20, blas.compact ? 1 : 0).putInt(entry + 24, blas.mask).putInt(entry + 28, 0);
            }
            for (int i = 0; i < trailer.length; i++) tableData.putFloat(count * ENTRY_BYTES + i * 4, trailer[i]);
        }
        owner.enqueue(new Build(builds, instances.deviceAddress(), count, top, topScratch));
        instanceBuffers.giveBack(instances);
        tableBuffers.giveBack(table);
        return new Frame(top, table.deviceAddress(), count);
    }

    private long topScratch;

    /** The sizes for a top level build over {@link #topCapacity} instances read from {@code instances}. */
    private VkAccelerationStructureBuildSizesInfoKHR topSizes(MemoryStack stack, long instances) {
        var geometry = topGeometry(stack, instances);
        var build = VkAccelerationStructureBuildGeometryInfoKHR.calloc(stack).sType$Default().type(VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR)
                .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_BUILD_BIT_KHR).mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                .geometryCount(1).pGeometries(geometry);
        return structures.sizes(build, new int[]{topCapacity}, stack);
    }

    private static VkAccelerationStructureGeometryKHR.Buffer topGeometry(MemoryStack stack, long instances) {
        var geometry = VkAccelerationStructureGeometryKHR.calloc(1, stack);
        geometry.get(0).sType$Default().geometryType(VK_GEOMETRY_TYPE_INSTANCES_KHR).flags(0);
        geometry.get(0).geometry().instances().sType$Default().arrayOfPointers(false).data().deviceAddress(instances);
        return geometry;
    }

    /** A section layer's bottom level structure and the ranges of the mesh it was built from. */
    private final class Blas implements AutoCloseable {
        final GpuBuffer vertices, indices;
        final int baseVertex, remainder, firstIndex, indexCount, indexBytes, stride, maxVertex, triangles, mask;
        final boolean compact;
        final long vertexAddress, indexAddress, scratchBytes;
        final BlazeRayStructures.Structure structure;
        int used;

        Blas(BlazeMesh mesh, int mask, MemoryStack stack) {
            vertices = mesh.drawVertices;
            indices = mesh.drawIndices;
            baseVertex = mesh.drawBaseVertex;
            remainder = mesh.drawRemainder;
            firstIndex = mesh.drawFirstIndex;
            indexCount = mesh.drawIndexCount;
            indexBytes = mesh.drawIndexBytes;
            stride = mesh.drawLayout.stride();
            compact = stride == COMPACT_STRIDE;
            BlazeMeshState state = mesh.stateOrNull();
            maxVertex = Math.max(0, (state != null ? state.vertexCount() : indexCount) - 1);
            triangles = indexCount / 3;
            this.mask = mask;
            vertexAddress = vertices.deviceAddress() + (long) baseVertex * stride + remainder;
            indexAddress = indices.deviceAddress() + (long) firstIndex * indexBytes;
            var build = buildInfo(stack, geometry(stack));
            var sizes = structures.sizes(build, new int[]{triangles}, stack);
            scratchBytes = sizes.buildScratchSize();
            structure = structures.create(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR, sizes.accelerationStructureSize());
        }

        boolean holds(BlazeMesh mesh) {
            return mesh.drawVertices == vertices && mesh.drawIndices == indices && mesh.drawBaseVertex == baseVertex && mesh.drawRemainder == remainder
                    && mesh.drawFirstIndex == firstIndex && mesh.drawIndexCount == indexCount && mesh.drawIndexBytes == indexBytes;
        }

        VkAccelerationStructureGeometryKHR.Buffer geometry(MemoryStack stack) {
            var geometry = VkAccelerationStructureGeometryKHR.calloc(1, stack);
            geometry.get(0).sType$Default().geometryType(VK_GEOMETRY_TYPE_TRIANGLES_KHR).flags(mask == MASK_CUTOUT ? 0 : VK_GEOMETRY_OPAQUE_BIT_KHR);
            var triangleData = geometry.get(0).geometry().triangles();
            triangleData.sType$Default().vertexFormat(compact ? VK_FORMAT_R16G16B16A16_UNORM : VK_FORMAT_R32G32B32_SFLOAT)
                    .vertexStride(stride).maxVertex(maxVertex).indexType(indexBytes == 2 ? VK_INDEX_TYPE_UINT16 : VK_INDEX_TYPE_UINT32);
            triangleData.vertexData().deviceAddress(vertexAddress);
            triangleData.indexData().deviceAddress(indexAddress);
            return geometry;
        }

        VkAccelerationStructureBuildGeometryInfoKHR buildInfo(MemoryStack stack, VkAccelerationStructureGeometryKHR.Buffer geometry) {
            return VkAccelerationStructureBuildGeometryInfoKHR.calloc(stack).sType$Default().type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                    .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR).mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                    .geometryCount(1).pGeometries(geometry);
        }

        @Override public void close() {
            structure.close();
        }
    }

    private record Candidate(BlazeMesh mesh, int layer, double distance, float[] offset) {}

    /**
     * Records the frame's bottom level builds side by side in shared scratch memory, then the top level build, with
     * the barriers that put the geometry uploads before them and both before the rays.
     */
    private final class Build implements BlazeQueue.Operation {
        private final List<Blas> builds;
        private final long instances, scratchBytes;
        private final int count;
        private final BlazeRayStructures.Structure top;

        Build(List<Blas> builds, long instances, int count, BlazeRayStructures.Structure top, long topScratch) {
            this.builds = builds;
            this.instances = instances;
            this.count = count;
            this.top = top;
            long bytes = structures.align(topScratch);
            for (Blas blas : builds) bytes += structures.align(blas.scratchBytes);
            scratchBytes = bytes;
        }

        @Override public void record(VkCommandBuffer command) {
            long scratch = structures.scratch(scratchBytes);
            int buildStage = VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR;
            BlazeRayStructures.barrier(command, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_ACCESS_MEMORY_WRITE_BIT | VK_ACCESS_SHADER_READ_BIT
                    | VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR, buildStage,
                    VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR | VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR | VK_ACCESS_SHADER_READ_BIT);
            for (int from = 0; from < builds.size(); from += 16) {
                int to = Math.min(builds.size(), from + 16);
                try (MemoryStack stack = VulkanStack.push()) {
                    var infos = VkAccelerationStructureBuildGeometryInfoKHR.calloc(to - from, stack);
                    PointerBuffer ranges = stack.mallocPointer(to - from);
                    for (int at = from; at < to; at++) {
                        Blas blas = builds.get(at);
                        infos.get(at - from).set(blas.buildInfo(stack, blas.geometry(stack)));
                        infos.get(at - from).dstAccelerationStructure(blas.structure.handle()).scratchData().deviceAddress(scratch);
                        scratch += structures.align(blas.scratchBytes);
                        ranges.put(at - from, VkAccelerationStructureBuildRangeInfoKHR.calloc(1, stack).primitiveCount(blas.triangles));
                    }
                    vkCmdBuildAccelerationStructuresKHR(command, infos, ranges);
                }
            }
            if (!builds.isEmpty()) BlazeRayStructures.barrier(command, buildStage, VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR, buildStage,
                    VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);
            try (MemoryStack stack = VulkanStack.push()) {
                var info = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack).sType$Default().type(VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR)
                        .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_BUILD_BIT_KHR).mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                        .geometryCount(1).pGeometries(topGeometry(stack, instances)).dstAccelerationStructure(top.handle());
                info.get(0).scratchData().deviceAddress(scratch);
                vkCmdBuildAccelerationStructuresKHR(command, info, stack.pointers(VkAccelerationStructureBuildRangeInfoKHR.calloc(1, stack).primitiveCount(count)));
            }
            BlazeRayStructures.barrier(command, buildStage, VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR | VK_ACCESS_SHADER_READ_BIT);
        }

        @Override public void submitted(FrameRing.Submission submission) {}

        @Override public void close() {}
    }

    /** Host buffers written once a frame; each goes back to the free list once the frames that read it finish. */
    private final class HostRing implements AutoCloseable {
        private final int usage;
        private final ArrayDeque<GpuBuffer> free = new ArrayDeque<>();
        private final List<GpuBuffer> all = new ArrayList<>();

        HostRing(int usage) {
            this.usage = usage;
        }

        GpuBuffer take(int bytes) {
            while (!free.isEmpty()) {
                GpuBuffer buffer = free.poll();
                if (buffer.size() >= bytes) return buffer;
                all.remove(buffer);
                buffer.close();
            }
            GpuBuffer buffer = owner.allocator().hostBuffer(Math.max(64 * 1024, bytes + bytes / 2), usage);
            all.add(buffer);
            return buffer;
        }

        void giveBack(GpuBuffer buffer) {
            owner.retire(() -> free.add(buffer));
        }

        @Override public void close() {
            all.forEach(GpuBuffer::close);
            all.clear();
            free.clear();
        }
    }

    @Override public void close() {
        built.values().forEach(Blas::close);
        built.clear();
        if (top != null) top.close();
        top = null;
        instanceBuffers.close();
        tableBuffers.close();
        structures.close();
    }
}
