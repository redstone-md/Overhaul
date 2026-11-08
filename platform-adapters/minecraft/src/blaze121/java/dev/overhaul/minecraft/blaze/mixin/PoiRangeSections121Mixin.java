package dev.overhaul.minecraft.blaze.mixin;

import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiSection;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.SectionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A query for points of interest within a distance of a block walked every section of every chunk column it covers,
 * from the bottom of the world to the top, and only then dropped the records farther away than the distance. A record
 * within the distance is also within it on the vertical axis, so only the sections that span that height are read.
 * Chunks, sections and records come in the same order as before, so the nearest of equals stays the same one.
 */
@Mixin(PoiManager.class)
abstract class PoiRangeSections121Mixin extends SectionStorage<PoiSection> {
    private PoiRangeSections121Mixin() {
        super(null, null, null, null, null, null);
    }

    @Inject(method = "getInRange", at = @At("HEAD"), cancellable = true, require = 0)
    private void overhaul$rangeSections(Predicate<Holder<PoiType>> type, BlockPos pos, int distance,
            PoiManager.Occupancy occupancy, CallbackInfoReturnable<Stream<PoiRecord>> result) {
        if (distance < 0) return;
        int lowest = Math.max(levelHeightAccessor.getMinSection(), SectionPos.blockToSectionCoord(pos.getY() - distance));
        int above = Math.min(levelHeightAccessor.getMaxSection(), SectionPos.blockToSectionCoord(pos.getY() + distance) + 1);
        long reach = (long) distance * distance;
        int chunks = Math.floorDiv(distance, 16) + 1;
        result.setReturnValue(ChunkPos.rangeClosed(new ChunkPos(pos), chunks)
                .flatMap(chunk -> IntStream.range(lowest, above)
                        .mapToObj(y -> getOrLoad(SectionPos.of(chunk, y).asLong()))
                        .filter(Optional::isPresent)
                        .flatMap(section -> section.get().getRecords(type, occupancy)))
                .filter(record -> {
                    BlockPos at = record.getPos();
                    return Math.abs(at.getX() - pos.getX()) <= distance && Math.abs(at.getZ() - pos.getZ()) <= distance
                            && at.distSqr(pos) <= reach;
                }));
    }
}
