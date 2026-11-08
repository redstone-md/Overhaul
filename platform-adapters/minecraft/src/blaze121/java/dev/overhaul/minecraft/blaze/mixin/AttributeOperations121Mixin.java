package dev.overhaul.minecraft.blaze.mixin;

import com.google.common.collect.Maps;
import java.util.EnumMap;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Every new attribute instance builds an {@code EnumMap} of modifier operations, and on this pack each build fetched
 * the enum's constants through a reflective {@code values()} call. A copy of one empty map shares its key universe
 * and needs no lookup.
 */
@Mixin(AttributeInstance.class)
abstract class AttributeOperations121Mixin {
    @Unique
    private static final EnumMap<AttributeModifier.Operation, Object> overhaul$EMPTY = new EnumMap<>(AttributeModifier.Operation.class);

    @Redirect(method = "<init>", at = @At(value = "INVOKE",
            target = "Lcom/google/common/collect/Maps;newEnumMap(Ljava/lang/Class;)Ljava/util/EnumMap;"), require = 0)
    @SuppressWarnings({"unchecked", "rawtypes"})
    private EnumMap overhaul$copyEmpty(Class type) {
        return type == AttributeModifier.Operation.class ? new EnumMap(overhaul$EMPTY) : Maps.newEnumMap(type);
    }
}