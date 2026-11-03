package dev.overhaul.vulkan;

import org.lwjgl.PointerBuffer;
import java.util.ArrayList;
import java.util.List;

/** Copies runtime-owned native extension names so no mutable native list crosses the provider boundary. */
final class SurfaceExtensions {
    private SurfaceExtensions() { }

    static List<String> copy(PointerBuffer extensions) {
        if (extensions == null || !extensions.hasRemaining()) throw new IllegalStateException("window runtime returned no Vulkan surface extensions");
        List<String> names = new ArrayList<>(extensions.remaining());
        for (int i = extensions.position(); i < extensions.limit(); i++) names.add(extensions.getStringUTF8(i));
        return List.copyOf(names);
    }
}
