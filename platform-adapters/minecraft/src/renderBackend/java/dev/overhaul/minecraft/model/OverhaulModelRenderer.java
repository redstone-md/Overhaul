package dev.overhaul.minecraft.model;

import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.overhaul.minecraft.shader.InstancedQuadGeometry;
import dev.overhaul.minecraft.shader.ShaderResourceGate;
import dev.overhaul.minecraft.shader.SceneDrawDomain;
import dev.overhaul.minecraft.backendmixin.RenderTypeSetupAccess;
import dev.overhaul.minecraft.backendmixin.RenderSetupOitAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.FeatureRenderer;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.oit.OitStage;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Ordinary model submissions retain game animation/hierarchy and become ordered hardware quad instances. */
public final class OverhaulModelRenderer implements FeatureRenderer<ModelFeatureRenderer.Submit<?>> {
    private record Draw(PreparedRenderType prepared,GpuBufferSlice instances,GpuBufferSlice arguments,int count,SceneDrawDomain domain){}
    private record Group(List<Draw> draws,int fallbackIndex){}
    private final Thread owner=Thread.currentThread();
    private final ModelFeatureRenderer fallback;
    private final ModelGeometryCache geometry=new ModelGeometryCache();
    private final InstancedModelPipelines pipelines=new InstancedModelPipelines();
    private final ShaderResourceGate shaders=new ShaderResourceGate("/overhaul-model-shaders.properties");
    private final PoseStack poses=new PoseStack();
    private final List<Group> groups=new ArrayList<>();
    private final EnumMap<OitStage,Long> oitCalls=new EnumMap<>(OitStage.class);
    private final Map<String,Long> modelTypes=new LinkedHashMap<>();
    private final boolean diagnostics=Boolean.getBoolean("overhaul.diagnostics.enable");
    private InstancedQuadGeometry quad;
    private ModelInstanceArena arena;
    private ModelFrameCollector collector;
    private PipelineCache compiled;
    private int fallbackCount;
    private long frames,preparedSubmits,preparedQuads,preparedBytes,fallbackGroups,fallbackSubmits;
    private long normalCalls,recordedInstances,sortedQuads,mappedQuads,decalQuads,maxDrawInstances,pipelineGenerations;
    private boolean eligible,closed;

