package dev.overhaul.minecraft.blaze.mixin;

import dev.overhaul.minecraft.blaze.BlazeGunIdle;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Tells whether TACZ's gun state tick can be skipped for a living entity: it can while the entity is a mob that has
 * never held a gun in either hand. Once one is seen in hand the entity ticks as before for good; players always tick.
 * Its higher priority makes it apply after TACZ's mixin, and the plugin's post-apply step then guards TACZ's tick
 * handler in the merged class (see {@code TaczUnarmedPatch}).
 */
@Mixin(value = LivingEntity.class, priority = 1500)
abstract class TaczUnarmedTick121Mixin implements BlazeGunIdle {
    @Unique private static final String OVERHAUL$GUN = "com.tacz.guns.api.item.IGun";
    @Unique private static final ClassValue<Boolean> OVERHAUL$GUNS = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            return overhaul$implementsGun(type);
        }
    };

    @Unique private boolean overhaul$armed;

    @Unique
    private static boolean overhaul$implementsGun(Class<?> type) {
        for (Class<?> at = type; at != null; at = at.getSuperclass()) {
            for (Class<?> face : at.getInterfaces()) {
                if (face.getName().equals(OVERHAUL$GUN) || overhaul$implementsGun(face)) return true;
            }
        }
        return false;
    }

    @Override
    public boolean overhaul$gunIdle() {
        if (overhaul$armed) return false;
        LivingEntity self = (LivingEntity) (Object) this;
        if (!(self instanceof Player) && !OVERHAUL$GUNS.get(self.getMainHandItem().getItem().getClass())
                && !OVERHAUL$GUNS.get(self.getOffhandItem().getItem().getClass())) return true;
        overhaul$armed = true;
        return false;
    }
}
