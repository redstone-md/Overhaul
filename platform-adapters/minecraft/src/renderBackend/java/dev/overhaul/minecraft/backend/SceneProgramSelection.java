package dev.overhaul.minecraft.backend;

import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendOp;
import dev.overhaul.minecraft.shader.SceneDrawDomain;
import dev.overhaul.shader.pack.CompiledShaderPack;
import dev.overhaul.shader.pack.PackProgramFallbacks;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Iris program fallback is semantic: copied source identity, typed origin, and actual blend state select the path. */
final class SceneProgramSelection {
    static final Set<String> SUPPORTED=Set.of("gbuffers_basic","gbuffers_textured","gbuffers_textured_lit","gbuffers_terrain","gbuffers_terrain_solid","gbuffers_terrain_cutout",
            "gbuffers_water","gbuffers_entities","gbuffers_entities_translucent","gbuffers_spidereyes","gbuffers_block","gbuffers_block_translucent");
    /** No pipeline routes to these yet: their geometry keeps its vanilla shader and lands in colortex0 unshaded. */
    static final Set<String> VANILLA_DRAWN=Set.of("gbuffers_armor_glint","gbuffers_beaconbeam","gbuffers_clouds","gbuffers_damagedblock","gbuffers_entities_glowing",
            "gbuffers_hand","gbuffers_hand_water","gbuffers_line","gbuffers_lightning","gbuffers_skybasic","gbuffers_skytextured","gbuffers_weather");
    private final Map<String,CompiledShaderPack.Program> programs=new LinkedHashMap<>();
    SceneProgramSelection(CompiledShaderPack pack){for(var program:pack.programs())if(program.stage()==CompiledShaderPack.Stage.GBUFFER)programs.put(baseName(program),program);}
    boolean hasScene(){return !programs.isEmpty();}
    CompiledShaderPack.Program select(OverhaulPipeline pipeline){
        var metadata=pipeline.compileMetadata();if(metadata==null||metadata.flags().stream().anyMatch(name->name.startsWith("OIT_"))||metadata.defines().keySet().stream().anyMatch(name->name.startsWith("OIT_")))return null;
        // Combined foil has no pack material route yet: an enchanted entity stays vanilla-drawn instead of failing the frame.
        if(metadata.family()==PipelineCompileMetadata.Family.ENTITY&&metadata.defined("GLINT"))return null;
        String name;boolean transparent=transparent(pipeline);
        if(metadata.family()==PipelineCompileMetadata.Family.TERRAIN)name=transparent?"gbuffers_water":metadata.defined("ALPHA_CUTOUT")?"gbuffers_terrain_cutout":"gbuffers_terrain_solid";
        // The exact core/block source is Iris's moving-block profile, independently of caller object type.
        else if(metadata.family()==PipelineCompileMetadata.Family.BLOCK||metadata.domain()==SceneDrawDomain.BLOCK_ENTITY)name=transparent?"gbuffers_block_translucent":"gbuffers_block";
        else if(metadata.domain()==SceneDrawDomain.ENTITY)name=metadata.defined("EMISSIVE")&&additive(pipeline)?"gbuffers_spidereyes":transparent?"gbuffers_entities_translucent":"gbuffers_entities";
        else return null;
        var found=PackProgramFallbacks.resolve(name,programs.keySet());if(found.isEmpty())return null;
        metadata.requireScene();return programs.get(found.get());
    }
    static String baseName(CompiledShaderPack.Program program){return program.name().substring(program.name().lastIndexOf('/')+1);}
    static boolean transparent(OverhaulPipeline pipeline){
        if(pipeline.info().colorTargetStates().isEmpty()||pipeline.info().colorTargetStates().getFirst()==null)return false;
        var blend=pipeline.info().colorTargetStates().getFirst().blendFunction().orElse(null);if(blend==null)return false;
        return blend.color().op()!=BlendOp.ADD||blend.alpha().op()!=BlendOp.ADD||blend.color().sourceFactor()!=BlendFactor.ONE||blend.color().destFactor()!=BlendFactor.ZERO||blend.alpha().sourceFactor()!=BlendFactor.ONE||blend.alpha().destFactor()!=BlendFactor.ZERO;
    }
    private static boolean additive(OverhaulPipeline pipeline){
        if(pipeline.info().colorTargetStates().isEmpty()||pipeline.info().colorTargetStates().getFirst()==null)return false;
        var blend=pipeline.info().colorTargetStates().getFirst().blendFunction().orElse(null);return blend!=null&&blend.color().destFactor()==BlendFactor.ONE;
    }
}
