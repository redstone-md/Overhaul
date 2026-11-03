package dev.overhaul.vulkan;

import dev.overhaul.core.model.ModelMesh;
import dev.overhaul.core.model.RenderLayer;
import dev.overhaul.shader.compat.ProgramBindings;
import dev.overhaul.shader.compile.ShaderStage;
import dev.overhaul.shader.compile.ShadercCompiler;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import static org.lwjgl.vulkan.VK10.*;

/** Captured colors include tint/AO/shading; normals/material remain available in the source ABI. */
final class ModelPipelines implements AutoCloseable {
    static final ProgramBindings DESCRIPTORS=new ProgramBindings(Map.of("transform",new ProgramBindings.Uniform("mat4",0,64)),64,
            Map.of("atlas",1,"lightmap",2),Map.of(),Map.of(),Map.of());
    private static final String VERTEX="""
            #version 450
            layout(location=0) in vec3 position;
            layout(location=1) in vec2 atlasUv;
            layout(location=2) in vec4 tint;
            layout(location=3) in vec2 lightUv;
            layout(location=4) in vec4 normal;
            layout(location=5) in float alphaCutoff;
            layout(location=6) in uint material;
            layout(set=0,binding=0,std140) uniform Camera { mat4 transform; } camera;
            layout(location=0) out vec2 uv;
            layout(location=1) out vec4 color;
            layout(location=2) out vec2 light;
            layout(location=3) flat out float cutoff;
            void main(){ gl_Position=camera.transform*vec4(position,1); uv=atlasUv; color=tint; light=lightUv; cutoff=alphaCutoff; }
            """;
    private static final String FRAGMENT="""
            #version 450
            layout(location=0) in vec2 uv;
            layout(location=1) in vec4 color;
            layout(location=2) in vec2 light;
            layout(location=3) flat in float cutoff;
            layout(set=0,binding=1) uniform sampler2D atlas;
            layout(set=0,binding=2) uniform sampler2D lightmap;
            layout(location=0) out vec4 result;
            void main(){ vec4 sampled=texture(atlas,uv)*color; CUTOUT result=vec4(sampled.rgb*texture(lightmap,light).rgb,sampled.a); }
            """;
    private final EnumMap<RenderLayer,GraphicsPipeline> pipelines=new EnumMap<>(RenderLayer.class);

    ModelPipelines(VulkanContext context,ColorTarget target,long descriptorLayout) {
        List<GraphicsPipeline.Attribute> attributes=List.of(new GraphicsPipeline.Attribute(0,0,VK_FORMAT_R32G32B32_SFLOAT,0),
                new GraphicsPipeline.Attribute(1,0,VK_FORMAT_R16G16_UNORM,12),new GraphicsPipeline.Attribute(2,0,VK_FORMAT_R8G8B8A8_UNORM,16),
                new GraphicsPipeline.Attribute(3,0,VK_FORMAT_R16G16_UNORM,20),new GraphicsPipeline.Attribute(4,0,VK_FORMAT_R8G8B8A8_SNORM,24),
                new GraphicsPipeline.Attribute(5,0,VK_FORMAT_R8_UNORM,27),new GraphicsPipeline.Attribute(6,0,VK_FORMAT_R32_UINT,28));
        try (var compiler=new ShadercCompiler()) {
            var vertex=compiler.compile("overhaul-model.vert",VERTEX,ShaderStage.VERTEX);
            var opaque=compiler.compile("overhaul-model.frag",FRAGMENT.replace("CUTOUT",""),ShaderStage.FRAGMENT);
            var cutout=compiler.compile("overhaul-model-cutout.frag",FRAGMENT.replace("CUTOUT","if(sampled.a<cutoff) discard;"),ShaderStage.FRAGMENT);
            for (RenderLayer layer:RenderLayer.values()) {
                boolean translucent=layer==RenderLayer.TRANSLUCENT;
                var blend=translucent ? new GraphicsPipeline.Blend(true,VK_BLEND_FACTOR_SRC_ALPHA,VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA,VK_BLEND_FACTOR_ONE,VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                        : GraphicsPipeline.Blend.disabled();
                pipelines.put(layer,new GraphicsPipeline(context,target.renderPass,descriptorLayout,target.width,target.height,vertex,
                        layer==RenderLayer.CUTOUT?cutout:opaque,List.of(new GraphicsPipeline.Binding(0,ModelMesh.VERTEX_STRIDE,VK_VERTEX_INPUT_RATE_VERTEX)),attributes,blend,!translucent));
            }
        } catch (RuntimeException|Error failure) {close();throw failure;}
    }
    GraphicsPipeline get(RenderLayer layer){return pipelines.get(layer);}
    @Override public void close(){for(GraphicsPipeline pipeline:pipelines.values())pipeline.close();pipelines.clear();}
}
