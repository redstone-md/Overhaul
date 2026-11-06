package dev.overhaul.minecraft.blaze;

import dev.overhaul.shader.blaze.BlazeProgramCompiler;
import dev.overhaul.vulkan.GpuBuffer;
import dev.overhaul.vulkan.NativeGraphicsPipeline;
import dev.overhaul.vulkan.VulkanStack;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.lwjgl.system.MemoryStack;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Draws from vertex arrays a mod built with raw OpenGL calls: each enabled pointer becomes a native vertex input of
 * the active foreign program, read from the buffer's device copy. Draws that cannot be mapped report why.
 */
final class BlazeRawDraws implements AutoCloseable {
    private static final int GL_BYTE = 0x1400, GL_UNSIGNED_BYTE = 0x1401, GL_SHORT = 0x1402, GL_UNSIGNED_SHORT = 0x1403,
            GL_INT = 0x1404, GL_UNSIGNED_INT = 0x1405, GL_FLOAT = 0x1406, GL_HALF_FLOAT = 0x140B;
    private static final int[] BYTE_BASES = {VK_FORMAT_R8_UNORM, VK_FORMAT_R8G8_UNORM, VK_FORMAT_R8G8B8_UNORM, VK_FORMAT_R8G8B8A8_UNORM};
    private static final int[] SHORT_BASES = {VK_FORMAT_R16_UNORM, VK_FORMAT_R16G16_UNORM, VK_FORMAT_R16G16B16_UNORM, VK_FORMAT_R16G16B16A16_UNORM};
    private static final int[] INT_BASES = {VK_FORMAT_R32_UINT, VK_FORMAT_R32G32_UINT, VK_FORMAT_R32G32B32_UINT, VK_FORMAT_R32G32B32A32_UINT};

    /** @param indexType zero for a non-indexed draw; @param elements the element buffer bound to the array */
    record Request(List<BlazeGlVertexArrays.Pointer> pointers, int mode, int first, int count, int instances, int indexType, long indexOffset, int elements) {}

    private record Inputs(List<NativeGraphicsPipeline.Binding> bindings, List<NativeGraphicsPipeline.Attribute> attributes) {}

    private record Source(int buffer, int stride, int stepRate) {}

    private record PipelineKey(BlazeProgramCompiler.Program program, Inputs inputs, int topology, NativeGraphicsPipeline.Depth depth,
            NativeGraphicsPipeline.Color color, NativeGraphicsPipeline.ColorLogic logic, int cull, int polygonMode, long renderPass) {}

    private static final class Failure extends RuntimeException {
        Failure(String message) { super(message, null, false, false); }
    }

    private final BlazeRenderer owner;
    private final Map<PipelineKey, NativeGraphicsPipeline> pipelines = new HashMap<>();

    BlazeRawDraws(BlazeRenderer owner) { this.owner = owner; }

    /** @return null once drawn, or why the draw was dropped */
    String draw(Request request) {
        if (owner.drawingNowhere()) return null;
        BlazePrograms programs = owner.programs();
        if (!programs.activeForeign()) return "with no native program bound";
        try {
            int id = programs.activeId();
            BlazeShaderSnapshot shader = programs.activeSnapshot();
            BlazeProgramCompiler.Program program = shader.program();
            if (program.geometry() != null) throw new Failure("through a geometry stage");
            int topology = topology(request.mode());
            List<GpuBuffer> buffers = new ArrayList<>();
            long[] offsets = new long[BlazeGlVertexArrays.SLOTS];
            Inputs inputs = inputs(id, program, request.pointers(), buffers, offsets);
            int indexType = 0;
            GpuBuffer indices = null;
            if (request.indexType() != 0) {
                indexType = indexType(request.indexType());
                indices = BlazeGlBoundary.buffers().device(owner, request.elements());
                if (indices == null) throw new Failure("with no element buffer");
            }
            submit(program, shader, inputs, topology, buffers, offsets, indices, indexType, request);
            return null;
        } catch (Failure failure) {
            return failure.getMessage();
        } catch (RuntimeException failure) {
            return failure.getClass().getSimpleName() + ": " + failure.getMessage();
        }
    }

