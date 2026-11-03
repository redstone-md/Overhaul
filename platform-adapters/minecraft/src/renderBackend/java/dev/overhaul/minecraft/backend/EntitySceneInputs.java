package dev.overhaul.minecraft.backend;

import dev.overhaul.minecraft.shader.OwnedVertexShaderSource;
import dev.overhaul.shader.compat.ProgramBindings;
import dev.overhaul.shader.compat.UnsupportedShaderException;
import dev.overhaul.shader.pack.CompiledShaderPack;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Plain and owned-instance model streams expose the same CPU-posed, quantized material inputs. */
final class EntitySceneInputs extends SceneVertexInputs {
    private final EntityMaterialInputs material=new EntityMaterialInputs();
    EntitySceneInputs(OverhaulPipeline original,boolean shadow){super(original,shadow);}
    @Override Sources sources(CompiledShaderPack.Sources source){
        var result=material.prepare(super.sources(source),true,metadata.family()==PipelineCompileMetadata.Family.ENTITY&&metadata.defined("DISSOLVE"));
        if(material.overlay()&&overlayEnabled())result=new Sources("uniform sampler2D "+EntityMaterialInputs.OVERLAY_SAMPLER+";\n"+result.vertex(),result.fragment());
        return result;
    }
    @Override String fragment(String source){return material.fragment(source);}
    @Override Decoder decode(ProgramBindings bindings,int storageBinding,boolean activeNormal){
        boolean block=metadata.family()==PipelineCompileMetadata.Family.BLOCK;
        boolean quadNormal=block&&activeNormal&&!hasField("Normal");
        if(quadNormal&&!original.owner().context().shaderDrawParameters())throw new UnsupportedShaderException("Block quad normals require shader draw parameters");
        StringBuilder source=new StringBuilder();String position,color,uv,light,normal,overlay="ivec2(0)";
        if(metadata.modelInstances()){
            String kernel=OwnedVertexShaderSource.packagedInclude("model_quad_inputs.glsl");
            kernel=kernel.replace("#include <overhaul:packed_vertex.glsl>",OwnedVertexShaderSource.packagedInclude("packed_vertex.glsl"));bindKernel(kernel);
            for(String name:new String[]{"Position","Color","UV0","UV1","UV2","Normal","Corner","InstanceRow0","InstanceRow1","InstanceRow2","InstanceMin","InstanceMax","InstanceUv01","InstanceUv23","InstanceMaterial","InstanceEncoding"})
                kernel=kernel.replaceAll("\\b"+Pattern.quote(name)+"\\b","overhaulModel"+name);
            source.append(kernel);position="overhaulModelPosition";color="overhaulModelColor";uv="overhaulModelUV0";light="overhaulModelUV2";normal="overhaulModelNormal";overlay="overhaulModelUV1";
        }else{
            source.append(input("vec3","Position",0)).append(input("vec4","Color",1)).append(input("vec2","UV0",2));
            position="overhaulInputPosition";color="overhaulInputColor";uv="overhaulInputUV0";
            if(block){source.append(input("ivec2","UV2",3));light="overhaulInputUV2";normal="vec3(0.0)";if(activeNormal&&!quadNormal){source.append(normalInput(4));normal="overhaulInputNormal";}source.append(TerrainSceneInputs.quadStorage(storageBinding,quadNormal?quadStorage():null));}
            else{
                if(material.overlay()&&overlayEnabled()){source.append(input("ivec2","UV1",3));overlay="overhaulInputUV1";}
                boolean needLight=bindings.attributes().containsKey("overhaulLegacyTexCoord1")&&!emissive();
                if(needLight)source.append(input("ivec2","UV2",4));light=needLight?"overhaulInputUV2":"ivec2(240)";
                if(activeNormal)source.append(normalInput(5));normal=activeNormal?"overhaulInputNormal":"vec3(0.0)";
            }
        }
        String dynamic=dynamicBindings();source.append(dynamic);
        if(block)position="("+position+"+overhaulGameModelOffset)";
        Map<String,String> values=new LinkedHashMap<>();values.put("overhaulLegacyVertex","vec4("+position+",1.0)");values.put("overhaulLegacyColor",(material.dissolve()?"vec4("+color+".rgb,1.0)":color)+"*overhaulGameColor");
        values.put("overhaulLegacyTexCoord0","vec4("+uv+",0.0,1.0)");values.put("overhaulLegacyTexCoord1","vec4(vec2("+(emissive()?"ivec2(240)":light)+"),0.0,1.0)");values.put("overhaulLegacyNormal",normal);
        String initialize=(metadata.modelInstances()?"overhaulDecodeModelQuad();\n":"")+assign(bindings,values);
        if(quadNormal)initialize+=TerrainSceneInputs.quadNormal();
        if(material.dissolve())initialize+=EntityMaterialInputs.SOURCE_ALPHA+"="+color+".a;\n"+EntityMaterialInputs.DISSOLVE_UV+"="+(metadata.defined("APPLY_TEXTURE_MATRIX")?"(overhaulGameTextureMatrix*vec4("+uv+",0.0,1.0)).xy":uv)+";\n";
        if(material.overlay())initialize+=EntityMaterialInputs.OVERLAY_COLOR+"="+(!overlayEnabled()?"vec4(0.0)":"vec4(texelFetch(overhaulSampler_"+EntityMaterialInputs.OVERLAY_SAMPLER+","+overlay+",0).rgb,1.0-texelFetch(overhaulSampler_"+EntityMaterialInputs.OVERLAY_SAMPLER+","+overlay+",0).a)")+";\n";
        return new Decoder(source.toString(),dynamic,initialize,quadNormal);
    }
    private String dynamicBindings(){
        String blocks="layout(set=1,binding="+slot(original,"DynamicTransforms")+",std140) uniform OverhaulGameDynamic { mat4 overhaulGameModelView; mat4 overhaulGameTextureMatrix; vec4 overhaulGameColor; vec3 overhaulGameModelOffset; };\n"
                +"layout(set=1,binding="+slot(original,"Projection")+",std140) uniform OverhaulGameProjection { mat4 overhaulNativeProjection; };\n"
                +"mat4 overhaulSceneProjection(){mat4 p=overhaulNativeProjection;for(int c=0;c<4;c++)p[c][2]=p[c][3]-2.0*p[c][2];return p;}\n";
        String aliases="#define overhaulModelView overhaulGameModelView\n#define overhaulModelViewInverse inverse(overhaulGameModelView)\n"
                +"#define overhaulProjection overhaulSceneProjection()\n#define overhaulProjectionInverse inverse(overhaulSceneProjection())\n#define overhaulModelViewProjection (overhaulSceneProjection()*overhaulGameModelView)\n"
                +"#define overhaulNormalMatrix transpose(inverse(mat3(overhaulGameModelView)))\n";
        if(metadata.family()==PipelineCompileMetadata.Family.ENTITY&&(metadata.defined("APPLY_TEXTURE_MATRIX")||!shadow&&metadata.defined("GLINT")))aliases+="#define overhaulTextureMatrix0 overhaulGameTextureMatrix\n";
        return blocks+aliases;
    }
    private boolean overlayEnabled(){return metadata.family()==PipelineCompileMetadata.Family.ENTITY&&!metadata.defined("NO_OVERLAY");}
    private boolean emissive(){return metadata.family()==PipelineCompileMetadata.Family.ENTITY&&metadata.defined("EMISSIVE");}
}
