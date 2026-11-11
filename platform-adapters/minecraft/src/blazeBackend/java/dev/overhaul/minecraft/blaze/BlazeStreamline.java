package dev.overhaul.minecraft.blaze;

import dev.overhaul.vulkan.VulkanContext;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.lwjgl.system.JNI;
import org.lwjgl.system.Library;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.SharedLibrary;
import org.lwjgl.vulkan.VK;
import org.lwjgl.vulkan.VK12;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NVIDIA Streamline, loaded as the process's Vulkan library before the renderer's instance exists. Its interposer adds
 * the extensions and features DLSS needs to the instance and device the renderer creates, so nothing else changes.
 * Windows only; anything missing or refused leaves Vulkan as it was and the upscaler falls back to FSR2.
 */
final class BlazeStreamline implements AutoCloseable {
    static final int FEATURE_DLSS = 0, OK = 0;
    private static final Logger LOG = LoggerFactory.getLogger(BlazeStreamline.class);
    private static final long SDK_VERSION = (2L << 48) | (14L << 32) | (16L << 16) | 0xfedcL;
    private static final String INTERPOSER = "sl.interposer.dll";
    private static final String[] FILES = {INTERPOSER, "sl.common.dll", "sl.dlss.dll", "nvngx_dlss.dll"};
    private static final int PREFERENCES_BYTES = 144, LOG_DEFAULT = 1, RENDER_VULKAN = 2, ENGINE_CUSTOM = 0;
    private static final long DISABLE_STATE_TRACKING = 1, FRAME_BASED_TAGGING = 1 << 7;
    private static boolean vulkanSwapped;

    private final SharedLibrary library;
    private final List<ByteBuffer> natives = new ArrayList<>();
    private final long shutdown, supported, featureFunction, frameToken, tag, constants, evaluate, free;
    private boolean closed;

    private BlazeStreamline(SharedLibrary library) {
        this.library = library;
        shutdown = function("slShutdown");
        supported = function("slIsFeatureSupported");
        featureFunction = function("slGetFeatureFunction");
        frameToken = function("slGetNewFrameToken");
        tag = function("slSetTagForFrame");
        constants = function("slSetConstants");
        evaluate = function("slEvaluateFeature");
        free = function("slFreeResources");
    }

    /**
     * Initialises Streamline for DLSS and makes its interposer the Vulkan library. Call before the first instance.
     *
     * @return null when DLSS is off and not readied for the settings screen, the platform is not Windows, the files are missing or Streamline refuses
     */
    static BlazeStreamline open() {
        if (!BlazeOptimization.DLSS.on() && !BlazeOptimization.UPSCALE_READY.on() || !System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) return null;
        Path directory = directory();
        if (directory == null) return null;
        Path interposer = directory.resolve(INTERPOSER);
        SharedLibrary library;
        try {
            library = Library.loadNative(BlazeStreamline.class, "org.lwjgl.vulkan", interposer.toString());
        } catch (RuntimeException | UnsatisfiedLinkError failure) {
            LOG.warn("DLSS unavailable: Streamline did not load from {}", directory, failure);
            return null;
        }
        BlazeStreamline streamline = null;
        try {
            streamline = new BlazeStreamline(library);
            int result = streamline.initialise(directory);
            if (result != OK) {
                LOG.warn("DLSS unavailable: slInit returned {}; logs are in {}", result, directory.resolve("logs"));
                streamline.release();
                return null;
            }
            if (!vulkanSwapped) {
                VK.destroy();
                VK.create(interposer.toString());
                vulkanSwapped = true;
            }
            VulkanContext.requestApiVersion(VK12.VK_API_VERSION_1_2);
            LOG.info("Streamline {} loaded from {}", "2.14.16", directory);
            return streamline;
        } catch (RuntimeException | Error failure) {
            LOG.warn("DLSS unavailable: Streamline failed to start", failure);
            if (streamline != null) streamline.release();
            else library.free();
            return null;
        }
    }

    /** The folder holding the Streamline and DLSS libraries, filled from the jar when it carries them. */
    private static Path directory() {
        String configured = BlazeOptimization.value("streamlinePath", "");
        Path directory = configured.isEmpty() ? Path.of("overhaul", "streamline").toAbsolutePath() : Path.of(configured).toAbsolutePath();
        try {
            Files.createDirectories(directory);
            for (String name : FILES) extract(name, directory.resolve(name));
        } catch (IOException failure) {
            LOG.warn("DLSS unavailable: could not prepare {}", directory, failure);
            return null;
        }
        for (String name : FILES) {
            if (!Files.isRegularFile(directory.resolve(name))) {
                LOG.info("DLSS unavailable: {} is missing from {}", name, directory);
                return null;
            }
        }
        return directory;
    }

