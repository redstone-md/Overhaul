package dev.overhaul.minecraft.backend;

import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendOp;
import dev.overhaul.minecraft.shader.SceneDrawDomain;
import dev.overhaul.shader.pack.CompiledShaderPack;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Iris program fallback is semantic: copied source identity, typed origin, and actual blend state select the path. */
final class SceneProgramSelection {
    static final Set<String> SUPPORTED=Set.of("gbuffers_basic","gbuffers_textured","gbuffers_textured_lit","gbuffers_terrain","gbuffers_terrain_solid","gbuffers_terrain_cutout",
            "gbuffers_water","gbuffers_entities","gbuffers_entities_translucent","gbuffers_spidereyes","gbuffers_block","gbuffers_block_translucent");
    private static final Map<String,String> FALLBACKS=Map.ofEntries(Map.entry("gbuffers_textured","gbuffers_basic"),Map.entry("gbuffers_textured_lit","gbuffers_textured"),
            Map.entry("gbuffers_terrain","gbuffers_textured_lit"),Map.entry("gbuffers_terrain_solid","gbuffers_terrain"),Map.entry("gbuffers_terrain_cutout","gbuffers_terrain"),
            Map.entry("gbuffers_water","gbuffers_terrain"),Map.entry("gbuffers_entities","gbuffers_textured_lit"),Map.entry("gbuffers_entities_translucent","gbuffers_entities"),
            Map.entry("gbuffers_spidereyes","gbuffers_textured"),Map.entry("gbuffers_block","gbuffers_terrain"),Map.entry("gbuffers_block_translucent","gbuffers_block"));
    private final Map<String,CompiledShaderPack.Program> programs=new LinkedHashMap<>();
    SceneProgramSelection(CompiledShaderPack pack){for(var program:pack.programs())if(program.stage()==CompiledShaderPack.Stage.GBUFFER)programs.put(baseName(program),program);}
    boolean hasScene(){return !programs.isEmpty();}
    CompiledShaderPack.Program select(OverhaulPipeline pipeline){
        var metadata=pipeline.compileMetadata();if(metadata==null||metadata.flags().stream().anyMatch(name->name.startsWith("OIT_"))||metadata.defines().keySet().stream().anyMatch(name->name.startsWith("OIT_")))return null;
        String name;boolean transparent=transparent(pipeline);
        if(metadata.family()==PipelineCompileMetadata.Family.TERRAIN)name=transparent?"gbuffers_water":metadata.defined("ALPHA_CUTOUT")?"gbuffers_terrain_cutout":"gbuffers_terrain_solid";
        // The exact core/block source is Iris's moving-block profile, independently of caller object type.
        else if(metadata.family()==PipelineCompileMetadata.Family.BLOCK||metadata.domain()==SceneDrawDomain.BLOCK_ENTITY)name=transparent?"gbuffers_block_translucent":"gbuffers_block";
        else if(metadata.domain()==SceneDrawDomain.ENTITY)name=metadata.defined("EMISSIVE")&&additive(pipeline)?"gbuffers_spidereyes":transparent?"gbuffers_entities_translucent":"gbuffers_entities";
        else return null;
        while(name!=null){var program=programs.get(name);if(program!=null){metadata.requireScene();return program;}name=FALLBACKS.get(name);}return null;
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
