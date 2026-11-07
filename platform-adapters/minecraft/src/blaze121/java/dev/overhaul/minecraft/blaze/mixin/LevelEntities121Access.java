package dev.overhaul.minecraft.blaze.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.LevelEntityGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The level's entity getter, the same one its own entity queries walk. */
@Mixin(Level.class)
public interface LevelEntities121Access {
    @Invoker("getEntities")
    LevelEntityGetter<Entity> overhaul$entities();
}
