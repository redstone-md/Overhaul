package dev.overhaul.minecraft.backend;

import dev.overhaul.shader.compat.ProgramBindings;
import dev.overhaul.shader.compat.UnsupportedShaderException;
import java.util.Map;

/** The original BLOCK28 quad stream retains integer chunk subtraction and first-instance addressing. */
final class TerrainSceneInputs extends SceneVertexInputs {
    TerrainSceneInputs(OverhaulPipeline original){super(original);}
    @Override Decoder decode(ProgramBindings bindings,int storageBinding,boolean activeNormal){
        boolean reconstruct=activeNormal&&!hasField("Normal");
        if(reconstruct&&!original.owner().context().shaderDrawParameters())throw new UnsupportedShaderException("Quad normal reconstruction requires shader draw parameters");
        StringBuilder source=new StringBuilder(input("vec3","Position",0)+input("vec4","Color",1)+input("vec2","UV0",2)+input("ivec2","UV2",3));
        source.append("layout(set=1,binding=").append(slot(original,"Globals")).append(",std140) uniform OverhaulGameGlobals { ivec3 overhaulCameraBlock; float overhaulGlint; vec3 overhaulCameraOffset; float overhaulGameTime; vec2 overhaulScreen; int overhaulBlur; int overhaulRgss; };\n");
        if(metadata.defined("MULTIDRAW_TERRAIN"))source.append(input("ivec3","ChunkPosition",4)).append(input("float","ChunkVisibility",5));
        else source.append("layout(set=1,binding=").append(slot(original,"ChunkSection")).append(",std140) uniform OverhaulGameChunk { ivec3 overhaulInputChunkPosition; float overhaulInputChunkVisibility; };\n");
        if(activeNormal&&!reconstruct)source.append(normalInput(6));
        source.append(quadStorage(storageBinding,reconstruct?quadStorage():null));
        String initialize=assign(bindings,Map.of("overhaulLegacyVertex","vec4(overhaulInputPosition+(overhaulInputChunkPosition-overhaulCameraBlock)+overhaulCameraOffset,1.0)",
                "overhaulLegacyColor","overhaulInputColor","overhaulLegacyTexCoord0","vec4(overhaulInputUV0,0.0,1.0)","overhaulLegacyTexCoord1","vec4(vec2(overhaulInputUV2),0.0,1.0)","overhaulLegacyNormal",activeNormal&&!reconstruct?"overhaulInputNormal":"vec3(0.0)"));
        if(reconstruct)initialize+=quadNormal();
        return new Decoder(source.toString(),"",initialize,reconstruct);
    }
    static String quadStorage(int binding,Storage storage){return storage==null?"":"layout(set=0,binding="+binding+",std430) readonly buffer OverhaulQuadVertices { uint overhaulWords[]; };\nlayout(push_constant) uniform OverhaulVertexAddress { uint overhaulWordOffset; };\nvec3 overhaulReadPosition(int index){uint p=overhaulWordOffset+uint(index)*"+storage.strideWords()+"u+"+storage.positionWords()+"u;return vec3(uintBitsToFloat(overhaulWords[p]),uintBitsToFloat(overhaulWords[p+1u]),uintBitsToFloat(overhaulWords[p+2u]));}\n";}
    static String quadNormal(){return "int base=((gl_VertexIndex-gl_BaseVertexARB)&~3)+gl_BaseVertexARB;vec3 p=overhaulReadPosition(base);overhaulLegacyNormal=normalize(cross(overhaulReadPosition(base+1)-p,overhaulReadPosition(base+2)-p));\n";}
}
