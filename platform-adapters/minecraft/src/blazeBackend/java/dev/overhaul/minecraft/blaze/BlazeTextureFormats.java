package dev.overhaul.minecraft.blaze;

import dev.overhaul.vulkan.ImageFormat;
import java.util.HashMap;
import java.util.Map;

/** Maps OpenGL internal formats that mods allocate to Vulkan storage. RGB-only storage widens to RGBA. */
final class BlazeTextureFormats {
    record Storage(ImageFormat format, int channels) {
        /** Eight-bit colour storage is the only kind the CPU pixel converter can write. */
        boolean convertible() {
            return switch (format) {
                case R8_UNORM, RG8_UNORM, RGBA8_UNORM, RGBA8_SRGB -> true;
                default -> false;
            };
        }
    }

    private static final Map<Integer, Storage> STORAGE = new HashMap<>();

    private static void put(ImageFormat format, int channels, int... internals) {
        for (int internal : internals) STORAGE.put(internal, new Storage(format, channels));
    }

    static {
        put(ImageFormat.RGBA8_UNORM, 4, 6408, 32856);
        put(ImageFormat.RGBA8_UNORM, 3, 6407, 32849);
        put(ImageFormat.RGBA8_SRGB, 4, 35906, 35907);
        put(ImageFormat.RGBA8_SRGB, 3, 35904, 35905);
        put(ImageFormat.RG8_UNORM, 2, 33319, 33323);
        put(ImageFormat.R8_UNORM, 1, 6403, 33321);
        put(ImageFormat.R8_SNORM, 1, 36756);
        put(ImageFormat.RG8_SNORM, 2, 36757);
        put(ImageFormat.RGBA8_SNORM, 3, 36758);
        put(ImageFormat.RGBA8_SNORM, 4, 36759);
        put(ImageFormat.R16_UNORM, 1, 33322);
        put(ImageFormat.RG16_UNORM, 2, 33324);
        put(ImageFormat.RGBA16_UNORM, 3, 32852);
        put(ImageFormat.RGBA16_UNORM, 4, 32859);
        put(ImageFormat.R16_SFLOAT, 1, 33325);
        put(ImageFormat.RG16_SFLOAT, 2, 33327);
        put(ImageFormat.RGBA16_SFLOAT, 3, 34843);
        put(ImageFormat.RGBA16_SFLOAT, 4, 34842);
        put(ImageFormat.R32_SFLOAT, 1, 33326);
        put(ImageFormat.RG32_SFLOAT, 2, 33328);
        put(ImageFormat.RGBA32_SFLOAT, 3, 34837);
        put(ImageFormat.RGBA32_SFLOAT, 4, 34836);
        put(ImageFormat.RGB10A2_UNORM, 4, 32857);
        put(ImageFormat.RGB10A2_UINT, 4, 36975);
        put(ImageFormat.RG11B10_UFLOAT, 3, 35898);
        put(ImageFormat.R8_SINT, 1, 33329);
        put(ImageFormat.R8_UINT, 1, 33330);
        put(ImageFormat.R16_SINT, 1, 33331);
        put(ImageFormat.R16_UINT, 1, 33332);
        put(ImageFormat.R32_SINT, 1, 33333);
        put(ImageFormat.R32_UINT, 1, 33334);
        put(ImageFormat.RG8_SINT, 2, 33335);
        put(ImageFormat.RG8_UINT, 2, 33336);
        put(ImageFormat.RG16_SINT, 2, 33337);
        put(ImageFormat.RG16_UINT, 2, 33338);
        put(ImageFormat.RG32_SINT, 2, 33339);
        put(ImageFormat.RG32_UINT, 2, 33340);
        put(ImageFormat.RGBA8_UINT, 3, 36221);
        put(ImageFormat.RGBA8_UINT, 4, 36220);
        put(ImageFormat.RGBA8_SINT, 3, 36239);
        put(ImageFormat.RGBA8_SINT, 4, 36238);
        put(ImageFormat.RGBA16_UINT, 3, 36215);
        put(ImageFormat.RGBA16_UINT, 4, 36214);
        put(ImageFormat.RGBA16_SINT, 3, 36233);
        put(ImageFormat.RGBA16_SINT, 4, 36232);
        put(ImageFormat.RGBA32_UINT, 3, 36209);
        put(ImageFormat.RGBA32_UINT, 4, 36208);
        put(ImageFormat.RGBA32_SINT, 3, 36227);
        put(ImageFormat.RGBA32_SINT, 4, 36226);
        put(ImageFormat.D32_SFLOAT, 1, 6402, 33189, 33190, 33191, 36012);
        put(ImageFormat.D32_SFLOAT_S8_UINT, 2, 34041, 35056, 36013);
    }

    private BlazeTextureFormats() {}

    static Storage storage(int internal) {
        Storage storage = STORAGE.get(internal);
        if (storage == null) throw new UnsupportedOperationException("Unsupported internal texture format: " + internal);
        return storage;
    }
}
