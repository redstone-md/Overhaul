package dev.overhaul.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.lopymine.ip.element.mod.spawner.ParticleSpawnPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Shares equal spawn positions. The mod keeps millions of these immutable records, while texture
 * coordinates only produce a small set of distinct values.
 */
@Pseudo
@Mixin(targets = {
        "net.lopymine.ip.element.mod.spawner.ParticleSpawnArea",
        "net.lopymine.ip.family.cache.FamilyParticlesSpawnAreasCacheManager",
        "net.lopymine.ip.resourcepack.manager.ParticlesConfigsManager"
}, remap = false)
abstract class InventoryParticlesSpawnPosMixin {
    @Unique
    private static final Map<ParticleSpawnPos, ParticleSpawnPos> overhaul$SHARED = new ConcurrentHashMap<>();

    @WrapOperation(method = {"lambda$readFromTexture$0", "readNamespacePixels", "lambda$getParticleSpawnPos$16"},
            at = @At(value = "NEW", target = "(IIII)Lnet/lopymine/ip/element/mod/spawner/ParticleSpawnPos;"),
            require = 0, remap = false)
    private static ParticleSpawnPos overhaul$share(int x, int y, int width, int height, Operation<ParticleSpawnPos> original) {
        ParticleSpawnPos created = original.call(x, y, width, height);
        ParticleSpawnPos shared = overhaul$SHARED.putIfAbsent(created, created);
        return shared != null ? shared : created;
    }
}
