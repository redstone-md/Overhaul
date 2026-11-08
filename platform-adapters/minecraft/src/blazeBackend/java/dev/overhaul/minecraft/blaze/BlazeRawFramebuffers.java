package dev.overhaul.minecraft.blaze;

import dev.overhaul.vulkan.VulkanStack;

import dev.overhaul.vulkan.GpuAllocator;
import dev.overhaul.vulkan.VulkanContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkFormatProperties;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Framebuffers that mods assemble through raw OpenGL. Attachments are only recorded until a framebuffer is used,
 * because mods attach textures in any order and may redefine them later; a real target is then built over their images,
 * with one colour location per draw buffer.
 */
final class BlazeRawFramebuffers implements AutoCloseable {
    private static final int COLOR_0 = 0x8CE0, COLOR_LAST = 0x8CFF, DEPTH = 0x8D00, STENCIL = 0x8D20, DEPTH_STENCIL = 0x821A;

    private record Slot(int texture, int level) {}

    private static final int NONE = 0;
    private static final int[] FIRST_ONLY = {COLOR_0};

    private static final class Assembly {
        final Slot[] colors = new Slot[COLOR_LAST - COLOR_0 + 1];
        Slot depth;
        /** The attachment each fragment output location writes, as glDrawBuffers names it; a new framebuffer writes only the first. */
        int[] drawBuffers = FIRST_ONLY;
        BlazeTarget target;
        List<BlazeTextures.Attachment> builtColors;
        BlazeTextures.Attachment builtDepth;
    }

    private final VulkanContext context;
    private final GpuAllocator allocator;
    private final BlazeQueue queue;
    private final BlazeTextures textures;
    private final Map<Integer, Assembly> assemblies = new HashMap<>();
    private final Map<Integer, Integer> renderbuffers = new HashMap<>();
    private int boundRenderbuffer;

    BlazeRawFramebuffers(VulkanContext context, GpuAllocator allocator, BlazeQueue queue, BlazeTextures textures) {
        this.context = context;
        this.allocator = allocator;
        this.queue = queue;
        this.textures = textures;
    }

    void attachTexture(int framebuffer, int attachment, int texture, int level) {
        Slot slot = texture == 0 ? null : new Slot(texture, level);
        Assembly assembly = assemblies.computeIfAbsent(framebuffer, name -> new Assembly());
        if (attachment >= COLOR_0 && attachment <= COLOR_LAST) assembly.colors[attachment - COLOR_0] = slot;
        else if (attachment == DEPTH || attachment == DEPTH_STENCIL) assembly.depth = slot;
        else if (attachment != STENCIL) throw new UnsupportedOperationException("Framebuffer attachment point: 0x" + Integer.toHexString(attachment));
    }

    /** glDrawBuffers: output location {@code index} writes attachment {@code buffers[index]}, or nothing for GL_NONE. */
    void drawBuffers(int framebuffer, int[] buffers) {
        int[] kept = buffers.clone();
        for (int index = 0; index < kept.length; index++) {
            if (kept[index] == NONE || kept[index] >= COLOR_0 && kept[index] <= COLOR_LAST) continue;
            BlazeGlBoundary.reportOnce("raw draw buffer " + kept[index], "A mod named draw buffer 0x" + Integer.toHexString(kept[index]) + " on its own framebuffer, which has no such attachment; that output is dropped");
            kept[index] = NONE;
        }
        assemblies.computeIfAbsent(framebuffer, name -> new Assembly()).drawBuffers = kept;
    }

    void attachRenderbuffer(int framebuffer, int attachment, int renderbuffer) {
        attachTexture(framebuffer, attachment, renderbuffer == 0 ? 0 : renderbufferTexture(renderbuffer), 0);
    }

    void bindRenderbuffer(int name) { boundRenderbuffer = name; }

    /** A renderbuffer is a one-level texture nobody samples. */
    void storage(int name, int internal, int width, int height) {
        textures.renderbufferStorage(renderbufferTexture(name == 0 ? boundRenderbuffer : name), internal, width, height);
    }

    void deleteRenderbuffer(int name) {
        Integer texture = renderbuffers.remove(name);
        if (texture != null) textures.delete(texture);
        if (boundRenderbuffer == name) boundRenderbuffer = 0;
    }

