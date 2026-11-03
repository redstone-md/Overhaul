package dev.overhaul.minecraft.backend;

import com.mojang.renderpearl.frontend.FrontendGpuDevice;
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
        return super.compilePipeline(pipeline,source,work->executor.execute(()->backend.compileWithMetadata(metadata,work)));
    }
    public CompletableFuture<String> awaitWorldCapture() { return backend.awaitWorldCapture(); }
    public CompletableFuture<String> awaitWorldCapture(String label) { return backend.awaitWorldCapture(label); }
}
