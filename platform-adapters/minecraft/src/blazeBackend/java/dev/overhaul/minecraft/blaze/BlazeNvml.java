package dev.overhaul.minecraft.blaze;

import dev.overhaul.minecraft.blaze.BlazeHardware.Reading;
import java.util.Locale;
import org.lwjgl.system.APIUtil;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.SharedLibrary;

/**
 * An NVIDIA card's sensors through the management library its driver installs. Read only: nothing here changes a
 * clock, a fan or a limit. Used from the sensor thread alone.
 */
final class BlazeNvml implements BlazeHardware.Source {
    private static final int NAME = 96;
    private final long device, scratch = MemoryUtil.nmemAllocChecked(NAME);
    private final long utilization, temperature, clock, power, fan;

    private BlazeNvml(SharedLibrary library, long device) {
        this.device = device;
        utilization = library.getFunctionAddress("nvmlDeviceGetUtilizationRates");
        temperature = library.getFunctionAddress("nvmlDeviceGetTemperature");
        clock = library.getFunctionAddress("nvmlDeviceGetClockInfo");
        power = library.getFunctionAddress("nvmlDeviceGetPowerUsage");
        fan = library.getFunctionAddress("nvmlDeviceGetFanSpeed");
    }

    /** The card the renderer runs on, or null when it is no NVIDIA card or the library is missing. */
    static BlazeNvml open(String card) {
        String wanted = card.toLowerCase(Locale.ROOT);
        if (!wanted.contains("nvidia") && !wanted.contains("geforce") && !wanted.contains("quadro")) return null;
        try {
            SharedLibrary library = APIUtil.apiCreateLibrary("nvml");
            long init = library.getFunctionAddress("nvmlInit_v2"), count = library.getFunctionAddress("nvmlDeviceGetCount_v2");
            long byIndex = library.getFunctionAddress("nvmlDeviceGetHandleByIndex_v2"), name = library.getFunctionAddress("nvmlDeviceGetName");
            if (init == 0 || count == 0 || byIndex == 0 || JNI.invokeI(init) != 0) return null;
            long slot = MemoryUtil.nmemAllocChecked(NAME);
            try {
                int devices = JNI.invokePI(slot, count) == 0 ? MemoryUtil.memGetInt(slot) : 0;
                long first = 0;
                for (int index = 0; index < devices; index++) {
                    if (JNI.invokePI(index, slot, byIndex) != 0) continue;
                    long handle = MemoryUtil.memGetAddress(slot);
                    if (first == 0) first = handle;
                    if (devices == 1 || name == 0 || JNI.invokePPI(handle, slot, NAME, name) != 0) continue;
                    String found = MemoryUtil.memASCII(slot).toLowerCase(Locale.ROOT);
                    if (wanted.contains(found) || found.contains(wanted)) return new BlazeNvml(library, handle);
                }
                return first == 0 ? null : new BlazeNvml(library, first);
            } finally {
                MemoryUtil.nmemFree(slot);
            }
        } catch (Throwable missing) {
            return null;
        }
    }

    @Override
    public void sample(double[] readings) {
        if (utilization != 0 && JNI.invokePPI(device, scratch, utilization) == 0) readings[Reading.GPU_LOAD.ordinal()] = value();
        if (temperature != 0 && JNI.invokePPI(device, 0, scratch, temperature) == 0) readings[Reading.GPU_TEMPERATURE.ordinal()] = value();
        if (clock != 0 && JNI.invokePPI(device, 0, scratch, clock) == 0) readings[Reading.GPU_CLOCK.ordinal()] = value();
        if (power != 0 && JNI.invokePPI(device, scratch, power) == 0) readings[Reading.GPU_POWER.ordinal()] = value() / 1000.0;
        if (fan != 0 && JNI.invokePPI(device, scratch, fan) == 0) readings[Reading.GPU_FAN.ordinal()] = value();
    }

    private double value() {
        return Integer.toUnsignedLong(MemoryUtil.memGetInt(scratch));
    }
}
