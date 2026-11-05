package dev.overhaul.minecraft.blaze.mixin;

import dev.overhaul.minecraft.blaze.BlazeEntityTypeRenderer;
import java.util.Map;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.EntityType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(EntityType.class)
abstract class EntityTypeRenderer121Mixin implements BlazeEntityTypeRenderer {
    @Unique private Map<?,?> overhaul$source;
    @Unique private EntityRenderer<?> overhaul$cached;

    @Override public Map<?,?> overhaul$rendererSource(){return overhaul$source;}
    @Override public EntityRenderer<?> overhaul$renderer(){return overhaul$cached;}
    @Override public void overhaul$renderer(Map<?,?> source,EntityRenderer<?> renderer){overhaul$source=source;overhaul$cached=renderer;}
}
