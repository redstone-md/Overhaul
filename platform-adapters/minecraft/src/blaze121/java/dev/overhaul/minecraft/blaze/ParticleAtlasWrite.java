package dev.overhaul.minecraft.blaze;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import dev.overhaul.minecraft.blaze.mixin.NativeImageAccess121Mixin;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.Util;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

/**
 * Inventory Particles caches each namespace's generated particle textures as one PNG atlas, and it writes that atlas on
 * the render thread pixel by pixel, then encodes and writes the PNG there too. The textures it reads belong to its
 * sprites, so they are still copied on the render thread, but row by row into an image owned here; the encoding and the
 * file writes then run on the IO pool, one namespace's writes after another in the order they came.
 */
public final class ParticleAtlasWrite {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<String, CompletableFuture<Void>> WRITES = new ConcurrentHashMap<>();
    private static final ClassValue<Region> REGIONS = new ClassValue<>() {
        @Override
        protected Region computeValue(Class<?> type) {
            return Region.of(type);
        }
    };

    private ParticleAtlasWrite() {}

    /** A write of a namespace's files that runs once the atlas has been composed. */
    @FunctionalInterface
    public interface Write {
        void run(NativeImage atlas) throws Exception;
    }

    /**
     * Composes the packed regions into one atlas on this thread and queues {@code write} for it on the IO pool after
     * the namespace's earlier writes. The atlas is closed once {@code write} is done.
     */
    public static void submit(String namespace, List<?> packed, Write write) {
        NativeImage atlas = compose(packed);
        WRITES.compute(namespace, (key, previous) -> (previous == null ? CompletableFuture.<Void>completedFuture(null) : previous)
                .thenRunAsync(() -> {
                    try (atlas) {
                        write.run(atlas);
                    } catch (Exception failure) {
                        LOGGER.error("Failed to cache particles atlas with id {}", namespace, failure);
                    }
                }, Util.ioPool()));
    }

    private static NativeImage compose(List<?> packed) {
        int width = 0, height = 0;
        for (Object region : packed) {
            Region access = REGIONS.get(region.getClass());
            width = Math.max(width, access.x(region) + access.width(region));
            height = Math.max(height, access.y(region) + access.height(region));
        }
        NativeImage atlas = new NativeImage(width, height, false);
        for (Object region : packed) {
            Region access = REGIONS.get(region.getClass());
            copy(access.image(region), atlas, access.x(region), access.y(region), access.width(region), access.height(region));
        }
        return atlas;
    }

    private static void copy(NativeImage source, NativeImage atlas, int x, int y, int width, int height) {
        if (source.format() != NativeImage.Format.RGBA || width > source.getWidth() || height > source.getHeight()) {
            for (int row = 0; row < height; row++) {
                for (int column = 0; column < width; column++) {
                    atlas.setPixelRGBA(x + column, y + row, source.getPixelRGBA(column, row));
                }
            }
            return;
        }
        long from = ((NativeImageAccess121Mixin) (Object) source).overhaul$pixels();
        long to = ((NativeImageAccess121Mixin) (Object) atlas).overhaul$pixels();
        if (from == 0L || to == 0L) throw new IllegalStateException("Image is not allocated");
        for (int row = 0; row < height; row++) {
            MemoryUtil.memCopy(from + 4L * row * source.getWidth(), to + 4L * ((long) (y + row) * atlas.getWidth() + x), 4L * width);
        }
    }

    private record Region(MethodHandle image, MethodHandle x, MethodHandle y, MethodHandle width, MethodHandle height) {
        static Region of(Class<?> type) {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            try {
                MethodType integer = MethodType.methodType(int.class);
                return new Region(lookup.findVirtual(type, "image", MethodType.methodType(NativeImage.class)),
                        lookup.findVirtual(type, "x", integer), lookup.findVirtual(type, "y", integer),
                        lookup.findVirtual(type, "width", integer), lookup.findVirtual(type, "height", integer));
            } catch (ReflectiveOperationException missing) {
                throw new IllegalStateException("Unexpected atlas region " + type.getName(), missing);
            }
        }

        NativeImage image(Object region) {
            try {
                return (NativeImage) image.invoke(region);
            } catch (Throwable failure) {
                throw new IllegalStateException(failure);
            }
        }

        int x(Object region) {
            return number(x, region);
        }

        int y(Object region) {
            return number(y, region);
        }

        int width(Object region) {
            return number(width, region);
        }

        int height(Object region) {
            return number(height, region);
        }

        private static int number(MethodHandle accessor, Object region) {
            try {
                return (int) accessor.invoke(region);
            } catch (Throwable failure) {
                throw new IllegalStateException(failure);
            }
        }
    }
}
