package dev.overhaul.vulkan;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Validated buffer-to-image regions recorded as one copy command. The caller keeps regions from overlapping. */
public final class ImageCopies {
    private final VulkanContext context;
    private final GpuBuffer source;
    private final GpuImage target;
    private final List<ImageRegion> regions = new ArrayList<>();
    private long[] offsets = new long[16];
    private int[] pitches = new int[16];

    public ImageCopies(VulkanContext context, GpuBuffer source, GpuImage target) {
        this.context = Objects.requireNonNull(context, "context");
        this.source = Objects.requireNonNull(source, "source");
        this.target = Objects.requireNonNull(target, "target");
    }

    public void add(long offset, int pitch, ImageRegion region) {
        ImageCommand.validateImage(context, target, region, VK_IMAGE_USAGE_TRANSFER_DST_BIT);
        ImageCommand.validateBuffer(context, source, offset, region.byteSize(target, pitch), VK_BUFFER_USAGE_TRANSFER_SRC_BIT, target);
        int index = regions.size();
        if (index == offsets.length) {
            offsets = Arrays.copyOf(offsets, index * 2);
            pitches = Arrays.copyOf(pitches, index * 2);
        }
        offsets[index] = offset;
        pitches[index] = pitch;
        regions.add(region);
    }

    public int size() { return regions.size(); }

    GpuBuffer source() { return source; }
    GpuImage target() { return target; }
    ImageRegion region(int index) { return regions.get(index); }
    long offset(int index) { return offsets[index]; }
    int pitch(int index) { return pitches[index]; }

    /** One region for every mip and layer the copies touch; transitions cover the whole subresource. */
    List<ImageRegion> subresources() {
        List<ImageRegion> touched = new ArrayList<>();
        for (ImageRegion region : regions) {
            boolean known = false;
            for (ImageRegion existing : touched) {
                known |= existing.mipLevel() == region.mipLevel() && existing.arrayLayer() == region.arrayLayer();
            }
            if (!known) touched.add(region);
        }
        return touched;
    }
}
