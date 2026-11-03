package dev.overhaul.vulkan;

import dev.overhaul.shader.graph.RenderGraph;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/** Authoritative graph/native format correspondence, shared by allocation and borrowed-image imports. */
final class GraphImageFormats {
    private static final Map<RenderGraph.Format,ImageFormat> IMAGES;
    private static final Map<ImageFormat,RenderGraph.Format> GRAPHS;
    static {
        var images = new EnumMap<RenderGraph.Format,ImageFormat>(RenderGraph.Format.class);
        var graphs = new EnumMap<ImageFormat,RenderGraph.Format>(ImageFormat.class);
        for (RenderGraph.Format format : RenderGraph.Format.values()) {
            ImageFormat image = switch (format) {
                case R8 -> ImageFormat.R8_UNORM;
                case RG8 -> ImageFormat.RG8_UNORM;
                case RGB8 -> ImageFormat.RGB8_UNORM;
                case RGBA8 -> ImageFormat.RGBA8_UNORM;
                case RGBA8_SRGB -> ImageFormat.RGBA8_SRGB;
                case BGRA8 -> ImageFormat.BGRA8_UNORM;
                case BGRA8_SRGB -> ImageFormat.BGRA8_SRGB;
                case R16 -> ImageFormat.R16_UNORM;
                case RG16 -> ImageFormat.RG16_UNORM;
                case RGB16 -> ImageFormat.RGB16_UNORM;
                case RGBA16 -> ImageFormat.RGBA16_UNORM;
                case R16F -> ImageFormat.R16_SFLOAT;
                case RG16F -> ImageFormat.RG16_SFLOAT;
                case RGB16F -> ImageFormat.RGB16_SFLOAT;
                case RGBA16F -> ImageFormat.RGBA16_SFLOAT;
                case R32F -> ImageFormat.R32_SFLOAT;
                case RG32F -> ImageFormat.RG32_SFLOAT;
                case RGB32F -> ImageFormat.RGB32_SFLOAT;
                case RGBA32F -> ImageFormat.RGBA32_SFLOAT;
                case RGB10_A2 -> ImageFormat.RGB10A2_UNORM;
                case R11F_G11F_B10F -> ImageFormat.RG11B10_UFLOAT;
                case DEPTH16 -> ImageFormat.D16_UNORM;
                case DEPTH24_STENCIL8 -> ImageFormat.D24_UNORM_S8_UINT;
                case DEPTH32F -> ImageFormat.D32_SFLOAT;
                case DEPTH32F_STENCIL8 -> ImageFormat.D32_SFLOAT_S8_UINT;
            };
            images.put(format,image);
            if (graphs.put(image,format) != null) throw new ExceptionInInitializerError("Duplicate native graph image format");
        }
        IMAGES = Collections.unmodifiableMap(images);
        GRAPHS = Collections.unmodifiableMap(graphs);
    }

    private GraphImageFormats() {}
    static ImageFormat image(RenderGraph.Format format) {
        return java.util.Objects.requireNonNull(IMAGES.get(format),"graph image format");
    }
    static RenderGraph.Format graph(ImageFormat format) {
        RenderGraph.Format result = GRAPHS.get(format);
        if (result == null) throw new IllegalArgumentException("Unsupported imported graph image format: " + format);
        return result;
    }
}