    private void submit(BlazeProgramCompiler.Program program, BlazeShaderSnapshot shader, Inputs inputs, int topology, List<GpuBuffer> buffers, long[] offsets,
            GpuBuffer indices, int indexType, Request request) {
        var bindings = program.bindings();
        var pass = new BlazeTerrainPass(owner, owner.currentTarget(), owner.drawState().snapshot(), (target, width, height) -> {
            ByteBuffer values = shader.uniforms();
            if (values.remaining() > target.remaining()) values.limit(values.position() + target.remaining());
            target.put(values);
        }, program, (p, state, target, layout) -> pipeline(p, state, target, layout, inputs, topology),
                name -> owner.textures().resolve(shader.samplers().get(name), bindings.samplerTypes().get(name)), null);
        int indexSize = indexType == VK_INDEX_TYPE_UINT16 ? 2 : 4;
        int firstIndex = (int) (request.indexOffset() / indexSize);
        long[] handles = new long[buffers.size()];
        for (int index = 0; index < handles.length; index++) handles[index] = buffers.get(index).handle();
        long[] starts = java.util.Arrays.copyOf(offsets, handles.length);
        GpuBuffer elements = indices;
        int kind = indexType;
        pass.add((command, layout, multiDraw) -> {
            try (MemoryStack stack = VulkanStack.push()) {
                if (handles.length > 0) vkCmdBindVertexBuffers(command, 0, stack.longs(handles), stack.longs(starts));
                if (elements != null) {
                    vkCmdBindIndexBuffer(command, elements.handle(), 0, kind);
                    vkCmdDrawIndexed(command, request.count(), request.instances(), firstIndex, 0, 0);
                } else {
                    vkCmdDraw(command, request.count(), request.instances(), request.first(), 0);
                }
            }
        });
        owner.enqueue(pass);
    }

    /** Maps each enabled GL pointer to the program input its source declared at that location, grouped by buffer into bindings. */
    private Inputs inputs(int id, BlazeProgramCompiler.Program program, List<BlazeGlVertexArrays.Pointer> pointers, List<GpuBuffer> buffers, long[] offsets) {
        var bindings = program.bindings();
        Map<Integer, String> required = new HashMap<>();
        for (var entry : bindings.attributes().entrySet()) {
            String type = bindings.attributeTypes().getOrDefault(entry.getKey(), "vec4");
            for (int column = 0; column < BlazeGlVertexArrays.columns(type); column++) required.put(entry.getValue() + column, type);
        }
        Map<Integer, BlazeGlVertexArrays.Slot> declared = BlazeGlVertexArrays.declared(id);
        Map<Source, Integer> slots = new LinkedHashMap<>();
        Map<Source, Long> bases = new LinkedHashMap<>();
        List<BlazeGlVertexArrays.Pointer> used = new ArrayList<>();
        List<Integer> locations = new ArrayList<>();
        for (BlazeGlVertexArrays.Pointer pointer : pointers) {
            int location = location(declared, bindings.attributes(), pointer.location());
            if (location < 0 || !required.containsKey(location)) continue;
            if (pointer.divisor() > 1) throw new Failure("with instance divisor " + pointer.divisor());
            Source source = new Source(pointer.buffer(), stride(pointer), pointer.divisor() > 0 ? 1 : 0);
            bases.merge(source, pointer.offset(), Math::min);
            used.add(pointer);
            locations.add(location);
        }
        var buffersByName = BlazeGlBoundary.buffers();
        List<NativeGraphicsPipeline.Binding> bindingList = new ArrayList<>();
        for (var entry : bases.entrySet()) {
            Source source = entry.getKey();
            BlazeGlBuffers.Placed placed = buffersByName.placed(owner, source.buffer(), false);
            if (placed == null) throw new Failure("from vertex buffer " + source.buffer() + " with no storage");
            int slot = bindingList.size();
            slots.put(source, slot);
            bindingList.add(new NativeGraphicsPipeline.Binding(slot, source.stride(), source.stepRate()));
            buffers.add(placed.buffer());
            offsets[slot] = placed.base() + entry.getValue();
        }
        List<NativeGraphicsPipeline.Attribute> attributes = new ArrayList<>();
        for (int index = 0; index < used.size(); index++) {
            BlazeGlVertexArrays.Pointer pointer = used.get(index);
            int location = locations.get(index);
            Source source = new Source(pointer.buffer(), stride(pointer), pointer.divisor() > 0 ? 1 : 0);
            attributes.add(new NativeGraphicsPipeline.Attribute(location, slots.get(source), format(pointer, required.get(location)), (int) (pointer.offset() - bases.get(source))));
            required.remove(location);
        }
        if (!required.isEmpty()) throw new Failure("missing vertex input at location " + required.keySet().iterator().next() + " of " + bindings.attributes().keySet());
        return new Inputs(List.copyOf(bindingList), List.copyOf(attributes));
    }

    /** The native location of a GL location: by the name the source gave it, or the same number when it gave none. */
    private static int location(Map<Integer, BlazeGlVertexArrays.Slot> declared, Map<String, Integer> attributes, int glLocation) {
        if (declared == null) return glLocation;
        BlazeGlVertexArrays.Slot slot = declared.get(glLocation);
        if (slot == null) return -1;
        Integer location = attributes.get(slot.name());
        if (location == null) location = attributes.get(slot.name() + "0");
        return location == null ? -1 : location + slot.column();
    }

