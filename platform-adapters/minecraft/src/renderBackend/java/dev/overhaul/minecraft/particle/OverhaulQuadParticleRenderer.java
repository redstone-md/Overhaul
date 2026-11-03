package dev.overhaul.minecraft.particle;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.textures.FilterMode;
import dev.overhaul.minecraft.shader.ShaderResourceGate;
import dev.overhaul.minecraft.shader.InstancedQuadGeometry;
import dev.overhaul.minecraft.shader.OwnedVertexShaderSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.QuadParticleFeatureRenderer;
import net.minecraft.client.renderer.oit.OitStage;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Normal feature renderer: extracted billboard records become per-instance vertex input exactly once. */
public final class OverhaulQuadParticleRenderer extends QuadParticleFeatureRenderer {
    private static final long MAX_GROUP_BYTES=8L*1024*1024;
    private static final Identifier ORIGINAL_SHADER=Identifier.withDefaultNamespace("core/particle");
    private record Draw(SingleQuadParticle.Layer layer,AbstractTexture texture,GpuBufferSlice instances,GpuBufferSlice arguments,int count){}
    private record Group(List<Draw> draws,int fallbackIndex){}
    private static final class LayerPlan {long count;}
    private final Thread owner=Thread.currentThread();
    private final ShaderResourceGate shaders=new ShaderResourceGate("/overhaul-particle-shaders.properties");
    private final InstancedParticlePipelines pipelines=new InstancedParticlePipelines();
    private final List<Group> groups=new ArrayList<>();
    private InstancedQuadGeometry geometry;
    private GpuBufferSlice dynamicTransforms;
    private PipelineCache compiled;
    private int fallbackCount;
    private long frames,preparedParticles,preparedBytes,recordedIndirectCalls,recordedInstances,normalCalls,oitCalls;
    private long fallbackGroups,maxLayerInstances;
    private long pipelineGenerations;
    private boolean eligible,closed;

