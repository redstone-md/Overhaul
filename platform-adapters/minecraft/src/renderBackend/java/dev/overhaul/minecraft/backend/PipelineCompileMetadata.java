package dev.overhaul.minecraft.backend;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import dev.overhaul.shader.pack.ShaderPackProperties;
import dev.overhaul.shader.compat.UnsupportedShaderException;

/** Copies actual frontend compile defines before the backend schema discards them. */
record PipelineCompileMetadata(String name,String fragment,String alphaExpression,boolean alphaFlag) {
    static PipelineCompileMetadata terrain(RenderPipeline pipeline){
        var fragment=pipeline.getShaders().get(ShaderType.FRAGMENT);
        if(fragment==null||!fragment.toString().equals("minecraft:core/terrain"))return null;
        return new PipelineCompileMetadata(pipeline.getLocation().toString(),fragment.toString(),pipeline.getShaderDefines().values().get("ALPHA_CUTOUT"),pipeline.getShaderDefines().flags().contains("ALPHA_CUTOUT"));
    }
    ShaderPackProperties.AlphaTest inheritedAlphaTest(){
        String literal=alphaExpression;
        if(literal==null){if(alphaFlag)throw new UnsupportedShaderException("Terrain ALPHA_CUTOUT requires its actual numeric threshold");return null;}
        float threshold;
        try{threshold=Float.parseFloat(literal);}catch(NumberFormatException invalid){throw new UnsupportedShaderException("Unsupported terrain ALPHA_CUTOUT expression: "+literal);}
        if(!Float.isFinite(threshold)||threshold<0||threshold>1)throw new UnsupportedShaderException("Terrain ALPHA_CUTOUT threshold must be finite and within [0,1]");
        // The pinned terrain fragment discards alpha < ALPHA_CUTOUT; equality must survive.
        return new ShaderPackProperties.AlphaTest(true,"GEQUAL",threshold);
    }
}
