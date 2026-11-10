package dev.overhaul.minecraft.blaze;

import dev.overhaul.vulkan.GpuImageView;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

/**
 * Streamline's structures as x64 byte layouts. Each starts with the base structure, a next pointer, the type's GUID and
 * its version, so fields begin at byte 32. Matrices go out in JOML's column order, which is Streamline's row-major
 * storage of the same transform written for row vectors.
 */
final class BlazeStreamlineStructs {
    static final int[] PREFERENCES = {0x1ca10965, 0xbf8e, 0x432b, 0x8d, 0xa1, 0x67, 0x16, 0xd8, 0x79, 0xfb, 0x14};
    static final int[] RESOURCE = {0x3a9d70cf, 0x2418, 0x4b72, 0x83, 0x91, 0x13, 0xf8, 0x72, 0x1c, 0x72, 0x61};
    static final int[] RESOURCE_TAG = {0x4c6a5aad, 0xb445, 0x496c, 0x87, 0xff, 0x1a, 0xf3, 0x84, 0x5b, 0xe6, 0x53};
    static final int[] VIEWPORT = {0x171b6435, 0x9b3c, 0x4fc8, 0x99, 0x94, 0xfb, 0xe5, 0x25, 0x69, 0xaa, 0xa4};
    static final int[] ADAPTER = {0x0677315f, 0xa746, 0x4492, 0x9f, 0x42, 0xcb, 0x61, 0x42, 0xc9, 0xc3, 0xd4};
    static final int[] CONSTANTS = {0xdcd35ad7, 0x4e4a, 0x4bad, 0xa9, 0x0c, 0xe0, 0xc4, 0x9e, 0xb2, 0x3a, 0xfe};
    static final int[] DLSS_OPTIONS = {0x6ac826e4, 0x4c61, 0x4101, 0xa9, 0x2d, 0x63, 0x8d, 0x42, 0x10, 0x57, 0xb8};
    static final int[] DLSS_SETTINGS = {0xef1d0957, 0xfd58, 0x4df7, 0xb5, 0x04, 0x8b, 0x69, 0xd8, 0xaa, 0x6b, 0x76};
    static final int VIEWPORT_BYTES = 40, ADAPTER_BYTES = 56, RESOURCE_BYTES = 112, TAG_BYTES = 64;
    static final int CONSTANTS_BYTES = 456, OPTIONS_BYTES = 88, SETTINGS_BYTES = 64;
    static final int DEPTH = 0, MOTION = 1, INPUT_COLOR = 3, OUTPUT_COLOR = 4;
    private static final byte FALSE = 0, TRUE = 1;

    private BlazeStreamlineStructs() {}

    static ByteBuffer allocate(int bytes) {
        return MemoryUtil.memCalloc(bytes).order(ByteOrder.nativeOrder());
    }

    /** Writes the base structure: no next, the GUID and the version. */
    static void header(ByteBuffer struct, int[] guid, int version) {
        struct.order(ByteOrder.nativeOrder());
        struct.putLong(0, 0).putInt(8, guid[0]).putShort(12, (short) guid[1]).putShort(14, (short) guid[2]);
        for (int i = 0; i < 8; i++) struct.put(16 + i, (byte) guid[3 + i]);
        struct.putLong(24, version);
    }

    static ByteBuffer viewport(int id) {
        ByteBuffer viewport = allocate(VIEWPORT_BYTES);
        header(viewport, VIEWPORT, 1);
        viewport.putInt(32, id);
        return viewport;
    }

    static ByteBuffer adapter(long physicalDevice) {
        ByteBuffer adapter = allocate(ADAPTER_BYTES);
        header(adapter, ADAPTER, 1);
        adapter.putLong(48, physicalDevice);
        return adapter;
    }

    /** One image in the layout it will be in when Streamline reads it. */
    static void resource(ByteBuffer struct, int offset, GpuImageView view, int layout) {
        ByteBuffer at = struct.slice(offset, RESOURCE_BYTES).order(ByteOrder.nativeOrder());
        header(at, RESOURCE, 1);
        var image = view.image();
        at.put(32, (byte) 0)
                .putLong(40, image.handle()).putLong(48, image.deviceMemory()).putLong(56, view.handle())
                .putInt(64, layout).putInt(68, image.width(0)).putInt(72, image.height(0)).putInt(76, image.format().vkFormat())
                .putInt(80, image.mipLevels()).putInt(84, image.layers()).putLong(88, 0)
                .putInt(96, image.createFlags()).putInt(100, image.usage());
    }

    /** A tag naming a resource written at {@code resource}; the extent covers the whole image. */
    static void tag(ByteBuffer struct, int offset, long resource, int type, int width, int height) {
        ByteBuffer at = struct.slice(offset, TAG_BYTES).order(ByteOrder.nativeOrder());
        header(at, RESOURCE_TAG, 1);
        at.putLong(32, resource).putInt(40, type).putInt(44, 0)
                .putInt(48, 0).putInt(52, 0).putInt(56, width).putInt(60, height);
    }

    /** What DLSS needs of one frame's camera. */
    record Camera(Matrix4f viewToClip, Matrix4f clipToPrevClip, float jitterX, float jitterY,
                  float near, float far, float verticalFov, float aspect, boolean reset) {}

    static void constants(ByteBuffer struct, Camera camera) {
        header(struct, CONSTANTS, 2);
        Matrix4f viewToClip = camera.viewToClip(), clipToView = new Matrix4f(viewToClip).invert();
        Matrix4f toPrevious = camera.clipToPrevClip(), fromPrevious = new Matrix4f(toPrevious).invert();
        viewToClip.get(32, struct);
        clipToView.get(96, struct);
        new Matrix4f().get(160, struct);
        toPrevious.get(224, struct);
        fromPrevious.get(288, struct);
        struct.putFloat(352, camera.jitterX()).putFloat(356, camera.jitterY())
                .putFloat(360, 1).putFloat(364, 1)
                .putFloat(368, 0).putFloat(372, 0);
        for (int i = 376; i < 424; i += 4) struct.putFloat(i, 0);
        // Up is +y, right +x and forward -z in the game's view space.
        struct.putFloat(392, 1).putFloat(400, 1).putFloat(420, -1);
        struct.putFloat(424, camera.near()).putFloat(428, camera.far()).putFloat(432, camera.verticalFov())
                .putFloat(436, camera.aspect()).putFloat(440, 0);
        struct.put(444, FALSE).put(445, TRUE).put(446, FALSE).put(447, camera.reset() ? TRUE : FALSE)
                .put(448, FALSE).put(449, FALSE).put(450, FALSE)
                .putFloat(452, 40);
    }

    /** DLSS options for an LDR frame with automatic exposure. */
    static void options(ByteBuffer struct, int mode, int outputWidth, int outputHeight) {
        header(struct, DLSS_OPTIONS, 3);
        struct.putInt(32, mode).putInt(36, outputWidth).putInt(40, outputHeight)
                .putFloat(44, 0).putFloat(48, 1).putFloat(52, 1)
                .put(56, FALSE).put(57, FALSE).put(58, FALSE);
        for (int i = 60; i < 84; i += 4) struct.putInt(i, 0);
        struct.put(84, TRUE).put(85, FALSE);
    }
}
