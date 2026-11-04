package dev.overhaul.minecraft.backend;

import dev.overhaul.shader.compat.CoordinateConvention;
import dev.overhaul.shader.compat.FragmentAlphaTest;
import dev.overhaul.shader.compat.LegacyShaderContract;
import dev.overhaul.shader.compat.PackProgramTranslator;
import dev.overhaul.shader.compat.ProgramBindings;
import dev.overhaul.shader.compat.UnsupportedShaderException;
import dev.overhaul.shader.compat.ShaderMainWrapper;
import dev.overhaul.shader.compile.ShaderStage;
import dev.overhaul.shader.compile.ShadercCompiler;
import dev.overhaul.shader.compile.SpirvBinary;
import dev.overhaul.shader.compile.SpirvInterface;
import dev.overhaul.shader.pack.CompiledShaderPack;
import dev.overhaul.shader.pack.PackIdMap;
import dev.overhaul.shader.pack.PackShadowSettings;
import dev.overhaul.shader.pack.PackVertexDefaults;
import dev.overhaul.shader.pack.ShaderPackProperties;
import java.util.Set;

/** One authoritative pack-ABI compiler; only decoding the actual captured geometry varies by source family. */
final class ScenePackProgram {
    final SpirvBinary vertex,fragment;
    final ProgramBindings bindings;
    final SceneVertexInputs inputs;
    final int storageBinding;
    final boolean normals;
    ScenePackProgram(CompiledShaderPack pack,CompiledShaderPack.Program program,OverhaulPipeline original,ShadercCompiler compiler){
        boolean shadow=program.stage()==CompiledShaderPack.Stage.SHADOW;
        inputs=SceneVertexInputs.create(original,shadow);var source=inputs.sources(pack.sources(program));if(shadow)source=ShadowSceneInputs.sources(source);
        var alpha=program.state().alphaTest().orElseGet(()->shadow?(original.compileMetadata().defined("ALPHA_CUTOUT")?new ShaderPackProperties.AlphaTest(true,"GEQUAL",0.1f):null):original.inheritedAlphaTest());
        var translated=new PackProgramTranslator().translate(PackShadowSettings.stripDirectives(FragmentAlphaTest.lowerReference(source.vertex(),alpha)),PackShadowSettings.stripDirectives(FragmentAlphaTest.lowerReference(source.fragment(),alpha)),shadow?CoordinateConvention.OPENGL_RENDER_TARGET:CoordinateConvention.OPENGL_REVERSED_DEPTH_TARGET);
        ProgramBindings declared=translated.bindings();storageBinding=declared.samplers().values().stream().mapToInt(Integer::intValue).max().orElse(0)+1;
        boolean activeNormal=program.code().descriptors().attributes().containsKey("overhaulLegacyNormal");
        var decoder=inputs.decode(declared,storageBinding,activeNormal);if(shadow)decoder=ShadowSceneInputs.decode(inputs,decoder,declared);
        normals=decoder.quadNormals();String text=translated.vertex();
        for(var attribute:declared.attributes().entrySet()){
            String type=PackVertexDefaults.known(attribute.getKey())?declared.attributeTypes().get(attribute.getKey()):LegacyShaderContract.ATTRIBUTE_TYPES.get(attribute.getKey());if(type==null)throw new UnsupportedShaderException("Unbound scene pack attribute: "+attribute.getKey());
            text=text.replace("layout(location="+attribute.getValue()+") in "+type+" "+attribute.getKey()+";",type+" "+attribute.getKey()+";");
        }
        text=PackProgramTranslator.insertBodyPreamble(text,decoder.vertexPreamble());
        if(normals)text=text.replaceFirst("#version 450","#version 450\n#extension GL_ARB_shader_draw_parameters : require");
        text=ShaderMainWrapper.wrap(text,"overhaulSceneVertexEntry",decoder.initialize()+PackVertexDefaults.assign(declared.attributes(),declared.attributeTypes(),pipelineBlockId(pack,original)),"");
        String fs=PackProgramTranslator.insertBodyPreamble(translated.fragment(),decoder.fragmentPreamble());
        fs=FragmentAlphaTest.lower(inputs.fragment(fs),alpha);
        vertex=compiler.compile(program.name()+"-scene.vsh",text,ShaderStage.VERTEX);fragment=compiler.compile(program.name()+"-scene.fsh",fs,ShaderStage.FRAGMENT);
        var active=SpirvInterface.active(declared,vertex,fragment,Set.of(1));
        bindings=new ProgramBindings(active.uniforms(),active.uniformBytes(),active.samplers(),declared.attributes(),declared.varyings(),declared.attributeTypes(),active.samplerTypes());
    }
    /** Without per-vertex ids, all translucent terrain reads as water: water dominates it, though glass and ice then shade as water too. */
    private static int pipelineBlockId(CompiledShaderPack pack,OverhaulPipeline original){
        boolean water=original.compileMetadata().family()==PipelineCompileMetadata.Family.TERRAIN&&SceneProgramSelection.transparent(original);
        return water?pack.ids().block("minecraft:water"):PackIdMap.UNKNOWN;
    }
}
