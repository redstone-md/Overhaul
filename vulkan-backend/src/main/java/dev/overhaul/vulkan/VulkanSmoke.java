package dev.overhaul.vulkan;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.CRC32;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.glfw.GLFWVulkan.glfwVulkanSupported;
import static org.lwjgl.vulkan.VK10.*;

/** Deterministic native acceptance checks, distinct from Minecraft integration and benchmarks. */
public final class VulkanSmoke {
    private VulkanSmoke() { }

    public static void main(String[] arguments) throws IOException {
        boolean present = Arrays.asList(arguments).contains("--present");
        boolean validation = Arrays.asList(arguments).contains("--validation");
        if (validation && !VulkanContext.validationAvailable())
            throw new IllegalStateException("--validation requires installed Khronos validation layers");
        System.out.println("Khronos validation layer: " + (VulkanContext.validationAvailable() ? "available" : "unavailable")
            + "; enabled=" + validation);
        Path output = null;
        for (int i = 0; i < arguments.length; i++) {
            if (arguments[i].equals("--output") && i + 1 < arguments.length) output = Path.of(arguments[++i]);
        }
        VulkanContext context = VulkanContext.openHeadless(validation);
        try (context) {
            System.out.println("Vulkan device: " + context.deviceName());
            verifyTransfer(context);
            verifyDraw(context, output);
            verifyOwnership(context);
            AsyncSmoke.verify(context);
        }
        context.assertValidationClean();
        if (present) verifyPresentation(validation);
    }

