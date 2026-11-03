package dev.overhaul.minecraft.weather;

import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import dev.overhaul.minecraft.shader.InstancedQuadGeometry;
import dev.overhaul.minecraft.shader.OwnedVertexShaderSource;
import dev.overhaul.minecraft.shader.ShaderResourceGate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.renderer.oit.OitStage;
import net.minecraft.client.renderer.state.level.WeatherRenderState;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Ordinary world extraction remains authoritative; prepared columns become immutable frame-owned instances. */
public final class OverhaulWeatherRenderer extends WeatherEffectRenderer {
    private static final long MAX_INSTANCE_BYTES=8L*1024*1024;
    private static final Identifier RAIN=Identifier.withDefaultNamespace("textures/environment/rain.png");
    private static final Identifier SNOW=Identifier.withDefaultNamespace("textures/environment/snow.png");
    private static final OitStage[] STAGES=OitStage.values();
    private record Draw(GpuBufferSlice instances,GpuBufferSlice arguments,AbstractTexture texture,int count){}
    private final Thread owner=Thread.currentThread();
    private final ShaderResourceGate shaders=new ShaderResourceGate("/overhaul-particle-shaders.properties");
    private final InstancedWeatherPipelines pipelines=new InstancedWeatherPipelines();
    private final long[] oitRain=new long[STAGES.length],oitSnow=new long[STAGES.length];
    private InstancedQuadGeometry geometry;
    private PipelineCache compiled;
    private Draw rain,snow;
    private boolean eligible,fallback,closed;
    private long preparations,emptyPreparations,fallbackPreparations,rainInstances,snowInstances,instanceBytes;
    private long normalRainCalls,normalSnowCalls,recordedRainInstances,recordedSnowInstances,pipelineGenerations;
    private int lastRainCount,lastSnowCount,maxRainCount,maxSnowCount;
    private float lastIntensity;

