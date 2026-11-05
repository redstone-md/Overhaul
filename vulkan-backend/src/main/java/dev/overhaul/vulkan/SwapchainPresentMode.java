package dev.overhaul.vulkan;

import org.lwjgl.system.MemoryStack;
import java.nio.IntBuffer;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.VK10.*;
import static dev.overhaul.vulkan.VulkanException.check;

/** Presentation policies supported by the owned swapchain, queried against its actual surface. */
public enum SwapchainPresentMode {
    IMMEDIATE(VK_PRESENT_MODE_IMMEDIATE_KHR),
    MAILBOX(VK_PRESENT_MODE_MAILBOX_KHR),
    FIFO(VK_PRESENT_MODE_FIFO_KHR),
    FIFO_RELAXED(VK_PRESENT_MODE_FIFO_RELAXED_KHR);

    private final int nativeValue;
    SwapchainPresentMode(int nativeValue){this.nativeValue=nativeValue;}
    public int nativeValue(){return nativeValue;}

    public static Set<SwapchainPresentMode> supported(VulkanContext context){
        context.requireOwner();
        if(context.surface()==VK_NULL_HANDLE)throw new IllegalArgumentException("Presentation modes require an attached surface");
        for(int attempt=0;attempt<8;attempt++)try(MemoryStack stack=VulkanStack.push()){
            IntBuffer count=stack.ints(0);
            check(vkGetPhysicalDeviceSurfacePresentModesKHR(context.physicalDevice(),context.surface(),count,null),"surface present mode count");
            int capacity=count.get(0);
            if(capacity<1||capacity>256)throw new IllegalStateException("Invalid surface present mode count: "+Integer.toUnsignedString(capacity));
            IntBuffer modes=stack.mallocInt(capacity);
            int result=vkGetPhysicalDeviceSurfacePresentModesKHR(context.physicalDevice(),context.surface(),count,modes);
            if(result==VK_INCOMPLETE)continue;
            check(result,"surface present modes");
            int written=count.get(0);
            if(written<1||written>capacity)throw new IllegalStateException("Surface present mode count changed outside its allocated range");
            EnumSet<SwapchainPresentMode> supported=EnumSet.noneOf(SwapchainPresentMode.class);
            for(int index=0;index<written;index++)for(SwapchainPresentMode mode:values())
                if(mode.nativeValue==modes.get(index)){supported.add(mode);break;}
            if(!supported.contains(FIFO))throw new IllegalStateException("Surface does not advertise required FIFO presentation");
            return Collections.unmodifiableSet(supported);
        }
        throw new IllegalStateException("Surface presentation modes did not stabilize");
    }
}
