package dev.overhaul.minecraft.blaze;

import dev.overhaul.shader.compat.ProgramBindings;
import dev.overhaul.shader.compat.UnsupportedShaderException;
import dev.overhaul.vulkan.NativeGraphicsPipeline;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Feeds a pack's legacy vertex inputs from whatever layout the game drew with, without recompiling per layout. An input
 * the mesh lacks reads one fixed value from a small per-instance buffer, as GL's current attribute values would give.
 */
final class BlazePackVertexInputs {
    private static final float[][] CONSTANTS = {
        {0, 0, 0, 0}, {1, 1, 1, 1}, {0, 0, 1, 0}, {0, 0, 0, 1}, {240, 240, 0, 1}, {-1, -1, -1, -1}, {1, 0, 0, 1}
    };
    private static final int ZERO = 0, ONE = 1, UP = 2, TEXTURE = 3, LIGHT = 4, UNKNOWN_ID = 5, TANGENT = 6;
    private static final int CONSTANT_BYTES = 16, STRIDE = CONSTANTS.length * CONSTANT_BYTES;

    private BlazePackVertexInputs() {}

    static ByteBuffer constants() {
        ByteBuffer bytes = ByteBuffer.allocateDirect(STRIDE).order(ByteOrder.nativeOrder());
        for (float[] constant : CONSTANTS) for (float component : constant) bytes.putFloat(component);
        return bytes.flip();
    }

    static List<NativeGraphicsPipeline.Binding> bindings(BlazeMeshState.Layout layout) {
        return List.of(new NativeGraphicsPipeline.Binding(0, layout.stride(), 0), new NativeGraphicsPipeline.Binding(1, STRIDE, 1));
    }

    static List<NativeGraphicsPipeline.Attribute> attributes(ProgramBindings bindings, BlazeMeshState.Layout layout) {
        List<NativeGraphicsPipeline.Attribute> result = new ArrayList<>();
        for (var input : bindings.attributes().entrySet()) {
            String type = bindings.attributeTypes().get(input.getKey());
            if (type != null && !type.equals("float") && !type.startsWith("vec")) throw new UnsupportedShaderException("Integer pack vertex input: " + input.getKey());
            result.add(attribute(input.getKey(), input.getValue(), layout));
        }
        return List.copyOf(result);
    }

    private static NativeGraphicsPipeline.Attribute attribute(String name, int location, BlazeMeshState.Layout layout) {
        return switch (name) {
            case "overhaulLegacyVertex" -> {
                var position = field(layout, "Position");
                if (position == null) throw new UnsupportedShaderException("Mesh without positions reached a pack program");
                yield new NativeGraphicsPipeline.Attribute(location, 0, position.nativeFormat(), position.offset());
            }
            case "overhaulLegacyColor" -> either(location, layout, "Color", ONE);
            case "overhaulLegacyNormal" -> either(location, layout, "Normal", UP);
            case "overhaulLegacyTexCoord0", "mc_midTexCoord" -> either(location, layout, "UV0", TEXTURE);
            case "overhaulLegacyTexCoord1" -> either(location, layout, "UV2", LIGHT);
            case "mc_Entity" -> either(location, layout, BlazePackBlockIds.FIELD, UNKNOWN_ID);
            case "at_tangent" -> constant(location, TANGENT);
            case "at_velocity", "at_midBlock" -> constant(location, ZERO);
            default -> throw new UnsupportedShaderException("Unbound pack vertex input: " + name);
        };
    }

    private static NativeGraphicsPipeline.Attribute either(int location, BlazeMeshState.Layout layout, String source, int fallback) {
        var field = field(layout, source);
        return field == null ? constant(location, fallback) : new NativeGraphicsPipeline.Attribute(location, 0, floating(field.nativeFormat()), field.offset());
    }

    private static NativeGraphicsPipeline.Attribute constant(int location, int index) {
        return new NativeGraphicsPipeline.Attribute(location, 1, VK_FORMAT_R32G32B32A32_SFLOAT, index * CONSTANT_BYTES);
    }

    /** Game shaders read lightmap coordinates as integers; a pack's gl_MultiTexCoord1 reads the same bits as floats. */
    private static int floating(int format) {
        return switch (format) {
            case VK_FORMAT_R16G16_SINT -> VK_FORMAT_R16G16_SSCALED;
            case VK_FORMAT_R16G16_UINT -> VK_FORMAT_R16G16_USCALED;
            default -> format;
        };
    }

    private static BlazeMeshState.Field field(BlazeMeshState.Layout layout, String name) {
        for (var field : layout.fields()) if (field.name().equals(name)) return field;
        return null;
    }
}
