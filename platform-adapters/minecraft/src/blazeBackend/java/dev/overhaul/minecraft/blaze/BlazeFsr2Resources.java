package dev.overhaul.minecraft.blaze;

import dev.overhaul.vulkan.GpuAllocator;
import dev.overhaul.vulkan.GpuImage;
import dev.overhaul.vulkan.GpuImageView;
import dev.overhaul.vulkan.ImageFormat;
import dev.overhaul.vulkan.ImageRegion;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import static org.lwjgl.vulkan.VK10.*;

/**
 * The images one FSR2 context reads and writes at one render and display size, and the shader resource name each
 * binding of a pass resolves to on an even or odd frame. Every image stays in the general layout, so a pass only
 * needs a barrier before it, never a layout change.
 */
final class BlazeFsr2Resources implements AutoCloseable {
    private static final int STORAGE = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    private static final int TABLE = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    /** The scene luminance mip the shading change estimate reads; the SDK's {@code lumaMipLevelToUse}. */
    static final int SHADING_CHANGE_MIP = 4;

    private enum Size { RENDER, DISPLAY, HALF_RENDER, ONE, LANCZOS, BIAS }

    enum Image {
        /** The game's jittered colour, copied from the scaled target each frame. */
        INPUT_COLOR(ImageFormat.RGBA8_UNORM, Size.RENDER, STORAGE | VK_IMAGE_USAGE_TRANSFER_SRC_BIT),
        /** World depth, with the hand's depth where the hand is drawn; written by the motion pass. */
        INPUT_DEPTH(ImageFormat.R32_SFLOAT, Size.RENDER, STORAGE),
        INPUT_MOTION(ImageFormat.RG16_SFLOAT, Size.RENDER, STORAGE),
        /** The world depth before the game clears it for the hand. */
        WORLD_DEPTH(ImageFormat.R32_SFLOAT, Size.RENDER, STORAGE),
        PREPARED_COLOR(ImageFormat.RGBA16_SFLOAT, Size.RENDER, STORAGE),
        PREVIOUS_DEPTH(ImageFormat.R32_UINT, Size.RENDER, STORAGE),
        MOTION_EVEN(ImageFormat.RG16_SFLOAT, Size.RENDER, STORAGE),
        MOTION_ODD(ImageFormat.RG16_SFLOAT, Size.RENDER, STORAGE),
        DILATED_DEPTH(ImageFormat.R32_SFLOAT, Size.RENDER, STORAGE),
        LOCK_EVEN(ImageFormat.RG16_SFLOAT, Size.DISPLAY, STORAGE),
        LOCK_ODD(ImageFormat.RG16_SFLOAT, Size.DISPLAY, STORAGE),
        LOCK_LUMA(ImageFormat.R16_SFLOAT, Size.RENDER, STORAGE),
        NEW_LOCKS(ImageFormat.R8_UNORM, Size.DISPLAY, STORAGE),
        UPSCALED_EVEN(ImageFormat.RGBA16_SFLOAT, Size.DISPLAY, STORAGE),
        UPSCALED_ODD(ImageFormat.RGBA16_SFLOAT, Size.DISPLAY, STORAGE),
        LUMINANCE(ImageFormat.R16_SFLOAT, Size.HALF_RENDER, STORAGE),
        HISTORY_EVEN(ImageFormat.RGBA8_UNORM, Size.DISPLAY, STORAGE),
        HISTORY_ODD(ImageFormat.RGBA8_UNORM, Size.DISPLAY, STORAGE),
        ATOMIC(ImageFormat.R32_UINT, Size.ONE, STORAGE),
        REACTIVE(ImageFormat.RG8_UNORM, Size.RENDER, STORAGE),
        AUTO_EXPOSURE(ImageFormat.RG32_SFLOAT, Size.ONE, STORAGE),
        OUTPUT(ImageFormat.RGBA16_SFLOAT, Size.DISPLAY, STORAGE | VK_IMAGE_USAGE_TRANSFER_SRC_BIT),
        LANCZOS(ImageFormat.R16_SNORM, Size.LANCZOS, TABLE),
        MAXIMUM_BIAS(ImageFormat.R16_SNORM, Size.BIAS, TABLE),
        /** No reactivity anywhere: the game hands FSR no reactive or transparency mask. */
        NO_REACTIVITY(ImageFormat.R8_UNORM, Size.ONE, TABLE),
        /** A zero exposure, which FSR reads as an exposure of one. */
        NO_EXPOSURE(ImageFormat.RG32_SFLOAT, Size.ONE, TABLE);

