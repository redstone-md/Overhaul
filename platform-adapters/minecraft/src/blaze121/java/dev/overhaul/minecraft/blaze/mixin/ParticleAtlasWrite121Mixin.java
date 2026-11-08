package dev.overhaul.minecraft.blaze.mixin;

import dev.overhaul.minecraft.blaze.ParticleAtlasWrite;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Inventory Particles writes each namespace's atlas cache from a task on the render thread. The task now packs the
 * regions and copies their pixels there and leaves the PNG and the region list to {@link ParticleAtlasWrite}, which
 * writes them under the same per-namespace lock the cache loads under.
 */
@Pseudo
@Mixin(targets = "net.lopymine.ip.family.cache.FamilyParticlesAtlasCacheManager", remap = false)
abstract class ParticleAtlasWrite121Mixin {
    @Shadow
    private static Object lock(String namespace) {
        throw new AssertionError();
    }

    @Shadow
    @SuppressWarnings("rawtypes")
    private static List packRegions(List regions) {
        throw new AssertionError();
    }

    @Shadow
    @SuppressWarnings("rawtypes")
    private static void saveRegions(Path file, List packed) throws IOException {
        throw new AssertionError();
    }

    @Inject(method = "lambda$save$1(Ljava/lang/String;Ljava/nio/file/Path;Ljava/util/List;)V", at = @At("HEAD"),
            cancellable = true, require = 0)
    private static void overhaul$writeOffThread(String namespace, Path folder, List<?> regions, CallbackInfo callback) {
        callback.cancel();
        List<?> packed = packRegions(regions);
        ParticleAtlasWrite.submit(namespace, packed, atlas -> {
            synchronized (lock(namespace)) {
                Files.createDirectories(folder);
                atlas.writeToFile(folder.resolve("atlas.png"));
                saveRegions(folder.resolve("regions.txt"), packed);
            }
        });
    }
}
