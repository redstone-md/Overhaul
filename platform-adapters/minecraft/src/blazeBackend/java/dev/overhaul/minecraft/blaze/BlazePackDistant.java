package dev.overhaul.minecraft.blaze;

import dev.overhaul.vulkan.GpuImage;
import dev.overhaul.vulkan.GpuImageView;
import dev.overhaul.vulkan.ImageFormat;
import dev.overhaul.vulkan.ImageRegion;
import dev.overhaul.vulkan.NativeGraphicsPipeline;
import java.util.List;
import java.util.Map;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Distant Horizons' depth while a pack draws DH terrain itself. LODs use their own clip planes, so they get their own
 * depth image instead of sharing the scene's: the pack's composites read it as dhDepthTex0, and dhDepthTex1 is a copy
 * taken after the opaque LODs, which translucent LODs sample while they test against the first.
 * The clip planes are the last ones DH drew with, so a frame's uniforms see the planes of the frame before.
 */
public final class BlazePackDistant implements AutoCloseable {
    private static final String MOD_CLASS = "com.seibel.distanthorizons.api.DhApi";
    private static Boolean loaded;
    private static float near = 16, far = 4096, renderDistance = 4096;

    static final BlazeMeshState MESH = new BlazeMeshState(new BlazeMeshState.Layout(16, List.of(
            new BlazeMeshState.Field("DhPosition", 0, VK_FORMAT_R16G16B16A16_USCALED),
            new BlazeMeshState.Field("Color", 8, VK_FORMAT_R8G8B8A8_UNORM),
            new BlazeMeshState.Field("DhData", 12, VK_FORMAT_R8G8B8A8_USCALED))).canonical(),
            0, 0, VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST, 4, BlazeMeshState.IndexPattern.SEQUENTIAL);
    private static final NativeGraphicsPipeline.ColorLogic NO_LOGIC = new NativeGraphicsPipeline.ColorLogic(false, VK_LOGIC_OP_COPY);
    private static final NativeGraphicsPipeline.Color OPAQUE = new NativeGraphicsPipeline.Color(false, VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ZERO, VK_BLEND_OP_ADD,
            VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ZERO, VK_BLEND_OP_ADD, 15);
    private static final NativeGraphicsPipeline.Color BLENDED = new NativeGraphicsPipeline.Color(true, VK_BLEND_FACTOR_SRC_ALPHA, VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA, VK_BLEND_OP_ADD,
            VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA, VK_BLEND_OP_ADD, 15);

    private final BlazeRenderer renderer;
    private final GpuImage[] depth = new GpuImage[2];
    private final GpuImageView[] views = new GpuImageView[2];
    private final BlazeDrawState.Snapshot opaque, translucent;
    private final ImageRegion whole;

    BlazePackDistant(BlazeRenderer renderer, int width, int height) {
        this.renderer = renderer;
        whole = new ImageRegion(0, 0, 0, 0, width, height);
        opaque = raster(new NativeGraphicsPipeline.Depth(true, true, VK_COMPARE_OP_LESS_OR_EQUAL, 0, 0), OPAQUE, width, height);
        translucent = raster(new NativeGraphicsPipeline.Depth(true, false, VK_COMPARE_OP_LESS_OR_EQUAL, 0, 0), BLENDED, width, height);
        try {
            for (int index = 0; index < 2; index++) {
                depth[index] = renderer.allocator().createImage(ImageFormat.D32_SFLOAT, width, height, 1, 1,
                        VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
                views[index] = depth[index].view(0, 1, 0, 1);
            }
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    private static BlazeDrawState.Snapshot raster(NativeGraphicsPipeline.Depth test, NativeGraphicsPipeline.Color color, int width, int height) {
        return new BlazeDrawState.Snapshot(test, color, NO_LOGIC, VK_CULL_MODE_BACK_BIT, VK_POLYGON_MODE_FILL, 1, 0, 0, width, height, false, 0, 0, 0, 0);
    }

    /** True when Distant Horizons is installed, which is when a pack may compile its DH programs. */
    public static boolean loaded() {
        if (loaded == null) {
            try {
                Class.forName(MOD_CLASS, false, BlazePackDistant.class.getClassLoader());
                loaded = true;
            } catch (ClassNotFoundException | LinkageError absent) {
                loaded = false;
            }
        }
        return loaded;
    }

    /** DH's clip planes and LOD radius in blocks, as its last terrain pass used them. */
    public static void planes(float nearPlane, float farPlane, float radius) {
        near = nearPlane;
        far = farPlane;
        renderDistance = radius;
    }

    public static float near() {
        return near;
    }

    public static float far() {
        return far;
    }

    public static float renderDistance() {
        return renderDistance;
    }

    /** Nothing from the frame before may show through: a frame DH skips reads as empty sky. */
    void begin(BlazeTarget first, BlazeTarget second) {
        BlazeDrawState.Clear clear = new BlazeDrawState.Clear(0, 0, 0, 0, 1);
        first.clear(null, 0x100, opaque, clear);
        second.clear(null, 0x100, opaque, clear);
    }

    /** The opaque LODs are queued: the copy translucent ones sample is taken now. */
    void opaqueDone() {
        renderer.copyImage(depth[0], whole, depth[1], whole);
    }

    GpuImageView view(int index) {
        return views[index];
    }

    BlazeDrawState.Snapshot raster(boolean solid) {
        return solid ? opaque : translucent;
    }

    /** What a pack's composites sample the LOD depth by. */
    Map<String, GpuImageView> textures() {
        return Map.of("dhDepthTex", views[0], "dhDepthTex0", views[0], "dhDepthTex1", views[1]);
    }

    /** The scene has waited for the device. */
    @Override
    public void close() {
        for (int index = 0; index < 2; index++) {
            if (views[index] != null) views[index].close();
            if (depth[index] != null) depth[index].close();
            views[index] = null;
            depth[index] = null;
        }
    }
}