    @Override public void prepare(Vec3 camera,WeatherRenderState state) {
        requireOpen();resetFrame();preparations++;lastIntensity=state.intensity;
        eligible=RenderSystem.getDevice().getDeviceInfo().features().drawIndirect()
                &&shaders.eligible(Minecraft.getInstance().getResourceManager());
        long count=(long)state.rainColumns.size()+state.snowColumns.size();
        if(count==0){emptyPreparations++;return;}
        if(!eligible||count>MAX_INSTANCE_BYTES/InstancedWeatherPipelines.INSTANCE_STRIDE) {
            fallback=true;fallbackPreparations++;super.prepare(camera,state);return;
        }
        if(geometry==null)geometry=new InstancedQuadGeometry(RenderSystem.getDevice());
        rain=copy(state.rainColumns,camera,1,state.radius,state.intensity,RAIN);
        snow=copy(state.snowColumns,camera,.8f,state.radius,state.intensity,SNOW);
        lastRainCount=rain==null?0:rain.count();lastSnowCount=snow==null?0:snow.count();
        maxRainCount=Math.max(maxRainCount,lastRainCount);maxSnowCount=Math.max(maxSnowCount,lastSnowCount);
        rainInstances+=lastRainCount;snowInstances+=lastSnowCount;instanceBytes+=count*InstancedWeatherPipelines.INSTANCE_STRIDE;
    }
    private Draw copy(List<ColumnInstance> columns,Vec3 camera,float nearOpacity,int radius,float intensity,Identifier texture) {
        if(columns.isEmpty())return null;
        var memory=RenderSystem.getDevice().createCommandEncoder().transientMemory();GpuBufferSlice instances,arguments;
        try(var mapped=memory.allocateGpuMapped((long)columns.size()*InstancedWeatherPipelines.INSTANCE_STRIDE,4,GpuBuffer.USAGE_VERTEX)) {
            var writer=new WeatherInstanceWriter(mapped.data(),columns.size(),(WeatherGeometryAccess)(Object)this,camera,nearOpacity,radius,intensity);
            for(var column:columns)writer.write(column);writer.finish();instances=mapped.slice();
        }
        try(var mapped=memory.allocateGpuMapped(20,4,GpuBuffer.USAGE_INDIRECT_PARAMETERS)) {
            mapped.data().order(ByteOrder.nativeOrder()).putInt(6).putInt(columns.size()).putInt(0).putInt(0).putInt(0);arguments=mapped.slice();
        }
        return new Draw(instances,arguments,Minecraft.getInstance().getTextureManager().getTexture(texture),columns.size());
    }
    @Override public void render(WeatherRenderState state,RenderPass pass) {
        requireOpen();if(fallback){super.render(state,pass);return;}renderInstances(pass,RenderPipelines.WEATHER,null);
    }
    @Override public void renderOit(OitStage stage,WeatherRenderState state,RenderPass pass) {
        requireOpen();if(fallback){super.renderOit(stage,state,pass);return;}renderInstances(pass,RenderPipelines.OIT_WEATHER.getPipeline(stage),stage);
    }
    private void renderInstances(RenderPass pass,RenderPipeline original,OitStage stage) {
        if(rain==null&&snow==null)return;
        if(compiled==null){compiled=new PipelineCache(RenderSystem.getDevice(),new OwnedVertexShaderSource(Minecraft.getInstance().getResourceManager()));pipelineGenerations++;}
        var pipeline=compiled.get(pipelines.variant(original));if(pipeline==null)throw new IllegalStateException("Owned weather pipeline failed to compile");
        pass.pushDebugGroup(()->"Overhaul instanced weather");
        try {
            pass.setPipeline(pipeline);RenderSystem.bindDefaultUniforms(pass);
            // Preserve the original draw-time model-view value; preparation can run in a different matrix scope.
            pass.setUniform("DynamicTransforms",RenderSystem.getDynamicUniforms().writeTransform(RenderSystem.getModelViewMatrixCopy()));
            pass.setUniform("Sampler2",Minecraft.getInstance().gameRenderer.lightmap(),RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            draw(pass,rain,true,stage);draw(pass,snow,false,stage);
        }finally{pass.popDebugGroup();}
    }
    private void draw(RenderPass pass,Draw draw,boolean isRain,OitStage stage) {
        if(draw==null)return;
        geometry.bind(pass,draw.instances());pass.setUniform("Sampler0",draw.texture().getTextureView(),draw.texture().getSampler());
        pass.drawIndexedIndirect(draw.arguments(),1);
        if(isRain){recordedRainInstances+=draw.count();if(stage==null)normalRainCalls++;else oitRain[stage.ordinal()]++;}
        else{recordedSnowInstances+=draw.count();if(stage==null)normalSnowCalls++;else oitSnow[stage.ordinal()]++;}
    }
    public void resetFrame(){requireOwner();rain=snow=null;lastRainCount=lastSnowCount=0;fallback=false;}
    public long reloadStarted(){requireOpen();eligible=false;resetFrame();closePipelines();return shaders.reloadStarted();}
    public void reloadFinished(long token,boolean success){requireOpen();shaders.reloadFinished(token,success);}
    public Map<String,Object> snapshot() {
        requireOwner();var result=new LinkedHashMap<String,Object>();result.put("preparations",preparations);result.put("emptyPreparations",emptyPreparations);
        result.put("fallbackPreparations",fallbackPreparations);result.put("preparedRainInstances",rainInstances);result.put("preparedSnowInstances",snowInstances);
        result.put("preparedInstanceBytes",instanceBytes);result.put("normalRainCalls",normalRainCalls);result.put("normalSnowCalls",normalSnowCalls);
        result.put("recordedRainInstances",recordedRainInstances);result.put("recordedSnowInstances",recordedSnowInstances);
        var rainStages=new LinkedHashMap<String,Long>();var snowStages=new LinkedHashMap<String,Long>();
        for(var stage:STAGES){rainStages.put(stage.name(),oitRain[stage.ordinal()]);snowStages.put(stage.name(),oitSnow[stage.ordinal()]);}
        result.put("oitRainCalls",rainStages);result.put("oitSnowCalls",snowStages);result.put("lastRainCount",lastRainCount);result.put("lastSnowCount",lastSnowCount);
        result.put("maxRainCount",maxRainCount);result.put("maxSnowCount",maxSnowCount);result.put("lastIntensity",lastIntensity);
        result.put("eligible",eligible);result.put("shaderEligibility",shaders.reason());result.put("resourceGeneration",shaders.generation());
        result.put("pipelineGenerations",pipelineGenerations);result.put("pipelineCacheClosed",compiled==null);result.put("closed",closed);return Map.copyOf(result);
    }
    @Override public void close() {
        requireOwner();if(closed)return;resetFrame();closePipelines();if(geometry!=null)geometry.close();super.close();closed=true;
    }
    private void closePipelines(){if(compiled!=null){compiled.close();compiled=null;}}
    private void requireOwner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Weather renderer belongs to its render owner");}
    private void requireOpen(){requireOwner();if(closed)throw new IllegalStateException("Weather renderer is closed");}
}
