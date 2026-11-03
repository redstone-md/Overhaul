package dev.overhaul.minecraft.backendmixin;

import dev.overhaul.minecraft.terrain.SectionWorkerExecutor;
import dev.overhaul.minecraft.terrain.TerrainCompilerAccess;
import dev.overhaul.minecraft.terrain.TerrainDispatcherAccess;
import net.minecraft.CrashReport;
import net.minecraft.TracingExecutor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.SectionBufferBuilderPool;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Keeps vanilla task selection/pack ownership, replacing only dispatcher worker execution. */
@Mixin(SectionRenderDispatcher.class)
public abstract class SectionDispatcherMixin implements TerrainDispatcherAccess {
    @Shadow @Final private SectionBufferBuilderPool bufferPool;
    @Shadow private volatile boolean closed;
    @Shadow private volatile SectionCompiler sectionCompiler;
    @Shadow private void runTask(){throw new AssertionError("shadow");}
    @Shadow public abstract void clearCompileQueue();
    @Shadow public abstract int getCompileQueueSize();
    @Unique private SectionWorkerExecutor overhaul$workers;
    @Unique private SectionCompiler overhaul$retiredCompiler;

    @Inject(method="<init>",at=@At("RETURN"))
    private void overhaul$createWorkers(CallbackInfo callback){
        int count=Math.min(8,Math.min(Math.max(1,Runtime.getRuntime().availableProcessors()-1),Math.max(1,bufferPool.getFreeBufferCount())));
        overhaul$workers=new SectionWorkerExecutor(count,256,this::runTask,
                ()->getCompileQueueSize()>0&&bufferPool.getFreeBufferCount()>0,SectionDispatcherMixin::overhaul$failed);
    }
    @Redirect(method={"schedule","runTask"},at=@At(value="INVOKE",target="Lnet/minecraft/TracingExecutor;execute(Ljava/lang/Runnable;)V"))
    private void overhaul$dispatch(TracingExecutor ignored,Runnable wakeup){overhaul$workers.signal();}

    @Redirect(method="runTask",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection$SectionTask;doTask(Lnet/minecraft/client/renderer/SectionBufferBuilderPack;)Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection$SectionTask$SectionTaskResult;"))
    private SectionRenderDispatcher.RenderSection.SectionTask.SectionTaskResult overhaul$trackTask(
            SectionRenderDispatcher.RenderSection.SectionTask task,SectionBufferBuilderPack pack){
        return overhaul$workers.runTracked(task::cancel,()->{
            try{return task.doTask(pack);}
            catch(RuntimeException|Error failure){
                task.cancel();
                try{pack.discardAll();}catch(RuntimeException|Error cleanup){failure.addSuppressed(cleanup);}
                finally{bufferPool.release(pack);}
                throw failure;
            }
        });
    }
    @Inject(method="setCompiler",at=@At("HEAD"))
    private void overhaul$captureCompiler(SectionCompiler replacement,CallbackInfo callback){overhaul$retiredCompiler=sectionCompiler;}
    @Inject(method="setCompiler",at=@At("RETURN"))
    private void overhaul$retireCompiler(SectionCompiler replacement,CallbackInfo callback){
        var previous=overhaul$retiredCompiler;overhaul$retiredCompiler=null;
        if(previous!=replacement&&previous instanceof TerrainCompilerAccess access)
            overhaul$workers.afterCurrentTasks(access.overhaul$compiler()::close)
                    .whenComplete((ignored,failure)->{if(failure!=null)overhaul$failed(failure);});
    }
    @Inject(method="dispose",at=@At("HEAD"))
    private void overhaul$stopWorkers(CallbackInfo callback){
        closed=true;clearCompileQueue();overhaul$workers.close();
        if(sectionCompiler instanceof TerrainCompilerAccess access)access.overhaul$compiler().close();
    }
    @Unique private static void overhaul$failed(Throwable failure){
        Minecraft.getInstance().delayCrash(CrashReport.forThrowable(failure,"Overhaul section workers"));
    }
    @Override public SectionCompiler overhaul$sectionCompiler(){return sectionCompiler;}
    @Override public CompletableFuture<Void> overhaul$afterCurrentTasks(Runnable release){return overhaul$workers.afterCurrentTasks(release);}
    @Override public Map<String,Long> overhaul$workerStatistics(){return overhaul$workers.statistics();}
}
