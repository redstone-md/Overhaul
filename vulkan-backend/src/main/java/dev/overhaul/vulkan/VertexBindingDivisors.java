package dev.overhaul.vulkan;

import java.util.List;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.Pointer;
import org.lwjgl.vulkan.VkPipelineVertexInputStateCreateInfo;
import static org.lwjgl.system.MemoryUtil.memPutAddress;
import static org.lwjgl.system.MemoryUtil.memPutInt;
import static org.lwjgl.vulkan.EXTVertexAttributeDivisor.VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_DIVISOR_STATE_CREATE_INFO_EXT;

/**
 * Stable EXT native ABI, independent of LWJGL's Vulkan 1.4 struct promotion.
 * The 3.3.6 EXT setter's Java signature references a promoted Buffer class absent
 * from 3.3.1/3.3.3, despite identical Vulkan structure layouts in every version.
 */
final class VertexBindingDivisors {
    private static final int DESCRIPTION_BYTES=2*Integer.BYTES;
    private static final int NEXT_OFFSET=Pointer.POINTER_SIZE;
    private static final int COUNT_OFFSET=NEXT_OFFSET+Pointer.POINTER_SIZE;
    private static final int DESCRIPTIONS_OFFSET=align(COUNT_OFFSET+Integer.BYTES,Pointer.POINTER_SIZE);
    private static final int STATE_BYTES=DESCRIPTIONS_OFFSET+Pointer.POINTER_SIZE;

    private VertexBindingDivisors(){}

    static void attach(VkPipelineVertexInputStateCreateInfo input,List<NativeGraphicsPipeline.Binding> bindings,MemoryStack stack){
        int count=0;
        for(var binding:bindings)if(binding.stepRate()>1)count++;
        if(count==0)return;
        long descriptions=stack.ncalloc(Integer.BYTES,count,DESCRIPTION_BYTES);
        int index=0;
        for(var binding:bindings){
            if(binding.stepRate()<=1)continue;
            long address=descriptions+(long)index++*DESCRIPTION_BYTES;
            memPutInt(address,binding.slot());memPutInt(address+Integer.BYTES,binding.stepRate());
        }
        long state=stack.ncalloc(Pointer.POINTER_SIZE,1,STATE_BYTES);
        memPutInt(state,VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_DIVISOR_STATE_CREATE_INFO_EXT);
        memPutAddress(state+NEXT_OFFSET,input.pNext());
        memPutInt(state+COUNT_OFFSET,count);
        memPutAddress(state+DESCRIPTIONS_OFFSET,descriptions);
        input.pNext(state);
    }

    private static int align(int bytes,int alignment){return (bytes+alignment-1)/alignment*alignment;}
}
