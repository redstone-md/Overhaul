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
import dev.overhaul.shader.pack.PackRenderTargets;
import java.util.Set;

/** One authoritative pack-ABI compiler; only decoding the actual captured geometry varies by source family. */
final class ScenePackProgram {
    final SpirvBinary vertex,fragment;
    final ProgramBindings bindings;
    final SceneVertexInputs inputs;
    final int storageBinding;
    final boolean normals;
    ScenePackProgram(CompiledShaderPack pack,CompiledShaderPack.Program program,OverhaulPipeline original,ShadercCompiler compiler){
        inputs=SceneVertexInputs.create(original);var source=inputs.sources(pack.sources(program));var alpha=program.state().alphaTest().orElseGet(original::inheritedAlphaTest);
        var translated=new PackProgramTranslator().translate(PackRenderTargets.stripDirectives(FragmentAlphaTest.lowerReference(source.vertex(),alpha)),PackRenderTargets.stripDirectives(FragmentAlphaTest.lowerReference(source.fragment(),alpha)),CoordinateConvention.OPENGL_REVERSED_DEPTH_TARGET);
        ProgramBindings declared=translated.bindings();storageBinding=declared.samplers().values().stream().mapToInt(Integer::intValue).max().orElse(0)+1;
        boolean activeNormal=program.code().descriptors().attributes().containsKey("overhaulLegacyNormal");
        var decoder=inputs.decode(declared,storageBinding,activeNormal);normals=decoder.quadNormals();String text=translated.vertex();
        for(var attribute:declared.attributes().entrySet()){
            String type=LegacyShaderContract.ATTRIBUTE_TYPES.get(attribute.getKey());if(type==null)throw new UnsupportedShaderException("Unbound scene pack attribute: "+attribute.getKey());
            text=text.replace("layout(location="+attribute.getValue()+") in "+type+" "+attribute.getKey()+";",type+" "+attribute.getKey()+";");
        }
        text=PackProgramTranslator.insertBodyPreamble(text,decoder.vertexPreamble());
        if(normals)text=text.replaceFirst("#version 450","#version 450\n#extension GL_ARB_shader_draw_parameters : require");
        text=ShaderMainWrapper.wrap(text,"overhaulSceneVertexEntry",decoder.initialize(),"");
        String fs=PackProgramTranslator.insertBodyPreamble(translated.fragment(),decoder.fragmentPreamble());
        fs=FragmentAlphaTest.lower(inputs.fragment(fs),alpha);
        vertex=compiler.compile(program.name()+"-scene.vsh",text,ShaderStage.VERTEX);fragment=compiler.compile(program.name()+"-scene.fsh",fs,ShaderStage.FRAGMENT);
        var active=SpirvInterface.active(declared,vertex,fragment,Set.of(1));
        bindings=new ProgramBindings(active.uniforms(),active.uniformBytes(),active.samplers(),declared.attributes(),declared.varyings(),declared.attributeTypes());
    }
}
