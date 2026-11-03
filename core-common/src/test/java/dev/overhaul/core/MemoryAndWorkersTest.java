package dev.overhaul.core;

import dev.overhaul.core.concurrent.BoundedMpmcQueue;
import dev.overhaul.core.concurrent.WorkerScheduler;
import dev.overhaul.core.memory.DirectSlabPool;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MemoryAndWorkersTest {
    @Test void slabsAreExclusiveBoundedAndReusable() {
        DirectSlabPool pool = new DirectSlabPool(64, 2);
        DirectSlabPool.Lease first = pool.acquire(32);
        DirectSlabPool.Lease second = pool.acquire(64);
        first.buffer().putInt(0, 42);
        second.buffer().putInt(0, 99);
        assertEquals(42, first.buffer().getInt(0));
        assertThrows(IllegalStateException.class, () -> pool.acquire(1));
        first.close(); first.close();
        assertEquals(1, pool.activeLeases());
        assertThrows(IllegalStateException.class, first::buffer);
        try (DirectSlabPool.Lease recycled = pool.acquire(64)) { assertTrue(recycled.buffer().isDirect()); }
        pool.close();
        assertThrows(IllegalStateException.class, () -> pool.acquire(1));
        second.close();
        assertEquals(0, pool.activeLeases());
    }

    @Test void queueDoesNotLoseConcurrentProducers() throws Exception {
        BoundedMpmcQueue<Integer> queue = new BoundedMpmcQueue<Integer>(32);
        int producers = 4, each = 2000;
        Thread[] threads = new Thread[producers];
        CountDownLatch start = new CountDownLatch(1);
        for (int p = 0; p < producers; p++) {
            final int base = p * each;
            threads[p] = new Thread(() -> {
                try { start.await(); } catch (InterruptedException e) { throw new AssertionError(e); }
                for (int i = 0; i < each; i++) while (!queue.offer(base + i)) Thread.yield();
            });
            threads[p].start();
        }
        boolean[] observed = new boolean[producers * each];
        start.countDown();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        for (int count = 0; count < observed.length;) {
            assertTrue(System.nanoTime() < deadline, "queue made no progress");
            Integer next = queue.poll();
            if (next == null) { Thread.yield(); continue; }
            assertFalse(observed[next], "duplicate delivery");
            observed[next] = true;
            count++;
        }
        for (Thread thread : threads) thread.join();
        assertNull(queue.poll());
    }

    @Test void workerDrainCompletesExactlyOnce() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        List<CompletableFuture<Integer>> futures = new ArrayList<CompletableFuture<Integer>>();
        WorkerScheduler scheduler = new WorkerScheduler(3, 128, "test-worker");
        for (int i = 0; i < 100; i++) futures.add(scheduler.submit(calls::incrementAndGet));
        scheduler.close();
        for (CompletableFuture<Integer> future : futures) assertTrue(future.get() > 0);
        assertEquals(100, calls.get());
        assertEquals(0, scheduler.pendingTasks());
        assertThrows(RejectedExecutionException.class, () -> scheduler.submit(() -> 1));
    }

    @Test void cancellationClosesUndeliverableResources() throws Exception {
        WorkerScheduler scheduler = new WorkerScheduler(1, 1, "test-cancel");
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger closes = new AtomicInteger();
        CompletableFuture<AutoCloseable> result = scheduler.submit(() -> {
            entered.countDown();
            release.await();
            return closes::incrementAndGet;
        });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        result.cancel(false);
        release.countDown();
        scheduler.close();
        assertEquals(1, closes.get());
    }

    @Test void fullQueueRejectsWithoutRunningOnCaller() throws Exception {
        WorkerScheduler scheduler = new WorkerScheduler(1, 1, "test-bounded");
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try {
            scheduler.submit(() -> { entered.countDown(); release.await(); return 0; });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            scheduler.submit(() -> 1);
            assertThrows(RejectedExecutionException.class, () -> scheduler.submit(() -> 2));
        } finally { release.countDown(); scheduler.close(); }
    }

    @Test void ownedInputIsReleasedWhenQueuedWorkIsCanceledOrRejected() throws Exception {
        WorkerScheduler scheduler = new WorkerScheduler(1, 1, "test-owned");
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger closes = new AtomicInteger();
        try {
            scheduler.submit(() -> { entered.countDown(); release.await(); return 0; });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            CompletableFuture<Integer> queued = scheduler.submitOwned(() -> 1, closes::incrementAndGet);
            queued.cancel(false);
            assertThrows(RejectedExecutionException.class,
                    () -> scheduler.submitOwned(() -> 2, closes::incrementAndGet));
            assertEquals(1, closes.get());
        } finally { release.countDown(); scheduler.close(); }
        assertEquals(2, closes.get());
    }
}
