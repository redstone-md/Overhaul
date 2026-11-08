package dev.overhaul.minecraft.blaze.mixin;

import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntityLookup;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The lookup of the entities a persistent manager is tracking, the one its level's entity scans walk. */
@Mixin(PersistentEntitySectionManager.class)
public interface PersistentEntityLookup121Access<T extends EntityAccess> {
    @Accessor("visibleEntityStorage")
    EntityLookup<T> overhaul$visible();
}
