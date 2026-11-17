package dev.overhaul.minecraft.blaze;

import java.util.Arrays;
import org.lwjgl.system.Platform;

/**
 * What the machine's own sensors say: GPU load, temperature, clock, power and fan, the processor's clock and the
 * board's thermal zone. The driver and counter calls behind them take milliseconds, so a daemon thread samples once a
 * second while someone has asked in the last few seconds, and readers get the last sample at the cost of a volatile
 * read. Nothing loads until the first question, and a sensor the machine does not expose reads as unknown.
 */
final class BlazeHardware {
    enum Reading { GPU_LOAD, GPU_TEMPERATURE, GPU_CLOCK, GPU_POWER, GPU_FAN, CPU_CLOCK, ZONE_TEMPERATURE }

    /** One place readings come from; it fills the slots it can tell and leaves the rest alone. */
    interface Source {
        void sample(double[] readings);
    }

    private static final long PERIOD_MILLIS = 1000, IDLE_NANOS = 3_000_000_000L;
    private static final int COUNT = Reading.values().length;
    private static final Object LOCK = new Object();
    private static volatile double[] readings = unknown();
    private static volatile long askedAt;
    private static volatile String card;
    private static Thread sampler;

    private BlazeHardware() {}

    /** The last sample of a reading, or NaN while none is known. {@code device} names the card the renderer runs on. */
    static double get(Reading reading, String device) {
        card = device;
        askedAt = System.nanoTime();
        synchronized (LOCK) {
            if (sampler == null) {
                sampler = new Thread(BlazeHardware::run, "Overhaul sensors");
                sampler.setDaemon(true);
                sampler.setPriority(Thread.MIN_PRIORITY);
                sampler.start();
            } else {
                LOCK.notifyAll();
            }
        }
        return readings[reading.ordinal()];
    }

    private static double[] unknown() {
        double[] values = new double[COUNT];
        Arrays.fill(values, Double.NaN);
        return values;
    }

    private static void run() {
        Source[] sources = null;
        try {
            while (true) {
                if (System.nanoTime() - askedAt > IDLE_NANOS) {
                    readings = unknown();
                    synchronized (LOCK) {
                        while (System.nanoTime() - askedAt > IDLE_NANOS) LOCK.wait();
                    }
                }
                if (sources == null && card != null) sources = sources(card);
                if (sources != null) {
                    double[] next = unknown();
                    for (Source source : sources) source.sample(next);
                    readings = next;
                }
                Thread.sleep(PERIOD_MILLIS);
            }
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        }
    }

    /** The vendor's library first, so its exact figures win; the system's counters fill what it left unknown. */
    private static Source[] sources(String device) {
        if (Platform.get() != Platform.WINDOWS) return new Source[0];
        Source vendor = BlazeNvml.open(device), counters = BlazePdh.open(vendor == null);
        if (vendor == null) return counters == null ? new Source[0] : new Source[]{counters};
        return counters == null ? new Source[]{vendor} : new Source[]{vendor, counters};
    }
}
