package dev.overhaul.minecraft.blaze;

import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import dev.overhaul.shader.compat.ProgramBindings;
import dev.overhaul.vulkan.NativeGraphicsPipeline;
import java.util.ArrayList;
import java.util.List;
import static org.lwjgl.vulkan.VK10.*;

/** Uses the game's actual interleaved offsets and integer/normalized attribute semantics. */
final class BlazeVertexLayout {
    private final VertexFormat format;
    BlazeVertexLayout(VertexFormat format) { this.format = format; }

    List<NativeGraphicsPipeline.Attribute> attributes(ProgramBindings bindings) {
        List<NativeGraphicsPipeline.Attribute> result = new ArrayList<>();
        for (var attribute : bindings.attributes().entrySet()) {
            int index = format.getElementAttributeNames().indexOf(attribute.getKey());
            if (index < 0) throw new UnsupportedOperationException("Missing vertex attribute: " + attribute.getKey());
            VertexFormatElement element = format.getElements().get(index);
            result.add(new NativeGraphicsPipeline.Attribute(attribute.getValue(), 0, nativeFormat(element), format.getOffset(element)));
        }
        return List.copyOf(result);
    }

    private int nativeFormat(VertexFormatElement value) {
        int count = value.count();
        if (count < 1 || count > 4) throw new UnsupportedOperationException("Unsupported attribute width: " + count);
        boolean normalized = value.usage() == VertexFormatElement.Usage.COLOR || value.usage() == VertexFormatElement.Usage.NORMAL;
        // Pinned Usage.UV uses IPointer for every non-FLOAT type, including
        // SHORT UV0. POSITION/GENERIC use unnormalized floating conversion.
        boolean integer = value.usage() == VertexFormatElement.Usage.UV && value.type() != VertexFormatElement.Type.FLOAT;
        if (!normalized && !integer && value.type() != VertexFormatElement.Type.FLOAT) {
            throw new UnsupportedOperationException("Scaled floating attribute conversion is not implemented: " + value);
        }
        if (normalized && (value.type() == VertexFormatElement.Type.INT || value.type() == VertexFormatElement.Type.UINT)) {
            throw new UnsupportedOperationException("Normalized 32-bit attribute conversion is not implemented: " + value);
        }
        int[] formats = switch (value.type()) {
            case FLOAT -> new int[]{VK_FORMAT_R32_SFLOAT, VK_FORMAT_R32G32_SFLOAT, VK_FORMAT_R32G32B32_SFLOAT, VK_FORMAT_R32G32B32A32_SFLOAT};
            case UBYTE -> normalized
                    ? new int[]{VK_FORMAT_R8_UNORM, VK_FORMAT_R8G8_UNORM, VK_FORMAT_R8G8B8_UNORM, VK_FORMAT_R8G8B8A8_UNORM}
                    : new int[]{VK_FORMAT_R8_UINT, VK_FORMAT_R8G8_UINT, VK_FORMAT_R8G8B8_UINT, VK_FORMAT_R8G8B8A8_UINT};
            case BYTE -> normalized
                    ? new int[]{VK_FORMAT_R8_SNORM, VK_FORMAT_R8G8_SNORM, VK_FORMAT_R8G8B8_SNORM, VK_FORMAT_R8G8B8A8_SNORM}
                    : new int[]{VK_FORMAT_R8_SINT, VK_FORMAT_R8G8_SINT, VK_FORMAT_R8G8B8_SINT, VK_FORMAT_R8G8B8A8_SINT};
            case USHORT -> normalized
                    ? new int[]{VK_FORMAT_R16_UNORM, VK_FORMAT_R16G16_UNORM, VK_FORMAT_R16G16B16_UNORM, VK_FORMAT_R16G16B16A16_UNORM}
                    : new int[]{VK_FORMAT_R16_UINT, VK_FORMAT_R16G16_UINT, VK_FORMAT_R16G16B16_UINT, VK_FORMAT_R16G16B16A16_UINT};
            case SHORT -> normalized
                    ? new int[]{VK_FORMAT_R16_SNORM, VK_FORMAT_R16G16_SNORM, VK_FORMAT_R16G16B16_SNORM, VK_FORMAT_R16G16B16A16_SNORM}
                    : new int[]{VK_FORMAT_R16_SINT, VK_FORMAT_R16G16_SINT, VK_FORMAT_R16G16B16_SINT, VK_FORMAT_R16G16B16A16_SINT};
            case UINT -> new int[]{VK_FORMAT_R32_UINT, VK_FORMAT_R32G32_UINT, VK_FORMAT_R32G32B32_UINT, VK_FORMAT_R32G32B32A32_UINT};
            case INT -> new int[]{VK_FORMAT_R32_SINT, VK_FORMAT_R32G32_SINT, VK_FORMAT_R32G32B32_SINT, VK_FORMAT_R32G32B32A32_SINT};
        };
        return formats[count - 1];
    }
}
