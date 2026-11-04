package dev.overhaul.minecraft.backend;

import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import com.mojang.renderpearl.frontend.FrontendRenderPipeline;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Shared game frontend with owner-scoped native frame access. */
public final class OverhaulGpuDevice extends FrontendGpuDevice {
    private final OverhaulDeviceBackend backend;
    OverhaulGpuDevice(OverhaulDeviceBackend backend) { super(backend); this.backend=backend; }
    public NativeFrameResources nativeFrames(){backend.requireOpen();return backend;}
    @Override public CompletableFuture<CompiledRenderPipeline.Pending> compilePipeline(RenderPipeline pipeline,ShaderSource source,Executor executor){
        PipelineCompileMetadata metadata=PipelineCompileMetadata.capture(pipeline);
        return super.compilePipeline(pipeline,source,work->executor.execute(()->backend.compileWithMetadata(metadata,work)))
                .thenApply(this::normalized);
    }
    private CompiledRenderPipeline.Pending normalized(CompiledRenderPipeline.Pending pending){
        if(pending==null||pending==CompiledRenderPipeline.Pending.NULL)return pending;
        return ()->normalize(pending.finishCompile());
    }
    private CompiledRenderPipeline normalize(CompiledRenderPipeline compiled){
        if(!(compiled instanceof FrontendRenderPipeline frontend)
                ||!(frontend.backendRenderPipeline() instanceof OverhaulPipeline nativePipeline)
                ||nativePipeline.owner()!=backend)return compiled;
        try{
            // PipelineBuilder assigns these dense slots to SPIR-V before backend compilation.
            // Its separate frontend validation list still contains inactive source declarations.
            var reflected=nativePipeline.uniforms();
            if(frontend.uniformIndices().size()!=reflected.size())
                throw new IllegalStateException("Frontend uniform map differs from reflected pipeline "+frontend.name());
            for(int slot=0;slot<reflected.size();slot++){
                String name=reflected.get(slot).name();
                if(frontend.uniformIndices().getOrDefault(name,-1)!=slot)
                    throw new IllegalStateException("Frontend/native uniform binding mismatch for "+name+" in "+frontend.name());
            }
            if(frontend.uniforms().equals(reflected))return frontend;
            return new FrontendRenderPipeline(frontend.name(),frontend.backendRenderPipeline(),frontend.vertexFormats(),
                    frontend.uniformIndices(),reflected,frontend.colorTargetStates(),frontend.wantsDepthTexture(),frontend.pushConstantSize());
        }catch(RuntimeException|Error failure){
            try{compiled.close();}catch(RuntimeException|Error cleanup){failure.addSuppressed(cleanup);}throw failure;
        }
    }
    public CompletableFuture<String> awaitWorldCapture() { return backend.awaitWorldCapture(); }
    public CompletableFuture<String> awaitWorldCapture(String label) { return backend.awaitWorldCapture(label); }
}