    private static int stride(BlazeGlVertexArrays.Pointer pointer) {
        return pointer.stride() != 0 ? pointer.stride() : pointer.size() * typeBytes(pointer.type());
    }

    private static int typeBytes(int type) {
        return switch (type) {
            case GL_BYTE, GL_UNSIGNED_BYTE -> 1;
            case GL_SHORT, GL_UNSIGNED_SHORT, GL_HALF_FLOAT -> 2;
            case GL_INT, GL_UNSIGNED_INT, GL_FLOAT -> 4;
            default -> throw new Failure("with vertex type 0x" + Integer.toHexString(type));
        };
    }

    /** UNORM, SNORM, USCALED, SSCALED, UINT and SINT follow each other in every 8 and 16 bit family. */
    private static int format(BlazeGlVertexArrays.Pointer pointer, String shaderType) {
        int size = pointer.size();
        if (size < 1 || size > 4) throw new Failure("with " + size + " components");
        boolean integerInput = shaderType.startsWith("i") || shaderType.startsWith("u");
        boolean unsigned = pointer.type() == GL_UNSIGNED_BYTE || pointer.type() == GL_UNSIGNED_SHORT || pointer.type() == GL_UNSIGNED_INT;
        int kind = integerInput ? (unsigned ? 4 : 5) : pointer.normalized() ? (unsigned ? 0 : 1) : (unsigned ? 2 : 3);
        return switch (pointer.type()) {
            case GL_FLOAT -> {
                if (integerInput) throw new Failure("reading floats into " + shaderType);
                yield INT_BASES[size - 1] + 2;
            }
            case GL_HALF_FLOAT -> SHORT_BASES[size - 1] + 6;
            case GL_BYTE, GL_UNSIGNED_BYTE -> BYTE_BASES[size - 1] + kind;
            case GL_SHORT, GL_UNSIGNED_SHORT -> SHORT_BASES[size - 1] + kind;
            case GL_INT, GL_UNSIGNED_INT -> {
                if (!integerInput) throw new Failure("reading 32-bit integers into " + shaderType);
                yield INT_BASES[size - 1] + (unsigned ? 0 : 1);
            }
            default -> throw new Failure("with vertex type 0x" + Integer.toHexString(pointer.type()));
        };
    }

    private static int topology(int mode) {
        return switch (mode) {
            case 0 -> VK_PRIMITIVE_TOPOLOGY_POINT_LIST;
            case 1 -> VK_PRIMITIVE_TOPOLOGY_LINE_LIST;
            case 3 -> VK_PRIMITIVE_TOPOLOGY_LINE_STRIP;
            case 4 -> VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
            case 5 -> VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;
            case 6 -> VK_PRIMITIVE_TOPOLOGY_TRIANGLE_FAN;
            default -> throw new Failure("in GL mode " + mode);
        };
    }

    private static int indexType(int type) {
        return switch (type) {
            case GL_UNSIGNED_SHORT -> VK_INDEX_TYPE_UINT16;
            case GL_UNSIGNED_INT -> VK_INDEX_TYPE_UINT32;
            default -> throw new Failure("with index type 0x" + Integer.toHexString(type));
        };
    }

    private NativeGraphicsPipeline pipeline(BlazeProgramCompiler.Program program, BlazeDrawState.Snapshot draw, BlazeTarget target, long descriptorLayout,
            Inputs inputs, int topology) {
        var key = new PipelineKey(program, inputs, topology, draw.depth(), draw.color(), draw.logic(), draw.cull(), draw.polygonMode(), target.renderPass());
        var existing = pipelines.get(key);
        if (existing != null) return existing;
        var description = new NativeGraphicsPipeline.Description(List.of(
                new NativeGraphicsPipeline.Shader(VK_SHADER_STAGE_VERTEX_BIT, "main", program.vertex().directBuffer()),
                new NativeGraphicsPipeline.Shader(VK_SHADER_STAGE_FRAGMENT_BIT, "main", program.fragment().directBuffer())),
                inputs.bindings(), inputs.attributes(), new long[]{descriptorLayout}, 0, topology, draw.polygonMode(), draw.cull(), VK_FRONT_FACE_CLOCKWISE,
                draw.depth(), List.of(draw.color()), true, 0, 0, draw.logic());
        var created = new NativeGraphicsPipeline(owner.context(), target.renderPass(), description);
        pipelines.put(key, created);
        return created;
    }

    @Override public void close() {
        for (NativeGraphicsPipeline pipeline : new ArrayList<>(pipelines.values())) pipeline.close();
        pipelines.clear();
    }
}