    @Override public void beginPrepare(FeatureFrameContext context) {
        requireOpen();
        if(!groups.isEmpty())throw new IllegalStateException("Previous particle frame has not finished execution");
        frames++;
        eligible=RenderSystem.getDevice().getDeviceInfo().features().drawIndirect()
                &&shaders.eligible(Minecraft.getInstance().getResourceManager());
    }
    @Override public void prepareGroup(FeatureFrameContext context,List<Submit> submits,boolean sorted) {
        requireOpen();if(submits.isEmpty())return;
        var plans=new IdentityHashMap<SingleQuadParticle.Layer,LayerPlan>();long total=0;boolean compatible=eligible;
        for(var submit:submits) {
            var particles=submit.particles();if(particles.isEmpty())continue;
            var access=(QuadParticleStateAccess)particles;
            for(var layer:particles.layers())if(layer.translucent()==submit.translucent()) {
                // Include empty retained layers: identical key insertion preserves vanilla IdentityHashMap order.
                var plan=plans.computeIfAbsent(layer,ignored->new LayerPlan());
                int count=access.overhaul$count(layer);plan.count+=count;total+=count;
                compatible&=compatible(layer.pipeline());
            }
        }
        if(!compatible||total>MAX_GROUP_BYTES/InstancedParticlePipelines.INSTANCE_STRIDE) {
            super.prepareGroup(context,submits,sorted);groups.add(new Group(List.of(),fallbackCount++));fallbackGroups++;return;
        }
        var memory=RenderSystem.getDevice().createCommandEncoder().transientMemory();
        var draws=new ArrayList<Draw>();
        for(var entry:plans.entrySet()) {
            int count=Math.toIntExact(entry.getValue().count);if(count==0)continue;
            var layer=entry.getKey();long bytes=(long)count*InstancedParticlePipelines.INSTANCE_STRIDE;
            GpuBufferSlice instances,arguments;
            try(var mapped=memory.allocateGpuMapped(bytes,4,GpuBuffer.USAGE_VERTEX)) {
                var writer=new ParticleInstanceWriter(mapped.data(),count);
                for(var submit:submits)if(!submit.particles().isEmpty()&&submit.translucent()==layer.translucent())
                    ((QuadParticleStateAccess)submit.particles()).overhaul$forEachParticle(layer,writer);
                writer.finish();instances=mapped.slice();
            }
            try(var mapped=memory.allocateGpuMapped(20,4,GpuBuffer.USAGE_INDIRECT_PARAMETERS)) {
                mapped.data().order(ByteOrder.nativeOrder()).putInt(6).putInt(count).putInt(0).putInt(0).putInt(0);
                arguments=mapped.slice();
            }
            draws.add(new Draw(layer,context.textureManager().getTexture(layer.textureAtlasLocation()),instances,arguments,count));
            preparedParticles+=count;preparedBytes+=bytes;maxLayerInstances=Math.max(maxLayerInstances,count);
        }
        groups.add(new Group(List.copyOf(draws),-1));
    }
    @Override public void finishPrepare(FeatureFrameContext context) {
        requireOpen();if(fallbackCount>0)super.finishPrepare(context);
        if(groups.stream().anyMatch(group->!group.draws().isEmpty())) {
            ensureGeometry();dynamicTransforms=RenderSystem.getDynamicUniforms().writeTransform(RenderSystem.getModelViewMatrixCopy());
        }
    }
    @Override public void executeGroup(FeatureFrameContext context,OitStage stage,RenderPass pass,int index,List<Submit> submits,boolean sorted) {
        requireOpen();var group=groups.get(index);
        if(group.fallbackIndex()>=0){super.executeGroup(context,stage,pass,group.fallbackIndex(),submits,sorted);return;}
        if(group.draws().isEmpty())return;
        pass.pushDebugGroup(()->"Overhaul instanced quad particles");
        try {
            for(var draw:group.draws()) {
                RenderPipeline original=stage==null?draw.layer().pipeline():draw.layer().oitPipelineSet().getPipeline(stage);
                if(!compatible(original))throw new IllegalStateException("Particle stage changed its vertex contract");
                if(compiled==null){compiled=new PipelineCache(RenderSystem.getDevice(),new OwnedVertexShaderSource(Minecraft.getInstance().getResourceManager()));pipelineGenerations++;}
                var pipeline=compiled.get(pipelines.variant(original));
                if(pipeline==null)throw new IllegalStateException("Owned particle pipeline failed to compile");
                pass.setPipeline(pipeline);
                RenderSystem.bindDefaultUniforms(pass);pass.setUniform("DynamicTransforms",dynamicTransforms);
                pass.setUniform("Sampler2",context.lightmap(),RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
                geometry.bind(pass,draw.instances());
                pass.setUniform("Sampler0",draw.texture().getTextureView(),draw.texture().getSampler());
                pass.drawIndexedIndirect(draw.arguments(),1);recordedIndirectCalls++;recordedInstances+=draw.count();
                if(stage==null)normalCalls++;else oitCalls++;
            }
        }finally{pass.popDebugGroup();}
    }
    @Override public void finishExecute(FeatureFrameContext context) {
        requireOpen();if(fallbackCount>0)super.finishExecute(context);
        groups.clear();fallbackCount=0;dynamicTransforms=null;
    }
    private static boolean compatible(RenderPipeline pipeline) {
        return pipeline!=null&&ORIGINAL_SHADER.equals(pipeline.getShaders().get(ShaderType.VERTEX))
                &&pipeline.getVertexFormatBinding(0)==DefaultVertexFormat.PARTICLE;
    }
    private void ensureGeometry() {
        if(geometry==null)geometry=new InstancedQuadGeometry(RenderSystem.getDevice());
    }
    public long reloadStarted(){requireOpen();eligible=false;closePipelines();return shaders.reloadStarted();}
    public void reloadFinished(long token,boolean success){requireOpen();shaders.reloadFinished(token,success);}
    public Map<String,Object> snapshot() {
        requireOwner();var result=new LinkedHashMap<String,Object>();result.put("frames",frames);result.put("preparedParticles",preparedParticles);
        result.put("preparedInstanceBytes",preparedBytes);result.put("recordedIndirectCalls",recordedIndirectCalls);result.put("recordedInstances",recordedInstances);
        result.put("normalCalls",normalCalls);result.put("oitCalls",oitCalls);result.put("fallbackGroups",fallbackGroups);result.put("maxLayerInstances",maxLayerInstances);
        result.put("eligible",eligible);result.put("shaderEligibility",shaders.reason());result.put("resourceGeneration",shaders.generation());
        result.put("pipelineGenerations",pipelineGenerations);result.put("pipelineCacheClosed",compiled==null);
        result.put("pendingGroups",groups.size());result.put("closed",closed);return Map.copyOf(result);
    }
    @Override public void close() {
        requireOwner();if(closed)return;
        closePipelines();if(geometry!=null)geometry.close();groups.clear();dynamicTransforms=null;closed=true;
    }
    private void closePipelines(){if(compiled!=null){compiled.close();compiled=null;}}
    private void requireOwner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Particle renderer belongs to its render owner");}
    private void requireOpen(){requireOwner();if(closed)throw new IllegalStateException("Particle renderer is closed");}
}
