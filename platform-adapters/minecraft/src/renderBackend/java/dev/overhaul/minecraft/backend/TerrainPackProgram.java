package dev.overhaul.minecraft.backend;

import dev.overhaul.shader.compat.CoordinateConvention;
import dev.overhaul.shader.compat.FragmentAlphaTest;
import dev.overhaul.shader.compat.LegacyShaderContract;
import dev.overhaul.shader.compat.PackProgramTranslator;
import dev.overhaul.shader.compat.ProgramBindings;
import dev.overhaul.shader.compat.UnsupportedShaderException;
import dev.overhaul.shader.compile.ShaderStage;
import dev.overhaul.shader.compile.ShadercCompiler;
import dev.overhaul.shader.compile.SpirvBinary;
import dev.overhaul.shader.compile.SpirvInterface;
import dev.overhaul.shader.pack.CompiledShaderPack;
import dev.overhaul.shader.pack.PackRenderTargets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Maps actual pinned BLOCK/instance inputs into pack globals before its original vertex entry point. */
final class TerrainPackProgram {
    final SpirvBinary vertex,fragment;
    final ProgramBindings bindings;
    final int storageBinding;
    final boolean normals;
    TerrainPackProgram(CompiledShaderPack pack,CompiledShaderPack.Program program,OverhaulPipeline original,ShadercCompiler compiler){
        var sources=pack.sources(program);var translated=new PackProgramTranslator().translate(PackRenderTargets.stripDirectives(sources.vertex()),
                PackRenderTargets.stripDirectives(sources.fragment()),CoordinateConvention.OPENGL_REVERSED_DEPTH_TARGET);
        ProgramBindings declared=translated.bindings();storageBinding=declared.samplers().values().stream().mapToInt(Integer::intValue).max().orElse(0)+1;
        normals=program.code().descriptors().attributes().containsKey("overhaulLegacyNormal");
        int globals=slot(original,"Globals");boolean instanced=original.info().vertexBuffers().stream().anyMatch(value->value.stepRate()>0);
        if(normals&&!original.owner().context().shaderDrawParameters())throw new UnsupportedShaderException("Terrain normal reconstruction requires shader draw parameters");
        String text=translated.vertex();
        for(var attribute:declared.attributes().entrySet()){
            String type=LegacyShaderContract.ATTRIBUTE_TYPES.get(attribute.getKey());
            if(type==null)throw new UnsupportedShaderException("Unbound terrain pack attribute: "+attribute.getKey());
            text=text.replace("layout(location="+attribute.getValue()+") in "+type+" "+attribute.getKey()+";",type+" "+attribute.getKey()+";");
        }
        StringBuilder decode=new StringBuilder("\nlayout(location=0) in vec3 overhaulPosition;\nlayout(location=1) in vec4 overhaulColor;\nlayout(location=2) in vec2 overhaulUv;\nlayout(location=3) in ivec2 overhaulLight;\n");
        decode.append("layout(set=1,binding=").append(globals).append(",std140) uniform OverhaulGameGlobals { ivec3 overhaulCameraBlock; float overhaulGlint; vec3 overhaulCameraOffset; float overhaulGameTime; vec2 overhaulScreen; int overhaulBlur; int overhaulRgss; };\n");
        if(instanced)decode.append("layout(location=4) in ivec3 overhaulChunkPosition;\nlayout(location=5) in float overhaulVisibility;\n");
        else decode.append("layout(set=1,binding=").append(slot(original,"ChunkSection")).append(",std140) uniform OverhaulGameChunk { ivec3 overhaulChunkPosition; float overhaulVisibility; };\n");
        if(normals)decode.append("layout(set=0,binding=").append(storageBinding).append(",std430) readonly buffer OverhaulQuadVertices { uint overhaulWords[]; };\nlayout(push_constant) uniform OverhaulVertexAddress { uint overhaulWordOffset; };\nvec3 overhaulReadPosition(int index){uint p=overhaulWordOffset+uint(index)*7u;return vec3(uintBitsToFloat(overhaulWords[p]),uintBitsToFloat(overhaulWords[p+1u]),uintBitsToFloat(overhaulWords[p+2u]));}\n");
        decode.append("void overhaulDecodeTerrain(){\n");
        for(String name:declared.attributes().keySet())decode.append(name).append(" = ").append(switch(name){
            case "overhaulLegacyVertex"->"vec4(overhaulPosition+(overhaulChunkPosition-overhaulCameraBlock)+overhaulCameraOffset,1.0)";
            case "overhaulLegacyColor"->"overhaulColor";case "overhaulLegacyTexCoord0"->"vec4(overhaulUv,0.0,1.0)";
            case "overhaulLegacyTexCoord1"->"vec4(vec2(overhaulLight),0.0,1.0)";
            case "overhaulLegacyNormal"->"vec3(0.0)";default->throw new UnsupportedShaderException("Unsupported terrain input: "+name);
        }).append(";\n");
        if(normals)decode.append("int base=((gl_VertexIndex-gl_BaseVertexARB)&~3)+gl_BaseVertexARB;vec3 p=overhaulReadPosition(base);overhaulLegacyNormal=normalize(cross(overhaulReadPosition(base+1)-p,overhaulReadPosition(base+2)-p));\n");
        decode.append("}\n");
        String renamed=text.replaceAll("\\bvoid\\s+main\\s*\\(\\s*\\)","void overhaulTerrainEntry()");
        if(renamed.equals(text))throw new UnsupportedShaderException("Missing terrain pack entry point");
        String output=renamed.replaceFirst("#version 450","#version 450\n"+(normals?"#extension GL_ARB_shader_draw_parameters : require\n":"")+java.util.regex.Matcher.quoteReplacement(decode.toString()));
        // Decode declarations precede the pack body; globals supplied by the translator must be visible to decode.
        int body=output.indexOf("void overhaulDecodeTerrain(){"),end=output.indexOf("\n}\n",body)+3;
        String function=output.substring(body,end);output=output.substring(0,body)+output.substring(end)+"\n"+function+"\nvoid main(){overhaulDecodeTerrain();overhaulTerrainEntry();}\n";
        vertex=compiler.compile(program.name()+"-terrain.vsh",output,ShaderStage.VERTEX);
        fragment=compiler.compile(program.name()+"-terrain.fsh",FragmentAlphaTest.lower(translated.fragment(),program.state().alphaTest().orElseGet(original::inheritedAlphaTest)),ShaderStage.FRAGMENT);
        var active=SpirvInterface.active(declared,vertex,fragment,java.util.Set.of(1));
        bindings=new ProgramBindings(active.uniforms(),active.uniformBytes(),active.samplers(),declared.attributes(),declared.varyings(),declared.attributeTypes());
    }
    static int slot(OverhaulPipeline pipeline,String name){for(int slot=0;slot<pipeline.uniforms().size();slot++)if(pipeline.uniforms().get(slot).name().equals(name))return slot;throw new UnsupportedShaderException("Pinned terrain binding is absent: "+name);}
}
