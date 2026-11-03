package dev.overhaul.minecraft.backend;

import java.util.Map;

/** Allocation-policy counters; device preference does not assert dedicated VRAM on unified-memory hardware. */
final class BufferStorageStatistics {
    private long hostAllocations,deviceAllocations,hostBytes,deviceBytes,peakBytes;

    void allocated(boolean host,long bytes) {
        if (host) { hostAllocations++; hostBytes+=bytes; }
        else { deviceAllocations++; deviceBytes+=bytes; }
        peakBytes=Math.max(peakBytes,hostBytes+deviceBytes);
    }
    void released(boolean host,long bytes) {
        if (host) hostBytes-=bytes;
        else deviceBytes-=bytes;
    }
    Map<String,Long> snapshot() {
        return Map.of("hostAccessibleAllocations",hostAllocations,"devicePreferredAllocations",deviceAllocations,
                "liveHostAccessibleBytes",hostBytes,"liveDevicePreferredBytes",deviceBytes,"peakLiveBytes",peakBytes);
    }
}
