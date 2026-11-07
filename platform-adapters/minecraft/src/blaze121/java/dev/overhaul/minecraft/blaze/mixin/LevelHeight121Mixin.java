package dev.overhaul.minecraft.blaze.mixin;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.dimension.DimensionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Every block lookup checks the build height, and vanilla answers it through the level's dimension type holder each
 * time. A level's dimension type never changes, so its floor and height are read once and kept in one field. They are
 * read on first use, through {@code dimensionType()}, so a level subclass that answers its own dimension type is still
 * asked; one that overrides the height methods themselves still wins over these.
 */
@Mixin(Level.class)
abstract class LevelHeight121Mixin implements LevelReader {
    /**
     * Floor in the high half, height in the low half, written in one store so no thread sees one without the other.
     * Zero until first asked: a dimension is always at least 16 blocks tall.
     */
    @Unique private long overhaul$heights;

    @Override
    public int getMinBuildHeight() {
        return (int) (overhaul$heights() >> 32);
    }

    @Override
    public int getHeight() {
        return (int) overhaul$heights();
    }

    @Unique
    private long overhaul$heights() {
        long heights = overhaul$heights;
        if (heights != 0L) return heights;
        DimensionType type = dimensionType();
        return overhaul$heights = (long) type.minY() << 32 | type.height() & 0xFFFFFFFFL;
    }
}