    public OverhaulModelRenderer(ModelFeatureRenderer fallback){this.fallback=Objects.requireNonNull(fallback);}
    @Override public void beginPrepare(FeatureFrameContext context){
        requireOpen();if(!groups.isEmpty())throw new IllegalStateException("Previous model frame remains prepared");
        fallback.beginPrepare(context);frames++;
        eligible=RenderSystem.getDevice().getDeviceInfo().features().drawIndirect()
                &&shaders.eligible(Minecraft.getInstance().getResourceManager());
    }
    @Override public void prepareGroup(FeatureFrameContext context,List<ModelFeatureRenderer.Submit<?>> submits,boolean sorted){
        requireOpen();long maximum=0;boolean compatible=eligible;
        if(compatible)for(var submit:submits){
            compatible&=InstancedModelPipelines.compatible(submit.renderType().pipeline());
            var setup=((RenderTypeSetupAccess)submit.renderType()).overhaul$setup();
            compatible&=InstancedModelPipelines.compatibleOit(((RenderSetupOitAccess)(Object)setup).overhaul$oitPipelines());
            var definition=geometry.definition(submit.model());compatible&=definition.compatible();maximum+=definition.maximumQuads();
            if(!compatible||maximum>ModelInstanceArena.MAX_RECORDS)break;
        }
        if(!compatible||maximum>ModelInstanceArena.MAX_RECORDS){
            fallback.prepareGroup(context,submits,sorted);groups.add(new Group(List.of(),fallbackCount++));
            fallbackGroups++;fallbackSubmits+=submits.size();return;
        }
        ensureArena();arena.beginGroup();var batches=new ModelFrameBatches(sorted);
        for(var submit:submits){
            var batch=batches.batch(submit.renderType(),ModelSubmitDomainAccess.domain(submit));collector.configure(submit,batch);
            poses.last().set(submit.pose());int before=arena.size();prepareModel(submit);
            int count=arena.size()-before;preparedSubmits++;
            if(submit.sheetedDecalPose()!=null)decalQuads+=count;else if(submit.uvMapping()!=null)mappedQuads+=count;
            if(diagnostics){String name=submit.model().getClass().getName();if(modelTypes.size()<64||modelTypes.containsKey(name))modelTypes.merge(name,1L,Long::sum);}
        }
        var draws=new ArrayList<Draw>();
        var memory=RenderSystem.getDevice().createCommandEncoder().transientMemory();
        for(var batch:batches.batches()){
            int count=batch.records.size();if(count==0)continue;
            GpuBufferSlice instances=arena.upload(batch.records,batch.sorting),arguments;
            try(var mapped=memory.allocateGpuMapped(20,4,GpuBuffer.USAGE_INDIRECT_PARAMETERS)){
                mapped.data().order(ByteOrder.nativeOrder()).putInt(6).putInt(count).putInt(0).putInt(0).putInt(0);arguments=mapped.slice();
            }
            draws.add(new Draw(batch.prepared,instances,arguments,count,batch.domain));
            preparedQuads+=count;preparedBytes+=(long)count*InstancedModelPipelines.INSTANCE_STRIDE;
            if(batch.sorting!=null)sortedQuads+=count;maxDrawInstances=Math.max(maxDrawInstances,count);
        }
        groups.add(new Group(List.copyOf(draws),-1));
    }
    private <S> void prepareModel(ModelFeatureRenderer.Submit<S> submit){
        submit.model().setupAnim(submit.state());
        submit.model().renderToBuffer(poses,collector,submit.lightCoords(),submit.overlayCoords(),submit.tintedColor());
    }
    @Override public void finishPrepare(FeatureFrameContext context){requireOpen();fallback.finishPrepare(context);}
    @Override public void executeGroup(FeatureFrameContext context,OitStage stage,RenderPass pass,int index,List<ModelFeatureRenderer.Submit<?>> submits,boolean sorted){
        requireOpen();var group=groups.get(index);
        if(group.fallbackIndex()>=0){fallback.executeGroup(context,stage,pass,group.fallbackIndex(),submits,sorted);return;}
        for(var draw:group.draws()){
            var prepared=draw.prepared();RenderPipeline original=stage==null?prepared.pipeline():prepared.oitPipelineSet().getPipeline(stage);
            if(compiled==null){compiled=new PipelineCache(RenderSystem.getDevice(),new ModelVertexShaderSource(Minecraft.getInstance().getResourceManager()));pipelineGenerations++;}
            var pipeline=compiled.get(pipelines.variant(original,draw.domain()));if(pipeline==null)throw new IllegalStateException("Owned model pipeline failed to compile");
            if(quad==null)quad=new InstancedQuadGeometry(RenderSystem.getDevice());
            pass.pushDebugGroup(()->"Overhaul instanced model "+prepared.name());
            try{
                pass.setPipeline(pipeline);
                var scissor=prepared.scissorState();if(scissor.enabled())pass.enableScissor(scissor.x(),scissor.y(),scissor.width(),scissor.height());
                RenderSystem.bindDefaultUniforms(pass);pass.setUniform("DynamicTransforms",prepared.dynamicTransforms());
                for(var texture:prepared.textures())pass.setUniform(texture.name(),texture.textureView(),texture.sampler());
                quad.bind(pass,draw.instances());pass.drawIndexedIndirect(draw.arguments(),1);
                recordedInstances+=draw.count();if(stage==null)normalCalls++;else oitCalls.merge(stage,1L,Long::sum);
            }finally{pass.popDebugGroup();}
        }
    }
    @Override public void finishExecute(FeatureFrameContext context){
        requireOpen();fallback.finishExecute(context);groups.clear();fallbackCount=0;
    }
    private void ensureArena(){if(arena==null){arena=new ModelInstanceArena();collector=new ModelFrameCollector(geometry,arena);}}
    public long reloadStarted(){requireOpen();eligible=false;closePipelines();geometry.clear();return shaders.reloadStarted();}
    public void reloadFinished(long token,boolean success){requireOpen();shaders.reloadFinished(token,success);}
    public Map<String,Object> snapshot(){
        requireOwner();var result=new LinkedHashMap<String,Object>();result.put("frames",frames);result.put("preparedSubmits",preparedSubmits);
        result.put("preparedQuads",preparedQuads);result.put("preparedInstanceBytes",preparedBytes);result.put("recordedInstances",recordedInstances);
        result.put("normalCalls",normalCalls);var stages=new LinkedHashMap<String,Long>();for(var stage:OitStage.values())stages.put(stage.name(),oitCalls.getOrDefault(stage,0L));result.put("oitCalls",Map.copyOf(stages));
        result.put("fallbackGroups",fallbackGroups);result.put("fallbackSubmits",fallbackSubmits);result.put("sortedQuads",sortedQuads);
        result.put("mappedQuads",mappedQuads);result.put("decalQuads",decalQuads);result.put("maxDrawInstances",maxDrawInstances);
        result.put("modelTypes",Map.copyOf(modelTypes));result.put("cachedModels",geometry.models());result.put("cachedCubes",geometry.cubes());result.put("cachedQuads",geometry.retainedQuads());
        result.put("eligible",eligible);result.put("shaderEligibility",shaders.reason());result.put("resourceGeneration",shaders.generation());
        result.put("pipelineGenerations",pipelineGenerations);result.put("pipelineCacheClosed",compiled==null);result.put("pendingGroups",groups.size());
        result.put("staticGeometryClosed",quad==null);
        result.put("arenaClosed",arena==null);result.put("closed",closed);return Map.copyOf(result);
    }
    @Override public void close(){
        requireOwner();if(closed)return;closePipelines();if(quad!=null){quad.close();quad=null;}if(arena!=null)arena.close();
        arena=null;collector=null;geometry.clear();groups.clear();fallback.close();closed=true;
    }
    private void closePipelines(){if(compiled!=null){compiled.close();compiled=null;}}
    private void requireOwner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Model renderer belongs to its render owner");}
    private void requireOpen(){requireOwner();if(closed)throw new IllegalStateException("Model renderer is closed");}
}
