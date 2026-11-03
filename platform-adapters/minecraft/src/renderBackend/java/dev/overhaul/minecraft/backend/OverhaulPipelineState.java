package dev.overhaul.minecraft.backend;

import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import dev.overhaul.vulkan.NativeGraphicsPipeline;
import java.util.ArrayList;
import java.util.List;
import static org.lwjgl.vulkan.VK10.*;

/** Pure translation of the resolved RenderPearl 26.3 pipeline schema. */
final class OverhaulPipelineState {
    private OverhaulPipelineState() {}
    static NativeGraphicsPipeline.Description describe(BackendRenderPipeline.CreateInfo info,List<NativeGraphicsPipeline.Shader> shaders,
                                                       long descriptorLayout,List<Integer> formats,int depthFormat) {
        if(info.colorTargetStates().size()>formats.size())throw new IllegalArgumentException("Pipeline requires more color targets than the pass provides");
        var bindings=info.vertexBuffers().stream().map(value->new NativeGraphicsPipeline.Binding(value.bufferSlot(),value.stride(),value.stepRate())).toList();
        var attributes=info.attribBindings().stream().map(value->new NativeGraphicsPipeline.Attribute(value.location(),value.bufferSlot(),OverhaulFormats.format(value.format()),value.offset())).toList();
        List<NativeGraphicsPipeline.Color> colors=new ArrayList<>();
        for(int index=0;index<formats.size();index++) {
            ColorTargetState target=index<info.colorTargetStates().size()?info.colorTargetStates().get(index):null;
            if(target!=null&&target.format()!=null&&formats.get(index)!=VK_FORMAT_UNDEFINED&&OverhaulFormats.format(target.format())!=formats.get(index))throw new IllegalArgumentException("Pipeline/pass color format mismatch at "+index);
            int mask=target==null?0:(target.writeRed()?VK_COLOR_COMPONENT_R_BIT:0)|(target.writeGreen()?VK_COLOR_COMPONENT_G_BIT:0)
                    |(target.writeBlue()?VK_COLOR_COMPONENT_B_BIT:0)|(target.writeAlpha()?VK_COLOR_COMPONENT_A_BIT:0);
            var blend=target==null?null:target.blendFunction().orElse(null);
            colors.add(blend==null?new NativeGraphicsPipeline.Color(false,VK_BLEND_FACTOR_ONE,VK_BLEND_FACTOR_ZERO,VK_BLEND_OP_ADD,VK_BLEND_FACTOR_ONE,VK_BLEND_FACTOR_ZERO,VK_BLEND_OP_ADD,mask)
                    :new NativeGraphicsPipeline.Color(true,factor(blend.color().sourceFactor()),factor(blend.color().destFactor()),operation(blend.color().op()),
                    factor(blend.alpha().sourceFactor()),factor(blend.alpha().destFactor()),operation(blend.alpha().op()),mask));
        }
        DepthStencilState state=info.depthStencilState();
        if(state!=null&&depthFormat==VK_FORMAT_UNDEFINED)throw new IllegalArgumentException("Pipeline requires a depth attachment");
        var depth=state==null?new NativeGraphicsPipeline.Depth(false,false,VK_COMPARE_OP_ALWAYS,0,0)
                :new NativeGraphicsPipeline.Depth(true,state.writeDepth(),compare(state.depthTest()),state.depthBiasConstant(),state.depthBiasScaleFactor());
        return new NativeGraphicsPipeline.Description(shaders,bindings,attributes,new long[]{descriptorLayout},info.pushConstantsSize(),topology(info.primitiveTopology()),
                info.polygonMode()==PolygonMode.FILL?VK_POLYGON_MODE_FILL:VK_POLYGON_MODE_LINE,info.cull()?VK_CULL_MODE_BACK_BIT:VK_CULL_MODE_NONE,
                VK_FRONT_FACE_CLOCKWISE,depth,colors,true,0,0);
    }
    static int descriptor(UniformType type){return switch(type){case UNIFORM_BUFFER->VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;case COMBINED_IMAGE_SAMPLER->VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;case TEXEL_BUFFER->VK_DESCRIPTOR_TYPE_UNIFORM_TEXEL_BUFFER;};}
    static int topology(PrimitiveTopology topology){return switch(topology){case LINES,TRIANGLES,QUADS->VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;case DEBUG_LINES->VK_PRIMITIVE_TOPOLOGY_LINE_LIST;case DEBUG_LINE_STRIP->VK_PRIMITIVE_TOPOLOGY_LINE_STRIP;case POINTS->VK_PRIMITIVE_TOPOLOGY_POINT_LIST;case TRIANGLE_STRIP->VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;case TRIANGLE_FAN->VK_PRIMITIVE_TOPOLOGY_TRIANGLE_FAN;};}
    static int compare(CompareOp compare){return switch(compare){case ALWAYS_PASS->VK_COMPARE_OP_ALWAYS;case LESS_THAN->VK_COMPARE_OP_LESS;case LESS_THAN_OR_EQUAL->VK_COMPARE_OP_LESS_OR_EQUAL;case EQUAL->VK_COMPARE_OP_EQUAL;case NOT_EQUAL->VK_COMPARE_OP_NOT_EQUAL;case GREATER_THAN_OR_EQUAL->VK_COMPARE_OP_GREATER_OR_EQUAL;case GREATER_THAN->VK_COMPARE_OP_GREATER;case NEVER_PASS->VK_COMPARE_OP_NEVER;};}
    static int operation(BlendOp operation){return switch(operation){case ADD->VK_BLEND_OP_ADD;case SUBTRACT->VK_BLEND_OP_SUBTRACT;case REVERSE_SUBTRACT->VK_BLEND_OP_REVERSE_SUBTRACT;case MIN->VK_BLEND_OP_MIN;case MAX->VK_BLEND_OP_MAX;};}
    static int factor(BlendFactor factor){return switch(factor){
        case ZERO->VK_BLEND_FACTOR_ZERO;case ONE->VK_BLEND_FACTOR_ONE;case SRC_COLOR->VK_BLEND_FACTOR_SRC_COLOR;case ONE_MINUS_SRC_COLOR->VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR;
        case DST_COLOR->VK_BLEND_FACTOR_DST_COLOR;case ONE_MINUS_DST_COLOR->VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR;case SRC_ALPHA->VK_BLEND_FACTOR_SRC_ALPHA;case ONE_MINUS_SRC_ALPHA->VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
        case DST_ALPHA->VK_BLEND_FACTOR_DST_ALPHA;case ONE_MINUS_DST_ALPHA->VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA;case CONSTANT_COLOR->VK_BLEND_FACTOR_CONSTANT_COLOR;case ONE_MINUS_CONSTANT_COLOR->VK_BLEND_FACTOR_ONE_MINUS_CONSTANT_COLOR;
        case CONSTANT_ALPHA->VK_BLEND_FACTOR_CONSTANT_ALPHA;case ONE_MINUS_CONSTANT_ALPHA->VK_BLEND_FACTOR_ONE_MINUS_CONSTANT_ALPHA;case SRC_ALPHA_SATURATE->VK_BLEND_FACTOR_SRC_ALPHA_SATURATE;};}
}
