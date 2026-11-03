package dev.overhaul.vulkan;

import dev.overhaul.shader.compat.UnsupportedShaderException;
import dev.overhaul.shader.pack.ShaderPackProgram;
import dev.overhaul.shader.pack.ShaderPackProperties;
import java.util.List;
import java.util.Map;
import static org.lwjgl.vulkan.VK10.*;

/** Validates every supplied state field before creating a single-target native pipeline. */
final class PackPipelineState {
    private static final Map<String,Integer> FACTORS=Map.ofEntries(Map.entry("ZERO",VK_BLEND_FACTOR_ZERO),Map.entry("ONE",VK_BLEND_FACTOR_ONE),
            Map.entry("SRC_COLOR",VK_BLEND_FACTOR_SRC_COLOR),Map.entry("ONE_MINUS_SRC_COLOR",VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR),
            Map.entry("DST_COLOR",VK_BLEND_FACTOR_DST_COLOR),Map.entry("ONE_MINUS_DST_COLOR",VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR),
            Map.entry("SRC_ALPHA",VK_BLEND_FACTOR_SRC_ALPHA),Map.entry("ONE_MINUS_SRC_ALPHA",VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA),
            Map.entry("DST_ALPHA",VK_BLEND_FACTOR_DST_ALPHA),Map.entry("ONE_MINUS_DST_ALPHA",VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA),
            Map.entry("CONSTANT_COLOR",VK_BLEND_FACTOR_CONSTANT_COLOR),Map.entry("ONE_MINUS_CONSTANT_COLOR",VK_BLEND_FACTOR_ONE_MINUS_CONSTANT_COLOR),
            Map.entry("CONSTANT_ALPHA",VK_BLEND_FACTOR_CONSTANT_ALPHA),Map.entry("ONE_MINUS_CONSTANT_ALPHA",VK_BLEND_FACTOR_ONE_MINUS_CONSTANT_ALPHA),
            Map.entry("SRC_ALPHA_SATURATE",VK_BLEND_FACTOR_SRC_ALPHA_SATURATE));
    private PackPipelineState() {}

    static GraphicsPipeline.Blend validate(ShaderPackProgram program,ShaderPackProperties.ProgramState state) {
        if (!state.enabled()) throw unsupported("Program is disabled by shader options");
        if (!program.framebuffer().colorTargets().equals(List.of(0))) throw unsupported("Pack diagnostic renderer currently requires one colortex0 output");
        program.requireAlphaTest(state.alphaTest().orElse(null));
        if (!state.flips().isEmpty()) throw unsupported("Pack buffer flips require ping-pong render-graph execution");
        for (String target:state.blends().keySet()) if (!target.equals("*") && !target.equals("colortex0")) throw unsupported("Blend target is absent from this render pass: "+target);
        var blend=state.blends().getOrDefault("colortex0",state.blends().get("*"));
        if (blend==null || !blend.enabled()) return GraphicsPipeline.Blend.disabled();
        return new GraphicsPipeline.Blend(true,factor(blend.sourceRgb()),factor(blend.destinationRgb()),factor(blend.sourceAlpha()),factor(blend.destinationAlpha()));
    }
    private static int factor(String name) {
        Integer factor=FACTORS.get(name);
        if (factor==null) throw unsupported("Unsupported blend factor: "+name);
        return factor;
    }
    static NativeGraphicsPipeline.Color color(ShaderPackProperties.ProgramState state,int target){
        var blend=state.blends().getOrDefault("colortex"+target,state.blends().get("*"));
        boolean enabled=blend!=null&&blend.enabled();
        return new NativeGraphicsPipeline.Color(enabled,enabled?factor(blend.sourceRgb()):VK_BLEND_FACTOR_ONE,
                enabled?factor(blend.destinationRgb()):VK_BLEND_FACTOR_ZERO,VK_BLEND_OP_ADD,
                enabled?factor(blend.sourceAlpha()):VK_BLEND_FACTOR_ONE,enabled?factor(blend.destinationAlpha()):VK_BLEND_FACTOR_ZERO,
                VK_BLEND_OP_ADD,target<0?0:15);
    }
    private static UnsupportedShaderException unsupported(String message) { return new UnsupportedShaderException(message); }
}
