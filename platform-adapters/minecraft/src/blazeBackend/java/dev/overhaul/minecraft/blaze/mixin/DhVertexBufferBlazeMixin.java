package dev.overhaul.minecraft.blaze.mixin;

import dev.overhaul.minecraft.blaze.BlazeDhDeviceSlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;

/** Gives each Distant Horizons vertex buffer a slot for the device buffer it resolved to. */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.common.render.openGl.glObject.buffer.GLVertexBuffer", remap = false)
abstract class DhVertexBufferBlazeMixin implements BlazeDhDeviceSlot.Holder {
    @Unique private final BlazeDhDeviceSlot overhaul$slot = new BlazeDhDeviceSlot();

    @Override
    public BlazeDhDeviceSlot overhaul$deviceSlot() {
        return overhaul$slot;
    }
}
