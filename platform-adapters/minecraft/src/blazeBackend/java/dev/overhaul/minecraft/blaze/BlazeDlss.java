package dev.overhaul.minecraft.blaze;

import dev.overhaul.vulkan.FrameRing;
import dev.overhaul.vulkan.GpuImageView;
import dev.overhaul.vulkan.VulkanContext;
import dev.overhaul.vulkan.VulkanStack;
import java.nio.ByteBuffer;
import java.util.Locale;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.lwjgl.vulkan.VK10.*;

/**
 * DLSS Super Resolution through Streamline, reading the same jittered colour, depth and motion FSR2 reads and writing
 * the same display-size output, so the upscaler swaps one for the other. Exists only on a device Streamline reports
 * DLSS for. Owner-thread only.
 */
final class BlazeDlss implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(BlazeDlss.class);
    private static final int VIEWPORT_ID = 0;
    private static final BlazeFsr2Resources.Image[] INPUTS = {
            BlazeFsr2Resources.Image.INPUT_COLOR, BlazeFsr2Resources.Image.INPUT_DEPTH,
            BlazeFsr2Resources.Image.INPUT_MOTION, BlazeFsr2Resources.Image.OUTPUT};
    private static final int[] TYPES = {
            BlazeStreamlineStructs.INPUT_COLOR, BlazeStreamlineStructs.DEPTH,
            BlazeStreamlineStructs.MOTION, BlazeStreamlineStructs.OUTPUT_COLOR};

    private final BlazeStreamline streamline;
    private final long optimalSettings, setOptions;
    private final int mode;
    private final ByteBuffer viewport, options, settings, token;
    private boolean closed, warned;

    private BlazeDlss(BlazeStreamline streamline, long optimalSettings, long setOptions, int mode) {
        this.streamline = streamline;
        this.optimalSettings = optimalSettings;
        this.setOptions = setOptions;
        this.mode = mode;
        viewport = BlazeStreamlineStructs.viewport(VIEWPORT_ID);
        options = BlazeStreamlineStructs.allocate(BlazeStreamlineStructs.OPTIONS_BYTES);
        settings = BlazeStreamlineStructs.allocate(BlazeStreamlineStructs.SETTINGS_BYTES);
        token = BlazeStreamlineStructs.allocate(8);
    }

    /** @return DLSS on this device, or null when Streamline did not come up or reports no DLSS for it */
    static BlazeDlss create(BlazeStreamline streamline, VulkanContext context) {
        if (streamline == null) return null;
        ByteBuffer adapter = BlazeStreamlineStructs.adapter(context.physicalDevice().address());
        try {
            int supported = streamline.supported(BlazeStreamline.FEATURE_DLSS, MemoryUtil.memAddress(adapter));
            if (supported != BlazeStreamline.OK) {
                LOG.info("DLSS unavailable on {}: Streamline reports {}", context.deviceName(), supported);
                return null;
            }
        } finally {
            MemoryUtil.memFree(adapter);
        }
        long optimal = streamline.featureFunction(BlazeStreamline.FEATURE_DLSS, "slDLSSGetOptimalSettings");
        long set = streamline.featureFunction(BlazeStreamline.FEATURE_DLSS, "slDLSSSetOptions");
        if (optimal == 0 || set == 0) {
            LOG.info("DLSS unavailable: Streamline lacks its DLSS functions");
            return null;
        }
        return new BlazeDlss(streamline, optimal, set, mode(BlazeOptimization.value("dlssQuality", "quality")));
    }

    boolean nativeResolution() {
        return mode == 6;
    }

    /** @return the render size DLSS wants for this display size, or null if it gives none */
    int[] renderSize(int displayWidth, int displayHeight) {
        BlazeStreamlineStructs.options(options, mode, displayWidth, displayHeight);
        BlazeStreamlineStructs.header(settings, BlazeStreamlineStructs.DLSS_SETTINGS, 1);
        int result = JNI.invokePPI(MemoryUtil.memAddress(options), MemoryUtil.memAddress(settings), optimalSettings);
        int width = settings.getInt(32), height = settings.getInt(36);
        if (result != BlazeStreamline.OK || width <= 0 || height <= 0) {
            warn("slDLSSGetOptimalSettings returned " + result);
            return null;
        }
        return new int[]{width, height};
    }

    /**
     * Queues DLSS for this frame: the inputs FSR2's colour copy and motion pass wrote, the output its image.
     *
     * @return false when Streamline refused the frame, so the caller falls back
     */
    boolean evaluate(BlazeRenderer owner, BlazeFsr2Resources resources, BlazeStreamlineStructs.Camera camera) {
        long frame = streamline.newFrame(MemoryUtil.memAddress(token));
        if (frame == 0) return warn("slGetNewFrameToken failed");
        BlazeStreamlineStructs.options(options, mode, resources.displayWidth(), resources.displayHeight());
        int result = JNI.invokePPI(MemoryUtil.memAddress(viewport), MemoryUtil.memAddress(options), setOptions);
        if (result != BlazeStreamline.OK) return warn("slDLSSSetOptions returned " + result);
        ByteBuffer constants = BlazeStreamlineStructs.allocate(BlazeStreamlineStructs.CONSTANTS_BYTES);
        try {
            BlazeStreamlineStructs.constants(constants, camera);
            result = streamline.constants(MemoryUtil.memAddress(constants), frame, MemoryUtil.memAddress(viewport));
        } finally {
            MemoryUtil.memFree(constants);
        }
        if (result != BlazeStreamline.OK) return warn("slSetConstants returned " + result);
        owner.enqueue(new Evaluate(resources, frame));
        return true;
    }

    private boolean warn(String problem) {
        if (!warned) LOG.warn("DLSS skipped a frame: {}", problem);
        warned = true;
        return false;
    }

    private static int mode(String quality) {
        return switch (quality.toLowerCase(Locale.ROOT)) {
            case "performance" -> 1;
            case "balanced" -> 2;
            case "ultra", "ultraperformance", "ultra_performance" -> 4;
            case "dlaa", "native" -> 6;
            default -> 3;
        };
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        int result = streamline.free(BlazeStreamline.FEATURE_DLSS, MemoryUtil.memAddress(viewport));
        if (result != BlazeStreamline.OK) LOG.debug("slFreeResources returned {}", result);
        MemoryUtil.memFree(viewport);
        MemoryUtil.memFree(options);
        MemoryUtil.memFree(settings);
        MemoryUtil.memFree(token);
    }

    /** Tags the four images in GENERAL layout and evaluates DLSS into the output, in the frame's command buffer. */
    private final class Evaluate implements BlazeQueue.Operation {
        private final BlazeFsr2Resources resources;
        private final long frame;
        private final BlazeImageUse images = new BlazeImageUse();

        private Evaluate(BlazeFsr2Resources resources, long frame) {
            this.resources = resources;
            this.frame = frame;
        }

        @Override public void record(VkCommandBuffer command) {
            for (BlazeFsr2Resources.Image kind : INPUTS) images.transition(command, resources.view(kind), VK_IMAGE_LAYOUT_GENERAL);
            int count = INPUTS.length;
            ByteBuffer described = BlazeStreamlineStructs.allocate(count * BlazeStreamlineStructs.RESOURCE_BYTES);
            ByteBuffer tags = BlazeStreamlineStructs.allocate(count * BlazeStreamlineStructs.TAG_BYTES);
            ByteBuffer inputs = BlazeStreamlineStructs.allocate(8);
            try (MemoryStack stack = VulkanStack.push()) {
                var before = VkMemoryBarrier.calloc(1, stack).sType$Default()
                        .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT)
                        .dstAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
                vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, before, null, null);
                for (int i = 0; i < count; i++) {
                    GpuImageView view = resources.view(INPUTS[i]);
                    int offset = i * BlazeStreamlineStructs.RESOURCE_BYTES;
                    BlazeStreamlineStructs.resource(described, offset, view, VK_IMAGE_LAYOUT_GENERAL);
                    BlazeStreamlineStructs.tag(tags, i * BlazeStreamlineStructs.TAG_BYTES, MemoryUtil.memAddress(described) + offset,
                            TYPES[i], view.image().width(0), view.image().height(0));
                }
                inputs.putLong(0, MemoryUtil.memAddress(viewport));
                int result = streamline.tag(frame, MemoryUtil.memAddress(viewport), MemoryUtil.memAddress(tags), count, command.address());
                if (result == BlazeStreamline.OK) result = streamline.evaluate(BlazeStreamline.FEATURE_DLSS, frame, MemoryUtil.memAddress(inputs), 1, command.address());
                if (result != BlazeStreamline.OK) warn("Streamline returned " + result + " while evaluating");
                var after = VkMemoryBarrier.calloc(1, stack).sType$Default().srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
                        .dstAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_READ_BIT);
                vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT,
                        0, after, null, null);
            } finally {
                MemoryUtil.memFree(described);
                MemoryUtil.memFree(tags);
                MemoryUtil.memFree(inputs);
            }
        }

        @Override public void submitted(FrameRing.Submission submission) {
            images.submitted(submission);
        }

        @Override public void close() {
            images.close();
        }
    }
}
