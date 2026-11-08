package dev.overhaul.minecraft.blaze.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The server level's entity manager, whose visible lookup its entity scans walk. */
@Mixin(ServerLevel.class)
public interface ServerLevelEntityManager121Access {
    @Accessor("entityManager")
    PersistentEntitySectionManager<Entity> overhaul$entityManager();
}
