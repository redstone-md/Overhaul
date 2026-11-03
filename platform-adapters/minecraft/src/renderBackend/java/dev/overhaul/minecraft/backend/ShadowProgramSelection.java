package dev.overhaul.minecraft.backend;

import dev.overhaul.minecraft.shader.SceneDrawDomain;
import dev.overhaul.shader.pack.CompiledShaderPack;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Only captured, supported source profiles choose shadow specializations; every specialization falls back to shadow. */
final class ShadowProgramSelection {
    static final Set<String> SUPPORTED=Set.of("shadow","shadow_solid","shadow_cutout","shadow_water","shadow_entities","shadow_block");
    private final Map<String,CompiledShaderPack.Program> programs=new LinkedHashMap<>();
    ShadowProgramSelection(CompiledShaderPack pack){for(var program:pack.programs())if(program.stage()==CompiledShaderPack.Stage.SHADOW)programs.put(SceneProgramSelection.baseName(program),program);}
    boolean present(){return !programs.isEmpty();}
    CompiledShaderPack.Program select(OverhaulPipeline pipeline){
        var metadata=pipeline.compileMetadata();if(metadata==null)return null;
        String name;
        if(metadata.family()==PipelineCompileMetadata.Family.TERRAIN)name=SceneProgramSelection.transparent(pipeline)?"shadow_water":metadata.defined("ALPHA_CUTOUT")?"shadow_cutout":"shadow_solid";
        else if(metadata.family()==PipelineCompileMetadata.Family.BLOCK||metadata.domain()==SceneDrawDomain.BLOCK_ENTITY)name="shadow_block";
        else if(metadata.domain()==SceneDrawDomain.ENTITY)name="shadow_entities";
        else return null;
        var result=programs.get(name);if(result==null)result=programs.get("shadow");
        if(result!=null)metadata.requireSource();return result;
    }
}