    private int renderbufferTexture(int name) { return renderbuffers.computeIfAbsent(name, key -> textures.generate()); }

    void delete(int framebuffer) {
        Assembly assembly = assemblies.remove(framebuffer);
        if (assembly != null && assembly.target != null) assembly.target.close();
    }

    /** The target to draw to or read from, or null while the framebuffer has nothing renderable attached. */
    BlazeTarget target(int framebuffer) {
        Assembly assembly = assemblies.get(framebuffer);
        if (assembly == null) {
            BlazeGlBoundary.reportOnce("raw framebuffer without attachments", "A mod used a raw framebuffer with nothing renderable attached; what it renders there is dropped");
            return null;
        }
        List<BlazeTextures.Attachment> colors = colors(assembly);
        BlazeTextures.Attachment depth = resolve(assembly.depth);
        if (assembly.target != null && same(colors, assembly.builtColors) && same(depth, assembly.builtDepth)) return assembly.target;
        if (assembly.target != null) {
            assembly.target.close();
            assembly.target = null;
        }
        List<BlazeTextures.Attachment> written = new ArrayList<>(colors.size());
        boolean anyColor = false;
        for (BlazeTextures.Attachment color : colors) {
            BlazeTextures.Attachment kept = color != null && renderable(color, VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT) ? color : null;
            written.add(kept);
            anyColor |= kept != null;
        }
        BlazeTextures.Attachment drawnDepth = depth != null && renderable(depth, VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT) ? depth : null;
        if (!anyColor && drawnDepth == null) {
            BlazeGlBoundary.reportOnce("raw framebuffer without attachments", "A mod used a raw framebuffer with nothing renderable attached; what it renders there is dropped");
            return null;
        }
        assembly.target = new BlazeTarget(context, allocator, anyColor ? written : List.of(), drawnDepth);
        assembly.target.attach(queue);
        assembly.builtColors = colors;
        assembly.builtDepth = depth;
        return assembly.target;
    }

    /** The attachment behind each output location, in draw buffer order; trailing locations writing nothing are left off. */
    private List<BlazeTextures.Attachment> colors(Assembly assembly) {
        List<BlazeTextures.Attachment> colors = new ArrayList<>(assembly.drawBuffers.length);
        for (int buffer : assembly.drawBuffers) colors.add(buffer == NONE ? null : resolve(assembly.colors[buffer - COLOR_0]));
        while (!colors.isEmpty() && colors.get(colors.size() - 1) == null) colors.remove(colors.size() - 1);
        return colors;
    }

    private BlazeTextures.Attachment resolve(Slot slot) { return slot == null ? null : textures.attachment(slot.texture(), slot.level()); }

    private static boolean same(List<BlazeTextures.Attachment> left, List<BlazeTextures.Attachment> right) {
        if (right == null || left.size() != right.size()) return false;
        for (int index = 0; index < left.size(); index++) if (!same(left.get(index), right.get(index))) return false;
        return true;
    }

    private static boolean same(BlazeTextures.Attachment left, BlazeTextures.Attachment right) {
        return left == null ? right == null : right != null && left.image() == right.image() && left.mip() == right.mip() && left.layer() == right.layer();
    }

    /** Shaders here output floats, so an integer target would receive undefined values. */
    private boolean renderable(BlazeTextures.Attachment attachment, int feature) {
        var format = attachment.image().format();
        boolean supported;
        try (MemoryStack stack = VulkanStack.push()) {
            var properties = VkFormatProperties.malloc(stack);
            vkGetPhysicalDeviceFormatProperties(context.physicalDevice(), format.vkFormat(), properties);
            supported = (properties.optimalTilingFeatures() & feature) != 0 && !format.name().endsWith("INT");
        }
        if (!supported) BlazeGlBoundary.reportOnce("raw target format " + format, "A mod attached a texture of format " + format + ", which cannot be a render target here; its output is dropped");
        return supported;
    }

    @Override public void close() {
        for (Assembly assembly : new ArrayList<>(assemblies.values())) if (assembly.target != null) assembly.target.close();
        assemblies.clear();
        renderbuffers.clear();
    }
}
