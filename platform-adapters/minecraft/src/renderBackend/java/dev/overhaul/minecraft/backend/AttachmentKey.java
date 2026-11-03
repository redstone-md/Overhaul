package dev.overhaul.minecraft.backend;

import dev.overhaul.vulkan.GpuImage;
import java.util.List;

/** Immutable native framebuffer identity, including render-pass load semantics and unused slots. */
record AttachmentKey(List<Target> targets,List<Integer> colors,int depth,int width,int height,int layers) {
    AttachmentKey {
        targets=List.copyOf(targets); colors=List.copyOf(colors);
    }
    record Target(GpuImage image,int mip,int layer,int format,int aspects,boolean depth,boolean clear) {}
}
