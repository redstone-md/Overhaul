package dev.overhaul.minecraft.mixin;

import dev.overhaul.minecraft.world.SectionQueryAccess;
import dev.overhaul.minecraft.world.SectionQueryTraversal;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongSortedSet;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.Map;

/** Reuses the authoritative index; structural epochs protect the game's callback iterator semantics. */
@Mixin(EntitySectionStorage.class)
public abstract class EntitySectionStorageMixin<T extends EntityAccess> implements SectionQueryAccess {
    @Shadow @Final private Long2ObjectMap<EntitySection<T>> sections;
    @Shadow @Final private LongSortedSet sectionIds;
    @Unique private SectionQueryTraversal<T> overhaul$queries;

    @Inject(method="<init>",at=@At("TAIL"),require=1)
    private void overhaul$initialize(CallbackInfo callback) {
        overhaul$queries=new SectionQueryTraversal<>(sections,sectionIds);
    }

    @Inject(method="createSection(J)Lnet/minecraft/world/level/entity/EntitySection;",at=@At("HEAD"),require=1)
    private void overhaul$creating(CallbackInfoReturnable<EntitySection<T>> callback) {
        overhaul$queries.structureChanged();
    }

    @Inject(method="createSection(J)Lnet/minecraft/world/level/entity/EntitySection;",at=@At("RETURN"),require=1)
    private void overhaul$created(long key,CallbackInfoReturnable<EntitySection<T>> callback) {
        overhaul$queries.sectionCreated(key);
    }

    @Inject(method="remove(J)V",at=@At("HEAD"),require=1)
    private void overhaul$removing(long key,CallbackInfo callback) {
        overhaul$queries.sectionRemoving(key);
    }

    @Inject(method="forEachAccessibleNonEmptySection(Lnet/minecraft/world/phys/AABB;Lnet/minecraft/util/AbortableIterationConsumer;)V",
            at=@At("HEAD"),cancellable=true,require=1)
    private void overhaul$query(AABB bounds,AbortableIterationConsumer<EntitySection<T>> visitor,CallbackInfo callback) {
        if(overhaul$queries.tryTraverse(bounds,visitor))callback.cancel();
    }

    @Override public Map<String,Long> overhaul$queryStatistics(){return overhaul$queries.snapshot();}
    @Override public void overhaul$withVanilla(Runnable action){overhaul$queries.withVanilla(action);}
}