        final ImageFormat format;
        final Size size;
        final int usage;

        Image(ImageFormat format, Size size, int usage) {
            this.format = format;
            this.size = size;
            this.usage = usage;
        }
    }

    private final int renderWidth, renderHeight, displayWidth, displayHeight;
    private final Map<Image, GpuImage> images = new EnumMap<>(Image.class);
    private final Map<Image, GpuImageView> views = new EnumMap<>(Image.class);
    private final GpuImageView shadingChange, mip5;
    private final List<AutoCloseable> owned = new ArrayList<>();

    BlazeFsr2Resources(BlazeRenderer owner, int renderWidth, int renderHeight, int displayWidth, int displayHeight) {
        this.renderWidth = renderWidth;
        this.renderHeight = renderHeight;
        this.displayWidth = displayWidth;
        this.displayHeight = displayHeight;
        GpuAllocator allocator = owner.allocator();
        try {
            for (Image kind : Image.values()) {
                int[] extent = extent(kind.size);
                int mips = kind == Image.LUMINANCE ? mipCount(extent[0], extent[1]) : 1;
                GpuImage image = allocator.createImage(kind.format, extent[0], extent[1], mips, 1, kind.usage);
                owned.add(image);
                images.put(kind, image);
                views.put(kind, image.view(0, mips, 0, 1));
            }
            GpuImage luminance = images.get(Image.LUMINANCE);
            shadingChange = luminance.view(SHADING_CHANGE_MIP, 1, 0, 1);
            mip5 = luminance.view(5, 1, 0, 1);
            fill(owner);
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    int renderWidth() { return renderWidth; }
    int renderHeight() { return renderHeight; }
    int displayWidth() { return displayWidth; }
    int displayHeight() { return displayHeight; }
    GpuImage image(Image kind) { return images.get(kind); }
    GpuImageView view(Image kind) { return views.get(kind); }

    /** Every view a pass may bind, so an operation can barrier the lot before it. */
    List<GpuImageView> all() {
        List<GpuImageView> all = new ArrayList<>(views.values());
        all.add(shadingChange);
        all.add(mip5);
        return all;
    }

    /** The view the SDK binds under {@code name} on a frame of the given parity. */
    GpuImageView resolve(String name, boolean odd) {
        return switch (name) {
            case "r_input_color_jittered" -> view(Image.INPUT_COLOR);
            case "r_input_depth" -> view(Image.INPUT_DEPTH);
            case "r_input_motion_vectors" -> view(Image.INPUT_MOTION);
            case "r_input_exposure" -> view(Image.NO_EXPOSURE);
            case "r_auto_exposure", "rw_auto_exposure", "rw_exposure" -> view(Image.AUTO_EXPOSURE);
            case "r_reactive_mask", "r_transparency_and_composition_mask" -> view(Image.NO_REACTIVITY);
            case "r_prepared_input_color", "rw_prepared_input_color" -> view(Image.PREPARED_COLOR);
            case "r_reconstructed_previous_nearest_depth", "rw_reconstructed_previous_nearest_depth" -> view(Image.PREVIOUS_DEPTH);
            case "r_dilated_motion_vectors", "rw_dilated_motion_vectors" -> view(odd ? Image.MOTION_ODD : Image.MOTION_EVEN);
            case "r_previous_dilated_motion_vectors" -> view(odd ? Image.MOTION_EVEN : Image.MOTION_ODD);
            case "r_dilatedDepth", "rw_dilatedDepth" -> view(Image.DILATED_DEPTH);
            case "r_lock_status" -> view(odd ? Image.LOCK_ODD : Image.LOCK_EVEN);
            case "rw_lock_status" -> view(odd ? Image.LOCK_EVEN : Image.LOCK_ODD);
            case "r_lock_input_luma", "rw_lock_input_luma" -> view(Image.LOCK_LUMA);
            case "r_new_locks", "rw_new_locks" -> view(Image.NEW_LOCKS);
            case "r_internal_upscaled_color" -> view(odd ? Image.UPSCALED_ODD : Image.UPSCALED_EVEN);
            case "rw_internal_upscaled_color", "r_rcas_input" -> view(odd ? Image.UPSCALED_EVEN : Image.UPSCALED_ODD);
            case "r_luma_history" -> view(odd ? Image.HISTORY_ODD : Image.HISTORY_EVEN);
            case "rw_luma_history" -> view(odd ? Image.HISTORY_EVEN : Image.HISTORY_ODD);
            case "r_imgMips" -> view(Image.LUMINANCE);
            case "rw_img_mip_shading_change" -> shadingChange;
            case "rw_img_mip_5" -> mip5;
            case "rw_spd_global_atomic" -> view(Image.ATOMIC);
            case "r_dilated_reactive_masks", "rw_dilated_reactive_masks" -> view(Image.REACTIVE);
            case "rw_upscaled_output" -> view(Image.OUTPUT);
            case "r_lanczos_lut" -> view(Image.LANCZOS);
            case "r_upsample_maximum_bias_lut" -> view(Image.MAXIMUM_BIAS);
            default -> throw new IllegalStateException("FSR2 binds an unknown resource " + name);
        };
    }

    /**
     * Queues the clears the SDK makes when history is dropped: the lock status and upscaled colour this frame reads,
     * the luminance pyramid, and an auto exposure that starts over.
     */
    void reset(BlazeRenderer owner, boolean odd) {
        clear(owner, odd ? Image.LOCK_ODD : Image.LOCK_EVEN, 0, 0);
        clear(owner, odd ? Image.UPSCALED_ODD : Image.UPSCALED_EVEN, 0, 0);
        clear(owner, Image.LUMINANCE, 0, 0);
        clear(owner, Image.AUTO_EXPOSURE, -1, 1e8f);
    }

    private void fill(BlazeRenderer owner) {
        for (Image kind : Image.values()) if (kind != Image.LANCZOS && kind != Image.MAXIMUM_BIAS) clear(owner, kind, 0, 0);
        upload(owner, Image.LANCZOS, lanczos());
        upload(owner, Image.MAXIMUM_BIAS, BlazeFsr2Tables.maximumBias());
    }

    private void clear(BlazeRenderer owner, Image kind, float red, float green) {
        GpuImage image = images.get(kind);
        for (int mip = 0; mip < image.mipLevels(); mip++) owner.clearImage(image, mip, 0, red, green, 0, 0);
    }

    private void upload(BlazeRenderer owner, Image kind, short[] values) {
        GpuImage image = images.get(kind);
        ByteBuffer bytes = ByteBuffer.allocateDirect(values.length * Short.BYTES).order(ByteOrder.nativeOrder());
        for (short value : values) bytes.putShort(value);
        bytes.flip();
        owner.uploadImage(image, ImageRegion.whole(image, 0, 0), bytes, 0);
    }

    /** The SDK's 128-entry Lanczos-2 table over [0, 2], as signed normalised shorts. */
    private static short[] lanczos() {
        short[] table = new short[128];
        for (int i = 0; i < table.length; i++) {
            double x = 2.0 * i / (table.length - 1);
            table[i] = (short) Math.round(lanczos2(x) * 32767);
        }
        return table;
    }

    private static double lanczos2(double x) {
        if (Math.abs(x) < 1e-6) return 1;
        double pi = Math.PI * x;
        return Math.sin(pi) / pi * (Math.sin(pi / 2) / (pi / 2));
    }

    private int[] extent(Size size) {
        return switch (size) {
            case RENDER -> new int[]{renderWidth, renderHeight};
            case DISPLAY -> new int[]{displayWidth, displayHeight};
            case HALF_RENDER -> new int[]{Math.max(1, renderWidth / 2), Math.max(1, renderHeight / 2)};
            case ONE -> new int[]{1, 1};
            case LANCZOS -> new int[]{128, 1};
            case BIAS -> new int[]{BlazeFsr2Tables.BIAS_SIZE, BlazeFsr2Tables.BIAS_SIZE};
        };
    }

    private static int mipCount(int width, int height) {
        return 32 - Integer.numberOfLeadingZeros(Math.max(width, height));
    }

    @Override public void close() {
        for (int i = owned.size() - 1; i >= 0; i--) {
            try {
                owned.get(i).close();
            } catch (Exception ignored) {
                // Closing the rest matters more than the first failure.
            }
        }
        owned.clear();
    }
}
