package dev.overhaul.minecraft.blaze.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.brigadier.StringReader;
import dev.overhaul.minecraft.blaze.BlazeSelectorTags;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.commands.arguments.selector.EntitySelectorParser;
import net.minecraft.commands.arguments.selector.options.EntitySelectorOptions;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Notes the tag each {@code tag=name} option and the type tag each {@code type=#name} option requires, read back from
 * the text the option consumed, and the tag each {@code tag=!name} option excludes, and hands them to the selector.
 */
@Mixin(EntitySelectorParser.class)
abstract class EntitySelectorParserTags121Mixin {
    @Shadow @Final private StringReader reader;
    @Unique private String overhaul$option;
    @Unique private List<String> overhaul$tags;
    @Unique private List<String> overhaul$excluded;
    @Unique private List<TagKey<EntityType<?>>> overhaul$typeTags;

    @WrapOperation(method = "parseOptions", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/commands/arguments/selector/options/EntitySelectorOptions;get(Lnet/minecraft/commands/arguments/selector/EntitySelectorParser;Ljava/lang/String;I)Lnet/minecraft/commands/arguments/selector/options/EntitySelectorOptions$Modifier;"),
            require = 1)
    private EntitySelectorOptions.Modifier overhaul$noteOption(EntitySelectorParser parser, String key, int cursor, Operation<EntitySelectorOptions.Modifier> get) {
        overhaul$option = key;
        return get.call(parser, key, cursor);
    }

    @WrapOperation(method = "parseOptions", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/commands/arguments/selector/options/EntitySelectorOptions$Modifier;handle(Lnet/minecraft/commands/arguments/selector/EntitySelectorParser;)V"),
            require = 1)
    private void overhaul$noteTag(EntitySelectorOptions.Modifier modifier, EntitySelectorParser parser, Operation<Void> handle) {
        int start = reader.getCursor();
        handle.call(modifier, parser);
        boolean tag = "tag".equals(overhaul$option);
        if (!tag && !"type".equals(overhaul$option)) return;
        String value = reader.getString().substring(start, reader.getCursor()).trim();
        if (tag && value.length() > 1 && value.charAt(0) == '!') {
            if (overhaul$excluded == null) overhaul$excluded = new ArrayList<>(4);
            overhaul$excluded.add(value.substring(1).trim());
            return;
        }
        if (value.isEmpty() || value.charAt(0) == '!') return;
        if (tag) {
            if (overhaul$tags == null) overhaul$tags = new ArrayList<>(2);
            overhaul$tags.add(value);
            return;
        }
        ResourceLocation id = value.charAt(0) == '#' ? ResourceLocation.tryParse(value.substring(1)) : null;
        if (id == null) return;
        if (overhaul$typeTags == null) overhaul$typeTags = new ArrayList<>(1);
        overhaul$typeTags.add(TagKey.create(Registries.ENTITY_TYPE, id));
    }

    @Inject(method = "getSelector", at = @At("RETURN"))
    @SuppressWarnings("unchecked")
    private void overhaul$handTags(CallbackInfoReturnable<EntitySelector> info) {
        BlazeSelectorTags selector = (BlazeSelectorTags) info.getReturnValue();
        if (overhaul$tags != null) selector.overhaul$requiredTags(overhaul$tags.toArray(String[]::new));
        if (overhaul$excluded != null) selector.overhaul$excludedTags(overhaul$excluded.toArray(String[]::new));
        if (overhaul$typeTags != null) selector.overhaul$requiredTypeTags(overhaul$typeTags.toArray(TagKey[]::new));
    }
}
