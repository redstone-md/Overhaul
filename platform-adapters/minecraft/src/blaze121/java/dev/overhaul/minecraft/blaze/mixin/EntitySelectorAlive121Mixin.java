package dev.overhaul.minecraft.blaze.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.commands.arguments.selector.EntitySelectorParser;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@code @e} and {@code @n} test that an entity is alive before any option, which reads its health through synched data
 * on every entity a datapack tick function scans. The options are tags, types and scores that reject most entities
 * with a set lookup, so the alive test moves behind them. Every predicate is pure, so the selected entities are the same.
 */
@Mixin(EntitySelectorParser.class)
abstract class EntitySelectorAlive121Mixin {
    @Shadow @Final private List<Predicate<Entity>> predicates;
    @Unique private boolean overhaul$alive;

    @WrapOperation(method = "parseSelector", at = @At(value = "INVOKE", target = "Ljava/util/List;add(Ljava/lang/Object;)Z"), require = 1)
    private boolean overhaul$deferAlive(List<Predicate<Entity>> list, Object predicate, Operation<Boolean> add) {
        if (list != predicates) return add.call(list, predicate);
        overhaul$alive = true;
        return true;
    }

    @Inject(method = "getSelector", at = @At("HEAD"))
    private void overhaul$appendAlive(CallbackInfoReturnable<?> info) {
        if (!overhaul$alive) return;
        overhaul$alive = false;
        predicates.add(Entity::isAlive);
    }
}
