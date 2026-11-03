package dev.overhaul.minecraft.terrain;

import dev.overhaul.core.concurrent.WorkerScheduler;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Bounded worker wakeups preserve the game's priority queue while tracking tasks for safe retirement. */
public final class SectionWorkerExecutor implements AutoCloseable {
    private final WorkerScheduler workers;
    private final int workerCount;
    private final Runnable drainOne;
    private final BooleanSupplier canContinue;
    private final Consumer<Throwable> failureHandler;
    private final AtomicBoolean closing=new AtomicBoolean();
    private final AtomicInteger pumps=new AtomicInteger();
    private final AtomicLong signals=new AtomicLong(),admitted=new AtomicLong(),completed=new AtomicLong(),canceled=new AtomicLong();
    private final Object lifecycle=new Object();
    private final Set<ActiveTask> active=new HashSet<>();

    public SectionWorkerExecutor(int workerCount,int queueCapacity,Runnable drainOne,BooleanSupplier canContinue,
                                 Consumer<Throwable> failureHandler) {
        this.workerCount=workerCount;this.drainOne=java.util.Objects.requireNonNull(drainOne);
        this.canContinue=java.util.Objects.requireNonNull(canContinue);this.failureHandler=java.util.Objects.requireNonNull(failureHandler);
        workers=new WorkerScheduler(workerCount,queueCapacity,"overhaul-terrain");
    }
    /** Never blocks or executes compilation on the calling thread; redundant wakeups are coalesced. */
    public void signal() {
        if(closing.get())return;
        signals.incrementAndGet();launch();
    }
    private void launch() {
        int current=pumps.get();
        while(!closing.get()&&current<workerCount){
            if(!pumps.compareAndSet(current,current+1)){current=pumps.get();continue;}
            try{
                workers.submitOwned(()->{pump();return null;},null)
                        .whenComplete((ignored,failure)->{if(failure!=null)failureHandler.accept(failure);});
                admitted.incrementAndGet();
            }catch(RejectedExecutionException failure){
                pumps.decrementAndGet();
                // Shutdown cancels the authoritative task queue separately; an open-queue rejection is fatal.
                if(!closing.get())throw failure;
            }
            return;
        }
    }
    private void pump() {
        long observed=signals.get();
        try{
            while(!closing.get()){
                observed=signals.get();drainOne.run();
                if(observed==signals.get()&&!canContinue.getAsBoolean())break;
            }
        }finally{
            pumps.decrementAndGet();
            if(!closing.get()&&observed!=signals.get())launch();
        }
    }
    /** Registration precedes any compiler read in doTask, covering replacement's loaded-before-entry race. */
    public <T>T runTracked(Runnable cancel,Supplier<T> operation) {
        var task=new ActiveTask(java.util.Objects.requireNonNull(cancel));
        synchronized(lifecycle){active.add(task);}
        try{
            if(closing.get())task.cancel();
            return operation.get();
        }finally{
            synchronized(lifecycle){active.remove(task);}
            completed.incrementAndGet();task.finished.complete(null);
        }
    }
    /** Invoke after publishing the new compiler; releases the old one without waiting on the render thread. */
    public CompletableFuture<Void> afterCurrentTasks(Runnable release) {
        CompletableFuture<?>[] pending;
        synchronized(lifecycle){pending=active.stream().map(task->task.finished).toArray(CompletableFuture<?>[]::new);}
        return CompletableFuture.allOf(pending).thenRun(release);
    }
    public Map<String,Long> statistics() {
        int count;synchronized(lifecycle){count=active.size();}
        return Map.of("terrainWorkerCount",(long)workerCount,"terrainWorkerSignals",signals.get(),
                "terrainWorkerAdmissions",admitted.get(),"terrainWorkerPumps",(long)pumps.get(),
                "terrainWorkerPending",(long)workers.pendingTasks(),"terrainActiveTasks",(long)count,
                "terrainCompletedTasks",completed.get(),"terrainCanceledTasks",canceled.get());
    }
    /** Caller must cancel the game priority queue first and hold no staging/copy lock while joining. */
    @Override public void close() {
        if(!closing.compareAndSet(false,true))return;
        ArrayList<ActiveTask> cancel;
        synchronized(lifecycle){cancel=new ArrayList<>(active);}
        for(var task:cancel)task.cancel();
        workers.close();
    }
    private final class ActiveTask {
        private final Runnable cancellation;
        private final AtomicBoolean canceledOnce=new AtomicBoolean();
        private final CompletableFuture<Void> finished=new CompletableFuture<>();
        ActiveTask(Runnable cancellation){this.cancellation=cancellation;}
        void cancel(){if(canceledOnce.compareAndSet(false,true)){canceled.incrementAndGet();cancellation.run();}}
    }
}
