package dev.overhaul.minecraft.terrain;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class SectionWorkerExecutorTest {
    @Test void saturatedWakeupsStayBoundedAndEveryLogicalRequestRunsOffTheCallingThread() throws Exception {
        var queue=new ConcurrentLinkedQueue<Integer>();var seen=ConcurrentHashMap.<Integer>newKeySet();
        Set<String> threads=ConcurrentHashMap.newKeySet();var errors=new ConcurrentLinkedQueue<Throwable>();
        var started=new CountDownLatch(2);var release=new CountDownLatch(1);var done=new CountDownLatch(1002);
        var holder=new AtomicReference<SectionWorkerExecutor>();
        var executor=new SectionWorkerExecutor(2,256,()->{
            Integer value=queue.poll();if(value==null)return;
            holder.get().runTracked(release::countDown,()->{
                threads.add(Thread.currentThread().getName());
                if(value<2){started.countDown();await(release);}
                if(!seen.add(value))throw new IllegalStateException("Duplicate task "+value);
                done.countDown();return null;
            });
        },()->!queue.isEmpty(),errors::add);holder.set(executor);
        try{
            queue.add(0);executor.signal();queue.add(1);executor.signal();
            assertTrue(started.await(5,TimeUnit.SECONDS));
            for(int i=2;i<1002;i++){queue.add(i);for(int wake=0;wake<10;wake++)executor.signal();}
            assertEquals(2,executor.statistics().get("terrainWorkerPumps"));
            assertTrue(executor.statistics().get("terrainWorkerPending")<=2,"Coalescing must bound admitted jobs independently of request count");
            assertEquals(2,executor.statistics().get("terrainWorkerAdmissions"));
            release.countDown();assertTrue(done.await(5,TimeUnit.SECONDS));
        }finally{release.countDown();executor.close();}
        assertEquals(1002,seen.size());assertTrue(queue.isEmpty());assertTrue(errors.isEmpty(),errors.toString());
        assertTrue(threads.stream().allMatch(name->name.startsWith("overhaul-terrain-")));
        assertEquals(0,executor.statistics().get("terrainActiveTasks"));assertEquals(0,executor.statistics().get("terrainWorkerPending"));
    }

    @Test void closeCancelsActiveStagingWaitsBeforeJoiningAndRetiresCompilerAfterTheirExit() throws Exception {
        var holder=new AtomicReference<SectionWorkerExecutor>();var entered=new CountDownLatch(2);
        var started=new AtomicInteger();var exited=new AtomicInteger();var retirements=new AtomicInteger();
        var errors=new ConcurrentLinkedQueue<Throwable>();
        var executor=new SectionWorkerExecutor(2,256,()->{
            if(started.getAndIncrement()>=2)return;
            var canceled=new AtomicBoolean();
            holder.get().runTracked(()->canceled.set(true),()->{
                entered.countDown();while(!canceled.get())Thread.onSpinWait();exited.incrementAndGet();return null;
            });
        },()->false,errors::add);holder.set(executor);
        try{
            executor.signal();executor.signal();assertTrue(entered.await(5,TimeUnit.SECONDS));
            var retired=executor.afterCurrentTasks(()->{assertEquals(2,exited.get());retirements.incrementAndGet();});
            assertFalse(retired.isDone());assertEquals(0,retirements.get());
            assertTimeoutPreemptively(Duration.ofSeconds(5),executor::close);
            retired.get(5,TimeUnit.SECONDS);assertEquals(1,retirements.get());
            assertEquals(2,executor.statistics().get("terrainCanceledTasks"));assertEquals(2,exited.get());
            executor.signal();assertEquals(2,executor.statistics().get("terrainWorkerSignals"));
        }finally{executor.close();}
        assertTrue(errors.isEmpty(),errors.toString());
    }

    @Test void replacementRetirementCoversOldCompilerLoadedBeforeItsCompileMethodWasEntered() throws Exception {
        var holder=new AtomicReference<SectionWorkerExecutor>();var queue=new ConcurrentLinkedQueue<Runnable>();
        var old=new Object();var replacement=new Object();var compiler=new AtomicReference<>(old);
        var loaded=new CountDownLatch(1);var enterCompile=new CountDownLatch(1);var usedNew=new CountDownLatch(1);
        var oldClosed=new AtomicBoolean();var errors=new ConcurrentLinkedQueue<Throwable>();
        var executor=new SectionWorkerExecutor(1,256,()->{
            Runnable work=queue.poll();if(work!=null)holder.get().runTracked(enterCompile::countDown,()->{work.run();return null;});
        },()->!queue.isEmpty(),errors::add);holder.set(executor);
        try{
            queue.add(()->{
                Object captured=compiler.get();loaded.countDown();await(enterCompile);
                assertSame(old,captured);assertFalse(oldClosed.get(),"Loaded old compiler must remain open through its call");
            });executor.signal();assertTrue(loaded.await(5,TimeUnit.SECONDS));
            compiler.set(replacement);
            var retirement=executor.afterCurrentTasks(()->oldClosed.set(true));assertFalse(retirement.isDone());
            queue.add(()->{assertSame(replacement,compiler.get());assertTrue(oldClosed.get());usedNew.countDown();});executor.signal();
            enterCompile.countDown();retirement.get(5,TimeUnit.SECONDS);assertTrue(usedNew.await(5,TimeUnit.SECONDS));
        }finally{enterCompile.countDown();executor.close();}
        assertTrue(errors.isEmpty(),errors.toString());
    }

    @Test void failedTasksReleaseTheirRetirementLeaseAndReachTheFailureHandler() throws Exception {
        var holder=new AtomicReference<SectionWorkerExecutor>();var failed=new CountDownLatch(1);
        var failure=new AtomicReference<Throwable>();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var executor=new SectionWorkerExecutor(1,256,()->holder.get().runTracked(release::countDown,()->{
            entered.countDown();await(release);throw new IllegalStateException("worker compile rejected");
        }),()->false,error->{failure.set(error);failed.countDown();});holder.set(executor);
        try{
            executor.signal();assertTrue(entered.await(5,TimeUnit.SECONDS));var released=new AtomicBoolean();
            var retirement=executor.afterCurrentTasks(()->released.set(true));assertFalse(retirement.isDone());
            release.countDown();assertTrue(failed.await(5,TimeUnit.SECONDS));retirement.get(5,TimeUnit.SECONDS);
            assertTrue(released.get());assertInstanceOf(IllegalStateException.class,failure.get());
            assertEquals(0,executor.statistics().get("terrainActiveTasks"));
        }finally{release.countDown();executor.close();}
    }

    private static void await(CountDownLatch latch){
        try{if(!latch.await(5,TimeUnit.SECONDS))throw new IllegalStateException("Test task did not resume");}
        catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IllegalStateException(failure);}
    }
}
