package dev.overhaul.minecraft.blaze;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.slf4j.LoggerFactory;

/**
 * The palette a container currently holds. The container keeps it in a private record, so it is read through
 * handles found once. When they cannot be found, no palette is ever reported and callers keep their own path.
 */
public final class BlazeSectionPalettes {
    private static final MethodHandle DATA;
    private static final MethodHandle PALETTE;

    static {
        MethodHandle data = null;
        MethodHandle palette = null;
        try {
            Field dataField = PalettedContainer.class.getDeclaredField("data");
            Field paletteField = dataField.getType().getDeclaredField("palette");
            dataField.setAccessible(true);
            paletteField.setAccessible(true);
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            data = lookup.unreflectGetter(dataField).asType(MethodType.methodType(Object.class, PalettedContainer.class));
            palette = lookup.unreflectGetter(paletteField).asType(MethodType.methodType(Palette.class, Object.class));
        } catch (ReflectiveOperationException | RuntimeException failure) {
            data = null;
            palette = null;
            LoggerFactory.getLogger(BlazeSectionPalettes.class).info("Overhaul sectionPalettes off: {}", failure.toString());
        }
        DATA = data;
        PALETTE = palette;
    }

    private BlazeSectionPalettes() {}

    /** The palette the container holds now, or null when it cannot be read. */
    public static Palette<?> of(PalettedContainer<?> container) {
        if (DATA == null) return null;
        try {
            return (Palette<?>) PALETTE.invokeExact(DATA.invokeExact(container));
        } catch (Throwable failure) {
            return null;
        }
    }
}
