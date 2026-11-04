package dev.overhaul.minecraft.blaze;

import static org.lwjgl.vulkan.VK10.*;

/** One authoritative mapping of the captured GL integer/normalized input semantics. */
public final class BlazeVertexFormats {
    public enum Type { FLOAT,UBYTE,BYTE,USHORT,SHORT,UINT,INT }
    private BlazeVertexFormats(){}
    public static int format(Type type,int count,boolean normalized,boolean integerInput){
        if(count<1||count>4)throw new UnsupportedOperationException("Unsupported attribute width: "+count);
        if(!normalized&&!integerInput&&type!=Type.FLOAT)throw new UnsupportedOperationException("Scaled floating attribute conversion is not implemented: "+type);
        if(normalized&&(type==Type.INT||type==Type.UINT))throw new UnsupportedOperationException("Normalized 32-bit attribute conversion is not implemented: "+type);
        int[] formats=switch(type){
            case FLOAT->new int[]{VK_FORMAT_R32_SFLOAT,VK_FORMAT_R32G32_SFLOAT,VK_FORMAT_R32G32B32_SFLOAT,VK_FORMAT_R32G32B32A32_SFLOAT};
            case UBYTE->normalized
                    ?new int[]{VK_FORMAT_R8_UNORM,VK_FORMAT_R8G8_UNORM,VK_FORMAT_R8G8B8_UNORM,VK_FORMAT_R8G8B8A8_UNORM}
                    :new int[]{VK_FORMAT_R8_UINT,VK_FORMAT_R8G8_UINT,VK_FORMAT_R8G8B8_UINT,VK_FORMAT_R8G8B8A8_UINT};
            case BYTE->normalized
                    ?new int[]{VK_FORMAT_R8_SNORM,VK_FORMAT_R8G8_SNORM,VK_FORMAT_R8G8B8_SNORM,VK_FORMAT_R8G8B8A8_SNORM}
                    :new int[]{VK_FORMAT_R8_SINT,VK_FORMAT_R8G8_SINT,VK_FORMAT_R8G8B8_SINT,VK_FORMAT_R8G8B8A8_SINT};
            case USHORT->normalized
                    ?new int[]{VK_FORMAT_R16_UNORM,VK_FORMAT_R16G16_UNORM,VK_FORMAT_R16G16B16_UNORM,VK_FORMAT_R16G16B16A16_UNORM}
                    :new int[]{VK_FORMAT_R16_UINT,VK_FORMAT_R16G16_UINT,VK_FORMAT_R16G16B16_UINT,VK_FORMAT_R16G16B16A16_UINT};
            case SHORT->normalized
                    ?new int[]{VK_FORMAT_R16_SNORM,VK_FORMAT_R16G16_SNORM,VK_FORMAT_R16G16B16_SNORM,VK_FORMAT_R16G16B16A16_SNORM}
                    :new int[]{VK_FORMAT_R16_SINT,VK_FORMAT_R16G16_SINT,VK_FORMAT_R16G16B16_SINT,VK_FORMAT_R16G16B16A16_SINT};
            case UINT->new int[]{VK_FORMAT_R32_UINT,VK_FORMAT_R32G32_UINT,VK_FORMAT_R32G32B32_UINT,VK_FORMAT_R32G32B32A32_UINT};
            case INT->new int[]{VK_FORMAT_R32_SINT,VK_FORMAT_R32G32_SINT,VK_FORMAT_R32G32B32_SINT,VK_FORMAT_R32G32B32A32_SINT};
        };
        return formats[count-1];
    }
}
