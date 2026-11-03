package dev.overhaul.vulkan;

import dev.overhaul.shader.compile.SpirvBinary;
import java.util.List;
import static org.lwjgl.vulkan.VK10.*;

/** Built-in defaults over the authoritative general native graphics pipeline builder. */
final class GraphicsPipeline implements AutoCloseable {
    record Binding(int number,int stride,int rate) {}
    record Attribute(int location,int binding,int format,int offset) {}
    record Blend(boolean enabled,int sourceRgb,int destinationRgb,int sourceAlpha,int destinationAlpha) {
        static Blend disabled(){return new Blend(false,VK_BLEND_FACTOR_ONE,VK_BLEND_FACTOR_ZERO,VK_BLEND_FACTOR_ONE,VK_BLEND_FACTOR_ZERO);}
    }
    private final NativeGraphicsPipeline nativePipeline;
    long pipeline;
    long layout;

    GraphicsPipeline(VulkanContext context,long renderPass,long descriptorLayout,int width,int height,SpirvBinary vertex,SpirvBinary fragment,
                     List<Binding> bindings,List<Attribute> attributes,Blend blend){
        this(context,renderPass,descriptorLayout,width,height,vertex,fragment,bindings,attributes,blend,true);
    }
    GraphicsPipeline(VulkanContext context,long renderPass,long descriptorLayout,int width,int height,SpirvBinary vertex,SpirvBinary fragment,
                     List<Binding> bindings,List<Attribute> attributes,Blend blend,boolean depthWrite){
        var stages=List.of(new NativeGraphicsPipeline.Shader(VK_SHADER_STAGE_VERTEX_BIT,"main",vertex.directBuffer()),
                new NativeGraphicsPipeline.Shader(VK_SHADER_STAGE_FRAGMENT_BIT,"main",fragment.directBuffer()));
        var input=bindings.stream().map(value->new NativeGraphicsPipeline.Binding(value.number(),value.stride(),value.rate()==VK_VERTEX_INPUT_RATE_INSTANCE?1:0)).toList();
        var elements=attributes.stream().map(value->new NativeGraphicsPipeline.Attribute(value.location(),value.binding(),value.format(),value.offset())).toList();
        int mask=VK_COLOR_COMPONENT_R_BIT|VK_COLOR_COMPONENT_G_BIT|VK_COLOR_COMPONENT_B_BIT|VK_COLOR_COMPONENT_A_BIT;
        var color=new NativeGraphicsPipeline.Color(blend.enabled(),blend.sourceRgb(),blend.destinationRgb(),VK_BLEND_OP_ADD,
                blend.sourceAlpha(),blend.destinationAlpha(),VK_BLEND_OP_ADD,mask);
        nativePipeline=new NativeGraphicsPipeline(context,renderPass,new NativeGraphicsPipeline.Description(stages,input,elements,new long[]{descriptorLayout},0,
                VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST,VK_POLYGON_MODE_FILL,VK_CULL_MODE_NONE,VK_FRONT_FACE_COUNTER_CLOCKWISE,
                new NativeGraphicsPipeline.Depth(true,depthWrite,VK_COMPARE_OP_LESS,0,0),List.of(color),false,width,height));
        pipeline=nativePipeline.handle();layout=nativePipeline.layout();
    }
    byte[] cacheData(){return nativePipeline.cacheData();}
    @Override public void close(){nativePipeline.close();pipeline=layout=0;}
}