    private static void verifyTransfer(VulkanContext context) {
        int bytes = 65536;
        ByteBuffer expected = ByteBuffer.allocate(bytes);
        for (int i = 0; i < bytes; i++) expected.put((byte) (i * 37 + 11));
        expected.flip();
        try (GpuAllocator allocator = new GpuAllocator(context);
             ImmediateCommands commands = new ImmediateCommands(context);
             GpuBuffer staging = allocator.hostBuffer(bytes, VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
             GpuBuffer device = allocator.deviceBuffer(bytes, VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
             GpuBuffer readback = allocator.hostBuffer(bytes, VK_BUFFER_USAGE_TRANSFER_DST_BIT)) {
            staging.write(expected);
            commands.copy(staging, device, bytes);
            commands.copy(device, readback, bytes);
            if (!expected.equals(readback.read())) throw new IllegalStateException("GPU staging roundtrip differs");
            System.out.println("GPU device-local staging roundtrip: " + bytes + " bytes verified");
        }
    }

    private static void verifyDraw(VulkanContext context, Path output) throws IOException {
        ByteBuffer vertices = ByteBuffer.allocate(48).order(ByteOrder.nativeOrder());
        vertex(vertices, -0.8f, -0.8f); vertex(vertices, 0.8f, -0.8f); vertex(vertices, 0, 0.8f);
        vertices.flip();
        ByteBuffer indices = ByteBuffer.allocate(12).order(ByteOrder.nativeOrder()).putInt(0).putInt(1).putInt(2).flip();
        float[] identity = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
        try (OffscreenRenderer renderer = new OffscreenRenderer(context, 128, 128);
             GpuMesh mesh = renderer.upload(vertices, indices, 3)) {
            ByteBuffer pixels = renderer.render(mesh, identity);
            int center = (64 * 128 + 64) * 4;
            int corner = 0;
            if (pixels.get(center) == pixels.get(corner) && pixels.get(center + 1) == pixels.get(corner + 1))
                throw new IllegalStateException("indirect triangle did not cover center pixel");
            int covered = 0;
            for (int i = 0; i < pixels.limit(); i += 4) {
                if (Byte.toUnsignedInt(pixels.get(i)) > 20) covered++;
                if (Byte.toUnsignedInt(pixels.get(i + 3)) != 255) throw new IllegalStateException("invalid render alpha");
            }
            if (covered < 4000 || covered > 6000) throw new IllegalStateException("unexpected triangle coverage " + covered);
            ByteBuffer again = renderer.render(mesh, identity);
            if (!pixels.equals(again)) throw new IllegalStateException("repeated rendering differs");
            CRC32 checksum = new CRC32();
            checksum.update(pixels.duplicate());
            if (output != null) writePpm(output, pixels, 128, 128);
            System.out.println("Indexed-indirect RGBA8 draw: " + covered + " covered pixels; CRC32=" + checksum.getValue()
                + "; pipeline cache=" + renderer.pipelineCacheData().length + " bytes");
        }
    }

    private static void vertex(ByteBuffer vertices, float x, float y) {
        vertices.putFloat(x).putFloat(y).putFloat(0.5f).putInt(3);
    }

    private static void verifyOwnership(VulkanContext context) {
        java.util.concurrent.atomic.AtomicReference<Throwable> crossThread = new java.util.concurrent.atomic.AtomicReference<>();
        Thread thread = new Thread(() -> {
            try { context.device(); } catch (Throwable failure) { crossThread.set(failure); }
        }, "overhaul-ownership-check");
        thread.start();
        try { thread.join(); } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ownership check interrupted", interrupted);
        }
        if (!(crossThread.get() instanceof IllegalStateException)) throw new IllegalStateException("cross-thread context access accepted");
        ByteBuffer vertices = ByteBuffer.allocate(48).order(ByteOrder.nativeOrder());
        vertex(vertices, -0.8f, -0.8f); vertex(vertices, 0.8f, -0.8f); vertex(vertices, 0, 0.8f);
        vertices.flip();
        ByteBuffer indices = ByteBuffer.allocate(12).order(ByteOrder.nativeOrder()).putInt(0).putInt(1).putInt(2).flip();
        try (OffscreenRenderer renderer = new OffscreenRenderer(context, 16, 16)) {
            for (int i = 0; i < 16; i++) {
                GpuMesh mesh = renderer.upload(vertices, indices, 3);
                if (renderer.activeMeshCount() != 1) throw new IllegalStateException("mesh tracking count differs");
                mesh.close();
                mesh.close();
                if (renderer.activeMeshCount() != 0) throw new IllegalStateException("retired mesh retained");
            }
            ByteBuffer invalid = ByteBuffer.allocate(4).order(ByteOrder.nativeOrder()).putInt(3).flip();
            try {
                renderer.upload(vertices, invalid, 1);
                throw new IllegalStateException("invalid vertex index accepted");
            } catch (IllegalArgumentException expected) { }
        }
        System.out.println("GPU ownership: cross-thread access rejected; 16 retired meshes released; bounds checked");
    }

    private static void writePpm(Path path, ByteBuffer rgba, int width, int height) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        try (OutputStream stream = Files.newOutputStream(path)) {
            stream.write(("P6\n" + width + " " + height + "\n255\n").getBytes(StandardCharsets.US_ASCII));
            for (int i = 0; i < rgba.limit(); i += 4) {
                stream.write(rgba.get(i)); stream.write(rgba.get(i + 1)); stream.write(rgba.get(i + 2));
            }
        }
    }

    private static void verifyPresentation(boolean validation) {
        if (!glfwInit()) throw new IllegalStateException("GLFW initialization failed");
        long window = 0;
        try {
            if (!glfwVulkanSupported()) throw new IllegalStateException("GLFW Vulkan unavailable");
            glfwDefaultWindowHints();
            glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
            window = glfwCreateWindow(128, 128, "overhaul native verification", 0, 0);
            if (window == 0) throw new IllegalStateException("GLFW no-API window creation failed");
            VulkanContext context = VulkanContext.openWindowed(window, validation);
            try (context;
                 SwapchainPresenter presenter = new SwapchainPresenter(context, 128, 128)) {
                for (int i = 0; i < 4; i++) {
                    glfwPollEvents();
                    if (!presenter.presentClear(0.1f, 0.2f, 0.3f)) presenter.resize(128, 128);
                }
                glfwSetWindowSize(window, 192, 160);
                glfwPollEvents();
                presenter.resize(192, 160);
                if (!presenter.presentClear(0.3f, 0.2f, 0.1f)) throw new IllegalStateException("presentation failed after resize");
                System.out.println("GLFW no-API swapchain: present and resize verified " + presenter.width() + "x" + presenter.height());
            }
            context.assertValidationClean();
        } finally {
            if (window != 0) glfwDestroyWindow(window);
            glfwTerminate();
        }
    }
}
