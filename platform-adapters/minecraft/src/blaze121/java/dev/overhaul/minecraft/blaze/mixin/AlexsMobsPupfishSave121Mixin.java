package dev.overhaul.minecraft.blaze.mixin;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Alex's Mobs picks one pupfish chunk per world by sampling a noise column every ten ticks, up to five minutes, and
 * writes the result into its world data, but never marks that data changed, so it is not saved and every session
 * searches again (here 114 columns, about a minute, each launch). The data is marked once the search has settled.
 */
@Pseudo
@Mixin(targets = "com.github.alexthe666.alexsmobs.world.AMWorldData", remap = false)
abstract class AlexsMobsPupfishSave121Mixin extends SavedData {
    @Shadow private ChunkPos pupfishChunk;
    @Shadow private boolean noPupfishChunk;
    @Unique private boolean overhaul$settled;

    @Inject(method = "tickPupfish()V", at = @At("TAIL"), require = 0)
    private void overhaul$saveSettled(CallbackInfo callback) {
        if (overhaul$settled || pupfishChunk == null && !noPupfishChunk) return;
        overhaul$settled = true;
        setDirty();
    }
}
