package dev.overhaul.vulkan;

import org.junit.jupiter.api.Test;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SurfaceExtensionsTest {
    @Test void copiesBorrowedNativeNamesWithoutRetainingNativeStorageOrMutatingItsView() {
        List<String> copied;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer runtimeOwned = stack.pointers(stack.UTF8("ignored"), stack.UTF8("VK_KHR_surface"), stack.UTF8("VK_KHR_win32_surface"));
            runtimeOwned.position(1);
            copied = SurfaceExtensions.copy(runtimeOwned);
            assertEquals(1, runtimeOwned.position());
            runtimeOwned.put(1, stack.UTF8("replacement"));
        }
        assertEquals(List.of("VK_KHR_surface", "VK_KHR_win32_surface"), copied);
        assertThrows(UnsupportedOperationException.class, () -> copied.add("VK_EXT_debug_utils"));
    }

    @Test void rejectsMissingRuntimeExtensionsAndNullWindowHandles() {
        assertThrows(IllegalStateException.class, () -> SurfaceExtensions.copy(null));
        assertThrows(IllegalArgumentException.class, () -> new GlfwSurfaceProvider(0));
        assertThrows(NullPointerException.class, () -> VulkanContext.openWindowed((SurfaceProvider) null));
    }
}
