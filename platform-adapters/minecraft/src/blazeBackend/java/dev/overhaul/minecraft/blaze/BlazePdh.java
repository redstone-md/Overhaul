package dev.overhaul.minecraft.blaze;

import dev.overhaul.minecraft.blaze.BlazeHardware.Reading;
import java.nio.ByteBuffer;
import org.lwjgl.system.APIUtil;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.SharedLibrary;

/**
 * Windows performance counters: the processor's clock and the board's thermal zone on any machine, and the load of the 3D engine
 * for a card whose vendor library is not there. Rate counters need two collections, so the first sample tells
 * nothing. Used from the sensor thread alone.
 */
final class BlazePdh implements BlazeHardware.Source {
    private static final int DOUBLE_UNCAPPED = 0x200 | 0x8000, MORE_DATA = 0x800007D2, VALUE = 16, ITEM = 24;
    private final long collect, formatted, array, query, scratch = MemoryUtil.nmemAllocChecked(VALUE);
    private final long performance, frequency, zones, engines;
    private long items, capacity;

    private BlazePdh(SharedLibrary library, long query, long add, boolean gpu) {
        this.query = query;
        collect = library.getFunctionAddress("PdhCollectQueryData");
        formatted = library.getFunctionAddress("PdhGetFormattedCounterValue");
        array = library.getFunctionAddress("PdhGetFormattedCounterArrayW");
        performance = counter(add, "\\Processor Information(_Total)\\% Processor Performance");
        frequency = counter(add, "\\Processor Information(_Total)\\Processor Frequency");
        zones = counter(add, "\\Thermal Zone Information(*)\\Temperature");
        engines = gpu ? counter(add, "\\GPU Engine(*engtype_3D)\\Utilization Percentage") : 0;
    }

    /** A counter query, or null where the counter library is missing. {@code gpu} asks for the card's load as well. */
    static BlazePdh open(boolean gpu) {
        try {
            SharedLibrary library = APIUtil.apiCreateLibrary("pdh");
            long open = library.getFunctionAddress("PdhOpenQueryW"), add = library.getFunctionAddress("PdhAddEnglishCounterW");
            if (open == 0 || add == 0) return null;
            long slot = MemoryUtil.nmemAllocChecked(Long.BYTES);
            try {
                if (JNI.invokePPPI(0L, 0L, slot, open) != 0) return null;
                BlazePdh counters = new BlazePdh(library, MemoryUtil.memGetAddress(slot), add, gpu);
                return counters.collect == 0 || counters.formatted == 0 || counters.array == 0 ? null : counters;
            } finally {
                MemoryUtil.nmemFree(slot);
            }
        } catch (Throwable missing) {
            return null;
        }
    }

    private long counter(long add, String path) {
        ByteBuffer wide = MemoryUtil.memUTF16(path, true);
        try {
            return JNI.invokePPPPI(query, MemoryUtil.memAddress(wide), 0L, scratch, add) == 0 ? MemoryUtil.memGetAddress(scratch) : 0;
        } finally {
            MemoryUtil.memFree(wide);
        }
    }

    @Override
    public void sample(double[] readings) {
        if (JNI.invokePI(query, collect) != 0) return;
        double share = one(performance), base = one(frequency);
        if (share > 0 && base > 0) readings[Reading.CPU_CLOCK.ordinal()] = base * share / 100;
        double kelvin = all(zones, false);
        if (kelvin > 0) readings[Reading.ZONE_TEMPERATURE.ordinal()] = kelvin - 273.15;
        double load = all(engines, true);
        if (load >= 0 && Double.isNaN(readings[Reading.GPU_LOAD.ordinal()])) readings[Reading.GPU_LOAD.ordinal()] = Math.min(load, 100);
    }

    private double one(long counter) {
        if (counter == 0 || JNI.invokePPPI(counter, DOUBLE_UNCAPPED, 0L, scratch, formatted) != 0 || MemoryUtil.memGetInt(scratch) != 0) return -1;
        return MemoryUtil.memGetDouble(scratch + 8);
    }

    /** Every instance of a wildcard counter, summed or the largest; negative when none answers. */
    private double all(long counter, boolean sum) {
        if (counter == 0) return -1;
        long size = scratch, count = scratch + 4;
        MemoryUtil.memPutInt(size, (int) capacity);
        int status = JNI.callPPPPI(counter, DOUBLE_UNCAPPED, size, count, items, array);
        if (status == MORE_DATA) {
            capacity = Integer.toUnsignedLong(MemoryUtil.memGetInt(size)) + 4096;
            items = MemoryUtil.nmemRealloc(items, capacity);
            MemoryUtil.memPutInt(size, (int) capacity);
            status = JNI.callPPPPI(counter, DOUBLE_UNCAPPED, size, count, items, array);
        }
        if (status != 0) return -1;
        double result = -1;
        for (int index = 0, found = MemoryUtil.memGetInt(count); index < found; index++) {
            long item = items + (long) index * ITEM;
            if (MemoryUtil.memGetInt(item + 8) != 0) continue;
            double value = MemoryUtil.memGetDouble(item + 16);
            result = result < 0 ? value : sum ? result + value : Math.max(result, value);
        }
        return result;
    }
}