    private static void extract(String name, Path target) throws IOException {
        try (InputStream in = BlazeStreamline.class.getResourceAsStream("/overhaul/streamline/" + name)) {
            if (in == null) return;
            byte[] bytes = in.readAllBytes();
            if (Files.isRegularFile(target) && Files.size(target) == bytes.length) return;
            Path temporary = target.resolveSibling(name + ".part");
            Files.write(temporary, bytes);
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private int initialise(Path directory) {
        ByteBuffer plugins = keep(MemoryUtil.memUTF16(directory.toString(), true));
        ByteBuffer logs = keep(MemoryUtil.memUTF16(directory.resolve("logs").toString(), true));
        ByteBuffer pluginList = keep(MemoryUtil.memCalloc(8));
        pluginList.putLong(0, MemoryUtil.memAddress(plugins));
        ByteBuffer features = keep(MemoryUtil.memCalloc(4));
        features.putInt(0, FEATURE_DLSS);
        ByteBuffer engineVersion = keep(MemoryUtil.memUTF8("1.0", true));
        ByteBuffer project = keep(MemoryUtil.memUTF8("6f1c8a4e-3b2d-4e57-9a61-0c5d7e2f9b13", true));
        ByteBuffer preferences = keep(MemoryUtil.memCalloc(PREFERENCES_BYTES));
        BlazeStreamlineStructs.header(preferences, BlazeStreamlineStructs.PREFERENCES, 1);
        preferences.put(32, (byte) 0)
                .putInt(36, LOG_DEFAULT)
                .putLong(40, MemoryUtil.memAddress(pluginList)).putInt(48, 1)
                .putLong(56, MemoryUtil.memAddress(logs))
                .putLong(88, DISABLE_STATE_TRACKING | FRAME_BASED_TAGGING)
                .putLong(96, MemoryUtil.memAddress(features)).putInt(104, 1)
                .putInt(108, 0).putInt(112, ENGINE_CUSTOM)
                .putLong(120, MemoryUtil.memAddress(engineVersion))
                .putLong(128, MemoryUtil.memAddress(project))
                .putInt(136, RENDER_VULKAN);
        try {
            Files.createDirectories(directory.resolve("logs"));
        } catch (IOException ignored) {
            // Streamline logs nowhere then; it still runs.
        }
        return JNI.invokePJI(MemoryUtil.memAddress(preferences), SDK_VERSION, function("slInit"));
    }

    private ByteBuffer keep(ByteBuffer buffer) {
        natives.add(buffer);
        return buffer;
    }

    private long function(String name) {
        long address = library.getFunctionAddress(name);
        if (address == 0) throw new IllegalStateException("Streamline lacks " + name);
        return address;
    }

    /** @return the address of a feature's own function, or 0 when the feature does not offer it */
    long featureFunction(int feature, String name) {
        ByteBuffer text = MemoryUtil.memUTF8(name, true);
        ByteBuffer out = MemoryUtil.memCalloc(8);
        try {
            int result = JNI.invokePPI(feature, MemoryUtil.memAddress(text), MemoryUtil.memAddress(out), featureFunction);
            return result == OK ? out.getLong(0) : 0;
        } finally {
            MemoryUtil.memFree(text);
            MemoryUtil.memFree(out);
        }
    }

    /** @return Streamline's result code for whether the feature runs on this physical device */
    int supported(int feature, long adapterInfo) {
        return JNI.invokePI(feature, adapterInfo, supported);
    }

    /** @return an opaque frame token for this frame, or 0 */
    long newFrame(long tokenSlot) {
        return JNI.invokePPI(tokenSlot, MemoryUtil.NULL, frameToken) == OK ? MemoryUtil.memGetAddress(tokenSlot) : 0;
    }

    int tag(long frame, long viewport, long tags, int count, long command) {
        return JNI.invokePPPPI(frame, viewport, tags, count, command, tag);
    }

    int constants(long values, long frame, long viewport) {
        return JNI.invokePPPI(values, frame, viewport, constants);
    }

    /** The feature id passes in a 64-bit register, which the Windows x64 convention reads as its low 32 bits. */
    int evaluate(int feature, long frame, long inputs, int count, long command) {
        return JNI.invokePPPPI(Integer.toUnsignedLong(feature), frame, inputs, count, command, evaluate);
    }

    int free(int feature, long viewport) {
        return JNI.invokePI(feature, viewport, free);
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        int result = JNI.invokeI(shutdown);
        if (result != OK) LOG.warn("slShutdown returned {}", result);
        for (ByteBuffer buffer : natives) MemoryUtil.memFree(buffer);
        natives.clear();
    }

    /** Shuts Streamline down and puts the system Vulkan loader back, for a device that would not start through it. */
    void abandon() {
        try {
            close();
        } catch (RuntimeException | Error failure) {
            LOG.warn("Streamline did not shut down cleanly", failure);
        }
        restoreLoader();
    }

    /**
     * Puts the system Vulkan loader back once the renderer's instance and device exist. Their function tables already
     * point into the interposer, which stays loaded through this object's own handle; anything else that starts Vulkan
     * later, such as another mod's compute device, must not reach Streamline, which follows the last device it saw.
     */
    void restoreLoader() {
        if (!vulkanSwapped) return;
        VK.destroy();
        VK.create();
        vulkanSwapped = false;
    }

    /** Before the interposer became the Vulkan library it can still be dropped. */
    private void release() {
        closed = true;
        for (ByteBuffer buffer : natives) MemoryUtil.memFree(buffer);
        natives.clear();
        if (!vulkanSwapped) library.free();
    }
}
