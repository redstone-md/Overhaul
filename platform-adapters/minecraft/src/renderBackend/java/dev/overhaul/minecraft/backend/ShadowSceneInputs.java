package dev.overhaul.minecraft.backend;

import dev.overhaul.shader.compat.ProgramBindings;

/** Reuses actual geometry decoding, then changes the player-relative world basis to the pack's light camera. */
final class ShadowSceneInputs {
    private static final String MATRICES="uniform mat4 shadowProjection;\nuniform mat4 shadowModelView;\nuniform mat4 gbufferModelViewInverse;\n";
    private static final String ALIASES="#define overhaulProjection shadowProjection\n#define overhaulProjectionInverse inverse(shadowProjection)\n"
            +"#define overhaulModelView shadowModelView\n#define overhaulModelViewInverse inverse(shadowModelView)\n"
            +"#define overhaulModelViewProjection (shadowProjection*shadowModelView)\n#define overhaulNormalMatrix transpose(inverse(mat3(shadowModelView)))\n";
    private ShadowSceneInputs(){}
    static SceneVertexInputs.Sources sources(SceneVertexInputs.Sources original){return new SceneVertexInputs.Sources(MATRICES+original.vertex(),MATRICES+original.fragment());}
    static SceneVertexInputs.Decoder decode(SceneVertexInputs inputs,SceneVertexInputs.Decoder original,ProgramBindings bindings){
        String initialize=original.initialize();
        if(inputs.metadata.family()!=PipelineCompileMetadata.Family.TERRAIN){
            // The captured draw matrices remain in player-camera space, including custom per-draw transforms.
            initialize+="mat4 overhaulCasterToWorld=gbufferModelViewInverse*overhaulGameModelView;\n";
            if(bindings.attributes().containsKey("overhaulLegacyVertex"))initialize+="overhaulLegacyVertex=overhaulCasterToWorld*overhaulLegacyVertex;\n";
            if(bindings.attributes().containsKey("overhaulLegacyNormal"))initialize+="overhaulLegacyNormal=transpose(inverse(mat3(overhaulCasterToWorld)))*overhaulLegacyNormal;\n";
        }
        return new SceneVertexInputs.Decoder(preamble(original.vertexPreamble()),preamble(original.fragmentPreamble()),initialize,original.quadNormals());
    }
    private static String preamble(String original){
        return original.replaceAll("(?m)^#define (?:overhaulProjection|overhaulProjectionInverse|overhaulModelView|overhaulModelViewInverse|overhaulModelViewProjection|overhaulNormalMatrix) [^\\r\\n]*\\r?\\n","")+ALIASES;
    }
}
