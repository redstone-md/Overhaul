package dev.overhaul.minecraft.backend;

import java.util.ArrayList;
import java.util.List;

/** Exception-safe temporary resource retention around a completed native operation. */
final class BackendPins implements AutoCloseable {
    private final List<AutoCloseable> pins=new ArrayList<>();

    BackendPins(OverhaulDeviceBackend owner,Object... resources) {
        try { for (Object resource:resources) if (resource!=null) pins.add(owner.retain(resource)); }
        catch (RuntimeException|Error failure) { close(); throw failure; }
    }

    @Override public void close() {
        RuntimeException failure=null;
        for (int index=pins.size()-1;index>=0;index--) {
            try { pins.get(index).close(); }
            catch (Exception error) {
                if (failure==null) failure=new IllegalStateException("Cannot release recorded resource",error);
                else failure.addSuppressed(error);
            }
        }
        pins.clear();
        if (failure!=null) throw failure;
    }
}
