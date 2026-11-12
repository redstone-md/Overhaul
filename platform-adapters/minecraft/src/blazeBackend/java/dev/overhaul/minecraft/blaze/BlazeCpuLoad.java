package dev.overhaul.minecraft.blaze;

import java.lang.management.ManagementFactory;

/**
 * The process's CPU load, sampled off the render thread. On Windows each {@code getProcessCpuLoad} call goes through
 * the performance counter library and can take tens of milliseconds, a visible hitch if the overlay asks for it on
 * the render thread. A daemon thread samples once a second while someone has asked in the last few seconds and
 * otherwise waits; readers get the last sample at the cost of a volatile read.
 */
final class BlazeCpuLoad {
    private static final long PERIOD_MILLIS = 1000, IDLE_NANOS = 3_000_000_000L;
    private static final com.sun.management.OperatingSystemMXBean BEAN =
            ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean bean ? bean : null;
    private static final Object LOCK = new Object();
    private static volatile double load = -1;
    private static volatile long askedAt;
    private static Thread sampler;

    private BlazeCpuLoad() {}

    /** The last sampled load in [0, 1], or a negative value while none is known or the platform cannot tell. */
    static double get() {
        if (BEAN == null) return -1;
        askedAt = System.nanoTime();
        synchronized (LOCK) {
            if (sampler == null) {
                sampler = new Thread(BlazeCpuLoad::run, "Overhaul CPU load");
                sampler.setDaemon(true);
                sampler.setPriority(Thread.MIN_PRIORITY);
                sampler.start();
            } else {
                LOCK.notifyAll();
            }
        }
        return load;
    }

    private static void run() {
        try {
            while (true) {
                if (System.nanoTime() - askedAt > IDLE_NANOS) {
                    load = -1;
                    synchronized (LOCK) {
                        while (System.nanoTime() - askedAt > IDLE_NANOS) LOCK.wait();
                    }
                }
                load = BEAN.getProcessCpuLoad();
                Thread.sleep(PERIOD_MILLIS);
            }
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        }
    }
}
