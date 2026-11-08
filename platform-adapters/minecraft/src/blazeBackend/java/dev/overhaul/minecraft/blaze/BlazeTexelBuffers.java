package dev.overhaul.minecraft.blaze;

import dev.overhaul.vulkan.GpuBuffer;
import dev.overhaul.vulkan.VulkanContext;
import dev.overhaul.vulkan.VulkanStack;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferViewCreateInfo;
import static dev.overhaul.vulkan.VulkanException.check;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Texture names bound to {@code GL_TEXTURE_BUFFER} read a mod's buffer object through a Vulkan texel buffer view.
 * Views are cached by buffer, range and format; one whose buffer was replaced ages out and retires after its frames.
 */
final class BlazeTexelBuffers implements AutoCloseable {
    static final int TARGET = 0x8C2A;
    private static final int UNITS = 32, MAX_VIEWS = 64;
    private record Source(int buffer, int format, long offset, long size) {}
    private record Key(long buffer, long offset, long size, int format) {}

    private final VulkanContext context;
    private final BlazeQueue queue;
    private final Int2ObjectOpenHashMap<Source> sources = new Int2ObjectOpenHashMap<>();
    private final int[] units = new int[UNITS];
    private final LinkedHashMap<Key, Long> views = new LinkedHashMap<>(16, .75f, true);
    private boolean closed;

    BlazeTexelBuffers(VulkanContext context, BlazeQueue queue) { this.context = context; this.queue = queue; }

    void bind(int unit, int name) { if (unit >= 0 && unit < UNITS) units[unit] = name; }

    /** {@code size} below zero reads the buffer from {@code offset} to its end. */
    void define(int unit, int internal, int buffer, long offset, long size) {
        if (unit < 0 || unit >= UNITS || units[unit] == 0) return;
        int format = format(internal);
        if (format == VK_FORMAT_UNDEFINED) {
            BlazeGlBoundary.reportOnce("texel format " + internal, "A mod's texture buffer has a format with no texel view: 0x" + Integer.toHexString(internal));
            sources.remove(units[unit]);
            return;
        }
        if (buffer == 0) sources.remove(units[unit]);
        else sources.put(units[unit], new Source(buffer, format, offset, size));
    }

    void delete(int name) {
        sources.remove(name);
        for (int unit = 0; unit < UNITS; unit++) if (units[unit] == name) units[unit] = 0;
    }

    /** The texel view a sampler on this unit reads, or null when the unit names no buffer with storage. */
    BlazeTextures.Sampled sampled(BlazeRenderer renderer, int unit) {
        if (closed || unit < 0 || unit >= UNITS) return null;
        Source source = sources.get(units[unit]);
        if (source == null) return null;
        BlazeGlBuffers.Placed placed = BlazeGlBoundary.buffers().placed(renderer, source.buffer(), false);
        if (placed == null) return null;
        GpuBuffer buffer = placed.buffer();
        long offset = placed.base() + source.offset();
        long size = source.size() < 0 ? buffer.size() - offset : source.size();
        if (size <= 0 || offset + size > buffer.size()) return null;
        Key key = new Key(buffer.handle(), offset, size, source.format());
        Long view = views.get(key);
        if (view == null) {
            view = create(key);
            views.put(key, view);
            if (views.size() > MAX_VIEWS) evict();
        }
        return BlazeTextures.Sampled.texel(buffer, view);
    }

    private long create(Key key) {
        try (MemoryStack stack = VulkanStack.push()) {
            var pointer = stack.mallocLong(1);
            check(vkCreateBufferView(context.device(), VkBufferViewCreateInfo.calloc(stack).sType$Default()
                    .buffer(key.buffer()).format(key.format()).offset(key.offset()).range(key.size()), null, pointer), "Blaze texel buffer view");
            return pointer.get(0);
        }
    }

    private void evict() {
        Iterator<Map.Entry<Key, Long>> oldest = views.entrySet().iterator();
        long view = oldest.next().getValue();
        oldest.remove();
        queue.retire(() -> vkDestroyBufferView(context.device(), view, null));
    }

    private static int format(int internal) {
        return switch (internal) {
            case 0x8232 -> VK_FORMAT_R8_UINT; case 0x8236 -> VK_FORMAT_R32_UINT; case 0x823C -> VK_FORMAT_R32G32_UINT;
            case 0x8D71 -> VK_FORMAT_R32G32B32_UINT; case 0x8D70 -> VK_FORMAT_R32G32B32A32_UINT;
            case 0x8235 -> VK_FORMAT_R32_SINT; case 0x823B -> VK_FORMAT_R32G32_SINT; case 0x8D82 -> VK_FORMAT_R32G32B32A32_SINT;
            case 0x822E -> VK_FORMAT_R32_SFLOAT; case 0x8230 -> VK_FORMAT_R32G32_SFLOAT; case 0x8815 -> VK_FORMAT_R32G32B32_SFLOAT;
            case 0x8814 -> VK_FORMAT_R32G32B32A32_SFLOAT; case 0x8229 -> VK_FORMAT_R8_UNORM; case 0x8058 -> VK_FORMAT_R8G8B8A8_UNORM;
            case 0x8D7C -> VK_FORMAT_R8G8B8A8_UINT; case 0x8234 -> VK_FORMAT_R16_UINT; case 0x8D76 -> VK_FORMAT_R16G16B16A16_UINT;
            default -> VK_FORMAT_UNDEFINED;
        };
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        for (long view : views.values()) vkDestroyBufferView(context.device(), view, null);
        views.clear();
        sources.clear();
    }
}
